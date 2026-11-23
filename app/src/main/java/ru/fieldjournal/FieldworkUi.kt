package ru.fieldjournal

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

private val photoReads = Executors.newFixedThreadPool(2)

private fun decodePhoto(file: File, maxSize: Int): Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, options)
    options.inJustDecodeBounds = false
    options.inSampleSize = 1
    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > maxSize) options.inSampleSize *= 2
    return BitmapFactory.decodeFile(file.path, options)
}

private fun MainActivity.readPhoto(attachment: Attachment, size: Int, complete: (Bitmap?) -> Unit) {
    val file = store.attachmentFile(attachment.id)
    photoReads.execute {
        val bitmap = runCatching { decodePhoto(file, size) }.getOrNull()
        runOnUiThread { if (!isDestroyed && !isFinishing) complete(bitmap) }
    }
}

internal fun MainActivity.photoThumbnail(attachment: Attachment, open: () -> Unit): ImageView =
    ImageView(this).apply {
        background = panel(Palette.pale, 12)
        clipToOutline = true
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = "Фото · ${attachment.name}"
        tooltipText = attachment.name
        setOnClickListener { open() }
        readPhoto(attachment, 640) { setImageBitmap(it) }
    }

internal fun MainActivity.openPhotoGallery(photos: List<Attachment>, start: Int) {
    if (photos.isEmpty()) return
    var index = start.coerceIn(photos.indices)
    val image = ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = panel(Palette.pale, 14)
        clipToOutline = true
    }
    val name = label("", 14f, Palette.ink)
    image.contentDescription = "Фотография"
    val previous = action("Предыдущее") {}
    val next = action("Следующее") {}
    fun display() {
        val selection = index
        val attachment = photos[index]
        name.text = "${index + 1} из ${photos.size} · ${attachment.name}"
        image.contentDescription = attachment.name
        image.setImageDrawable(null)
        previous.isEnabled = index > 0
        next.isEnabled = index < photos.lastIndex
        readPhoto(attachment, 1600) { bitmap ->
            if (selection == index) image.setImageBitmap(bitmap)
        }
    }
    previous.setOnClickListener { if (index > 0) { index--; display() } }
    next.setOnClickListener { if (index < photos.lastIndex) { index++; display() } }
    fieldworkSheet("Фотографии") {
        addView(image, -1, dp(300))
        addSpace(8)
        addView(name)
        if (photos.size > 1) {
            addSpace(8)
            addView(horizontal().apply {
                addView(previous, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
                addView(next, LinearLayout.LayoutParams(0, -2, 1f))
            }, -1, -2)
        }
        addSpace(8)
        addView(action("Открыть файл") { openExternalAttachment(photos[index]) }, -1, -2)
    }
    display()
}

internal fun MainActivity.compareObservationPhotos(siteId: String) {
    val byDate = store.noteSummaries(siteId).mapNotNull { store.note(it.id) }.flatMap { note ->
        note.attachments.filter { it.mime.startsWith("image/") }.map { file ->
            val record = note.research.entries.firstOrNull { it.kind == "media" && it.fields["original"] == file.id }
            val context = listOfNotNull(record?.fields?.get("station"), record?.fields?.get("direction"))
            Triple(file, "${dateLabel(note.created)} · ${note.title}", context.joinToString(" · "))
        }
    }
    fieldworkSheet("Сравнение снимков осмотров") { _ ->
        if (byDate.size < 2) {
            addView(label("Сделайте снимки хотя бы в двух наблюдениях или добавьте второй кадр", 14f, Palette.muted))
            return@fieldworkSheet
        }
        fun choose(caption: String, value: (Int) -> Unit) {
            android.app.AlertDialog.Builder(this@compareObservationPhotos).setTitle(caption)
                .setItems(byDate.map { "${it.second} · ${it.first.name} · ${it.third}" }.toTypedArray()) { _, index ->
                    value(index)
                }.setPositiveButton("Отмена", null).show()
        }
        val selection = vertical()
        addView(selection, -1, -2)
        var top = 0
        var bottom = 1
        fun redraw() {
            selection.removeAllViews()
            listOf(top, bottom).forEachIndexed { index, chosen ->
                val (file, description, context) = byDate[chosen]
                with(selection) {
                    addView(label(description, 14f, Palette.heading, true))
                    if (context.isNotEmpty()) addView(label(context, 13f, Palette.muted))
                    addView(photoThumbnail(file) { openPhotoGallery(listOf(file), 0) }, -1, dp(142))
                    addView(action("Выбрать ${if (index == 0) "верхний" else "нижний"} кадр") {
                        choose("Снимки наблюдений") { value ->
                            if (index == 0) top = value else bottom = value
                            redraw()
                        }
                    }, -1, -2)
                    addSpace(6)
                }
            }
        }
        redraw()
    }
}

internal fun MainActivity.showSitePreview(site: Site) {
    val latest = store.latestObservation(site.id)
    val photos = (latest?.attachments.orEmpty() + site.attachments)
        .filter { it.mime.startsWith("image/") }.distinctBy(Attachment::id)
    val trip = store.trips.active()
    val visit = store.trips.latestVisit(site.id)
    fieldworkSheet(site.title) { dialog ->
        addView(label(site.symbol.title, 15f, Palette.accent, true))
        site.parentId?.let(store::site)?.let { parent ->
            addView(action("В составе «${parent.title}»") { dialog.dismiss(); openSite(parent.id) }, -1, -2)
        }
        verificationSummary(site.uncertainFields)?.let { addView(label(it, 13f, Palette.rust)) }
        addSpace(5)
        visit?.let { addView(label("Последнее посещение · ${dateLabel(it)}", 13f, Palette.muted)) }
        addView(label(latest?.let { "Последнее наблюдение · ${dateLabel(it.created)}" }
            ?: "Наблюдений пока нет", 13f, Palette.muted))
        if (photos.isNotEmpty()) {
            addSpace(10)
            addView(photoThumbnail(photos.first()) { openPhotoGallery(photos, 0) }, -1, dp(132))
        }
        addSpace(12)
        addView(addAction("Новое наблюдение", FieldIcon.NOTE) {
            dialog.dismiss()
            createNote(site.id, compact = true)
        }, -1, -2)
        addSpace(6)
        addView(action("Открыть карточку") {
            dialog.dismiss()
            openSite(site.id)
        }, -1, -2)
        if (trip != null) {
            addSpace(6)
            addView(addAction("Отметить посещение", FieldIcon.POINT, primary = false) {
                runCatching { store.trips.visit(trip.id, site.id) }.onSuccess {
                    dialog.dismiss()
                    message("Посещение добавлено в выезд")
                }.onFailure { message("Не удалось отметить посещение") }
            }, -1, -2)
        }
    }
}

internal fun MainActivity.showDrafts(list: LinearLayout) {
    val drafts = store.drafts()
    if (drafts.isEmpty()) empty(list, "Черновиков пока нет", "Незавершённые карточки и записи появятся здесь.")
    for (draft in drafts) {
        card(list, draft.title, if (draft.note == null) "Карточка объекта" else "Запись дневника") {
            fieldworkSheet(draft.title) { dialog ->
                addView(action("Продолжить", true) {
                    dialog.dismiss()
                    resumeDraft(draft)
                }, -1, -2)
                addSpace(6)
                addView(action("Удалить черновик") {
                    confirm("Удалить черновик?", "Сохранённые записи останутся в дневнике.") {
                        runCatching { store.discardDraft(draft) }.onSuccess {
                            dialog.dismiss()
                            refreshDiary()
                        }.onFailure { message("Не удалось удалить черновик") }
                    }
                }.apply { setTextColor(Palette.rust) }, -1, -2)
            }
        }
    }
}

internal fun MainActivity.showTrips(list: LinearLayout) {
    val trips = store.trips.list()
    if (trips.none { it.endedAt == null }) {
        list.addView(addAction("Новый выезд", FieldIcon.TRIP) { openNewTrip() }, -1, -2)
        list.addSpace(8)
    }
    if (trips.isEmpty()) empty(list, "Выездов пока нет", "Выезд объединяет записи, посещения и маршрут одной полевой работы.")
    for (trip in trips) {
        val status = if (trip.endedAt == null) "Текущий выезд" else "Завершён"
        card(list, trip.title, "$status · ${dateLabel(trip.startedAt)}") { showTripDetails(trip.id) }
    }
}

internal fun MainActivity.showTripPage(list: LinearLayout, tripId: String) {
    val contents = runCatching { store.trips.contents(tripId) }.getOrElse {
        empty(list, "Выезд не найден", "Вернитесь к списку выездов.")
        list.addView(action("Все выезды") { closeTripDetails() }, -1, -2)
        return
    }
    val trip = contents.trip
    list.addView(action("‹ Все выезды") { closeTripDetails() }, -1, -2)
    list.addSpace(10)
    list.addView(label(trip.title, 22f, Palette.heading, true))
    list.addView(label(trip.profile, 14f, Palette.muted))
    list.addView(action("Программа работ") {
        editResearch("Программа работ", trip.research, null, tripProfile = trip.profile) {
            store.trips.updateResearch(trip.copy(research = it))
            refreshDiary()
        }
    }, -1, -2)
    list.addView(label("Начат · ${dateLabel(trip.startedAt)}", 13f, Palette.muted))
    trip.endedAt?.let { list.addView(label("Завершён · ${dateLabel(it)}", 13f, Palette.muted)) }
    list.addSpace(8)
    list.addView(action("Записи выезда · ${contents.notes.size}") { showTripRecords(trip.id) }, -1, -2)
    list.addSpace(6)
    list.addView(addAction("Запись в выезд", FieldIcon.NOTE) { createNote(null, compact = true, tripId = trip.id) }, -1, -2)
    list.addSpace(10)
    val stats = label("", 14f, Palette.muted)
    list.addView(stats)
    list.addSpace(6)
    val recorder = if (trip.endedAt == null) action("Записать трек") {
        if (TripRecording.tripId == trip.id) TripRecordingService.pause(this)
        else startTripRecording(trip.id)
    } else null
    recorder?.let { list.addView(it, -1, -2); list.addSpace(6) }
    var hasRoute = contents.points.isNotEmpty()
    val routeToggle = action("Показать маршрут") {
        if (hasRoute && showingTripTrack(trip.id)) hideTripTrack(trip.id) else showTripOnMap(trip.id)
    }
    list.addView(routeToggle, -1, -2)
    if (contents.visits.isNotEmpty()) {
        list.addSpace(8)
        list.addView(action("Осмотры · ${contents.visits.size}") {
            fieldworkSheet("Осмотры выезда") {
                for (visit in contents.visits) addView(label(
                    "${dateLabel(visit.time)} · ${visit.sites.firstOrNull()?.title ?: visit.siteId}", 14f, Palette.ink))
            }
        }, -1, -2)
    }
    if (contents.sites.isNotEmpty()) {
        list.addSpace(10)
        list.addView(label("Посещённые объекты", 17f, Palette.heading, true))
        list.addSpace(6)
        for (site in contents.sites) card(list, site.title, site.symbol.title) { openSite(site.id) }
    }
    if (trip.endedAt == null) {
        list.addSpace(8)
        list.addView(action("Завершить выезд") {
            confirm("Завершить выезд?", "Маршрут, посещения и записи будут доступны в истории выездов.") {
                runCatching { finishTrip(trip.id) }.onSuccess { refreshDiary() }
                    .onFailure { message("Не удалось завершить выезд") }
            }
        }, -1, -2)
    }
    if (trip.endedAt != null) {
        list.addSpace(8)
        list.addView(action("Зафиксировать новую редакцию") {
            confirm("Зафиксировать редакцию?", "Предыдущий комплект останется доступен в разделе «Данные».") {
                runCatching { store.trips.recordEdition(trip.id) }
                    .onSuccess { message("Сохранена редакция ${it.number}"); refreshDiary() }
                    .onFailure { message(it.message ?: "Не удалось сохранить редакцию") }
            }
        }, -1, -2)
    }
    list.addSpace(8)
    list.addView(action("Удалить выезд") {
        confirm("Удалить выезд?", "Маршрут и отметки посещений будут удалены. Записи, объекты и фотографии останутся в дневнике.") {
            runCatching { deleteTrip(trip.id) }.onSuccess { message("Выезд удалён") }
                .onFailure { message("Не удалось удалить выезд") }
        }
    }.apply { setTextColor(Palette.rust) }, -1, -2)
    fun update(points: List<TrackPoint>) {
        val km = String.format(Locale.forLanguageTag("ru"), "%.2f", contents.copy(points = points).distanceMeters / 1000)
        val recording = TripRecording.tripId == trip.id
        hasRoute = points.isNotEmpty()
        val showing = hasRoute && showingTripTrack(trip.id)
        stats.text = if (recording) "Запись трека · $km км" else "Трек · $km км · ${points.size} точек"
        recorder?.text = if (recording) "Пауза записи" else if (points.isEmpty()) "Записать трек" else "Продолжить запись"
        routeToggle.text = when {
            showing -> "Скрыть маршрут"
            points.isNotEmpty() -> "Показать маршрут"
            else -> "Показать объекты на карте"
        }
        routeToggle.isEnabled = showing || points.isNotEmpty() || contents.sites.isNotEmpty()
    }
    update(contents.points)
    watchTripPage { update(store.trips.points(tripId)) }
}

internal fun MainActivity.chooseTripExport() {
    val trips = store.trips.list()
    fieldworkSheet("Экспорт выезда") { dialog ->
        if (trips.isEmpty()) addView(label("В дневнике пока нет выездов", 14f, Palette.muted))
        for (trip in trips) card(this, trip.title, dateLabel(trip.startedAt)) {
            dialog.dismiss()
            fieldworkSheet(trip.title) { options ->
                addView(action("Маршрут GPX") { options.dismiss(); exportTrip(trip.id, forQgis = false) }
                    .apply { isEnabled = store.trips.points(trip.id).isNotEmpty() }, -1, -2)
                addSpace(6)
                val contents = store.trips.contents(trip.id)
                addView(label("Объекты · ${contents.sites.size} · записи · ${contents.notes.size} · осмотры · ${contents.visits.size}", 14f, Palette.muted))
                val editions = if (trip.endedAt == null) emptyList() else store.trips.editions(trip.id)
                if (editions.isEmpty()) addView(action("Комплект для QGIS", true) {
                    options.dismiss()
                    exportTrip(trip.id, forQgis = true)
                }, -1, -2)
                for (edition in editions.asReversed()) card(this, "QGIS · редакция ${edition.number}",
                    "${dateLabel(edition.time)} · объектов ${edition.contents.sites.size} · записей ${edition.contents.notes.size} · осмотров ${edition.contents.visits.size}") {
                    options.dismiss()
                    exportTrip(trip.id, forQgis = true, edition = edition.number)
                }
            }
        }
    }
}
