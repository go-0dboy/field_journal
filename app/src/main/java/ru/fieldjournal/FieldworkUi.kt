package ru.fieldjournal

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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

internal fun MainActivity.showSitePreview(site: Site) {
    val latest = store.latestObservation(site.id)
    val photos = (latest?.attachments.orEmpty() + site.attachments)
        .filter { it.mime.startsWith("image/") }.distinctBy(Attachment::id)
    val trip = store.trips.active()
    val visit = store.trips.latestVisit(site.id)
    fieldworkSheet(site.title) { dialog ->
        addView(label(objectType(site.type).title, 15f, Palette.green, true))
        addSpace(5)
        visit?.let { addView(label("Последнее посещение · ${dateLabel(it)}", 13f, Palette.muted)) }
        addView(label(latest?.let { "Последнее наблюдение · ${dateLabel(it.created)}" }
            ?: "Наблюдений пока нет", 13f, Palette.muted))
        if (photos.isNotEmpty()) {
            addSpace(10)
            addView(photoThumbnail(photos.first()) { openPhotoGallery(photos, 0) }, -1, dp(132))
        }
        addSpace(12)
        addView(action("Новое наблюдение", true) {
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
            addView(action("Отметить посещение") {
                runCatching { store.trips.visit(trip.id, site.id) }.onSuccess {
                    dialog.dismiss()
                    message("Посещение добавлено в выезд")
                }.onFailure { message("Не удалось отметить посещение") }
            }, -1, -2)
        }
    }
}

internal fun MainActivity.showDrafts() {
    val drafts = store.drafts()
    fieldworkSheet("Черновики") { dialog ->
        if (drafts.isEmpty()) addView(label("Незавершённых записей и карточек нет", 14f, Palette.muted))
        for (draft in drafts) {
            val group = vertical().apply {
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = panel(Palette.paper, 14)
            }
            group.addView(label(draft.title, 16f, Palette.ink, true))
            group.addView(label(if (draft.note == null) "Карточка объекта" else "Запись дневника", 13f, Palette.muted))
            group.addSpace(5)
            group.addView(horizontal().apply {
                addView(action("Продолжить", true) {
                    dialog.dismiss()
                    resumeDraft(draft)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
                addView(action("Удалить") {
                    confirm("Удалить черновик?", "Сохранённые записи останутся в дневнике.") {
                        runCatching { store.discardDraft(draft) }.onSuccess {
                            dialog.dismiss()
                            refreshDiary()
                            showDrafts()
                        }.onFailure { message("Не удалось удалить черновик") }
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }, -1, -2)
            addView(group, -1, -2)
            addSpace(8)
        }
    }
}

internal fun MainActivity.showTrips() {
    val trips = store.trips.list()
    fieldworkSheet("Выезды") { dialog ->
        if (trips.none { it.endedAt == null }) {
            addView(action("Начать выезд", true) {
                dialog.dismiss()
                promptTripName()
            }, -1, -2)
            addSpace(10)
        }
        for (trip in trips) {
            val text = if (trip.endedAt == null) "${trip.title} · текущий" else trip.title
            addView(action(text, primary = trip.endedAt == null) {
                dialog.dismiss()
                showTripDetails(trip.id)
            }, -1, -2)
            addSpace(6)
        }
    }
}

private fun MainActivity.promptTripName() {
    fieldworkSheet("Новый выезд") { dialog ->
        val name = edit("Название выезда", "Выезд · ${java.time.LocalDate.now()}")
        addView(name, -1, -2)
        addSpace(10)
        addView(action("Начать", true) {
            runCatching { store.trips.start(name.text.toString().take(120)) }.onSuccess { trip ->
                dialog.dismiss()
                selectTripTrack(trip.id)
                showTripDetails(trip.id)
            }.onFailure { message(it.message ?: "Не удалось начать выезд") }
        }, -1, -2)
    }
}

internal fun MainActivity.showTripDetails(tripId: String) {
    val contents = runCatching { store.trips.contents(tripId) }.getOrElse {
        message(it.message ?: "Не удалось открыть выезд")
        return
    }
    val trip = contents.trip
    val stats = label("", 14f, Palette.muted)
    var recordingButton: TextView? = null
    var gpxButton: TextView? = null
    val dialog = fieldworkSheet(trip.title) { sheet ->
        addView(label(
            if (trip.endedAt == null) "Начат · ${dateLabel(trip.startedAt)}"
            else "Завершён · ${dateLabel(trip.endedAt)}", 13f, Palette.muted
        ))
        addSpace(4)
        addView(stats)
        addSpace(10)
        if (trip.endedAt == null) {
            val recording = action("Записать трек", true) {
                if (TripRecording.tripId == trip.id) TripRecordingService.pause(this@showTripDetails)
                else startTripRecording(trip.id)
            }
            recordingButton = recording
            addView(recording, -1, -2)
            addSpace(6)
            addView(action("Новое наблюдение") {
                sheet.dismiss()
                createNote(null, compact = true, tripId = trip.id)
            }, -1, -2)
            addSpace(6)
        }
        if (showingTripTrack(trip.id)) {
            addView(action("Скрыть маршрут") {
                sheet.dismiss()
                selectTripTrack(null)
                showTripDetails(trip.id)
            }, -1, -2)
            addSpace(6)
        }
        addView(action("Показать на карте") {
            sheet.dismiss()
            showTripOnMap(trip.id)
        }, -1, -2)
        addSpace(6)
        val gpx = action("Экспорт GPX") { sheet.dismiss(); exportTrip(trip.id, forQgis = false) }
        gpxButton = gpx
        addView(gpx, -1, -2)
        addSpace(6)
        addView(action("Комплект для QGIS") { sheet.dismiss(); exportTrip(trip.id, forQgis = true) }, -1, -2)
        if (trip.endedAt == null) {
            addSpace(8)
            addView(action("Завершить выезд") {
                confirm("Завершить выезд?", "Маршрут, посещения и записи будут доступны в истории выездов.") {
                    runCatching { finishTrip(trip.id) }.onSuccess {
                        sheet.dismiss()
                        showTripDetails(trip.id)
                    }.onFailure { message("Не удалось завершить выезд") }
                }
            }.apply { setTextColor(Palette.rust) }, -1, -2)
        }
        if (contents.notes.isNotEmpty()) {
            addSpace(12)
            addView(label("Наблюдения", 16f, Palette.pine, true))
            addSpace(5)
            for (note in contents.notes) {
                addView(action(note.title) { sheet.dismiss(); openRelatedNote(note.id) }, -1, -2)
                addSpace(4)
            }
        }
        if (contents.sites.isNotEmpty()) {
            addSpace(12)
            addView(label("Посещённые объекты", 16f, Palette.pine, true))
            addSpace(5)
            for (site in contents.sites) {
                addView(action(site.title) { sheet.dismiss(); openSite(site.id) }, -1, -2)
                addSpace(4)
            }
        }
    }
    fun update(points: List<TrackPoint>) {
        val km = String.format(Locale.forLanguageTag("ru"), "%.2f", contents.copy(points = points).distanceMeters / 1000)
        val recording = TripRecording.tripId == trip.id
        val route = if (recording) "Запись · $km км" else "Трек · $km км"
        stats.text = "$route · ${points.size} точек\nОбъекты ${contents.sites.size} · Наблюдения ${contents.notes.size}"
        recordingButton?.text = if (recording) "Пауза записи" else if (points.isEmpty()) "Записать трек" else "Продолжить запись"
        gpxButton?.isEnabled = points.isNotEmpty()
    }
    update(contents.points)
    watchTripSheet(dialog) {
        if (dialog.isShowing) update(store.trips.points(trip.id))
    }
}

private fun MainActivity.fieldworkSheet(title: String, populate: LinearLayout.(AlertDialog) -> Unit): AlertDialog {
    val dialog = AlertDialog.Builder(this).create()
    val body = vertical().apply {
        setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(horizontal().apply {
            addView(label(title, 21f, Palette.pine, true).apply {
                if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(action("Закрыть") { dialog.dismiss() })
        }, -1, -2)
        addSpace(10)
        populate(dialog)
    }
    dialog.setView(ScrollView(this).apply { addView(body) }, 0, 0, 0, 0)
    dialog.showFieldSheet()
    return dialog
}
