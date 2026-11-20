package ru.fieldjournal

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.DialogInterface
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.TimePicker
import java.time.Instant
import java.time.ZoneId

internal data class EditorDraft(
    val site: Site? = null,
    val note: Note? = null,
    val isNew: Boolean = false,
    val dirty: Boolean = isNew,
    val removed: Set<String> = emptySet(),
    val staged: Set<String> = emptySet(),
    val compact: Boolean = false,
    val tripId: String? = null
) {
    init {
        require((site == null) != (note == null))
    }

    val id get() = site?.id ?: requireNotNull(note).id
    val attachmentIds get() = (site?.attachments ?: note?.attachments.orEmpty()).map(Attachment::id)
    val title get() = (site?.title ?: note?.title).orEmpty().ifBlank {
        if (site != null) "Объект без названия" else "Полевое наблюдение"
    }

    fun json() = org.json.JSONObject().put("site", site?.feature()).put("note", note?.json())
        .put("new", isNew).put("dirty", dirty)
        .put("removed", org.json.JSONArray(removed)).put("staged", org.json.JSONArray(staged))
        .put("compact", compact)
        .apply { tripId?.let { put("trip_id", it) } }

    companion object {
        fun read(value: org.json.JSONObject): EditorDraft {
            fun ids(key: String): Set<String> {
                val array = value.getJSONArray(key)
                return (0 until array.length()).map { array.getString(it) }.toSet()
            }
            return EditorDraft(
                site = value.optJSONObject("site")?.let {
                    Site.read(it, withAttachments = true, validate = false).copy(title = it.getJSONObject("properties").getString("title"))
                },
                note = value.optJSONObject("note")?.let(Note::read),
                isNew = value.getBoolean("new"),
                dirty = value.getBoolean("dirty"),
                removed = ids("removed"),
                staged = ids("staged"),
                compact = value.optBoolean("compact"),
                tripId = value.optString("trip_id").takeIf(attachmentId::matches)
            )
        }
    }
}

@android.annotation.SuppressLint("ViewConstructor")
internal class JournalEditor(
    private val activity: MainActivity,
    private val draft: EditorDraft
) : FrameLayout(activity) {
    private val body = activity.vertical()
    private val scroll = ScrollView(activity).apply { isFillViewport = true; addView(body) }
    private val inputs = mutableMapOf<String, EditText>()
    private val site = draft.site
    private val note = draft.note
    private lateinit var mediaList: LinearLayout
    private val owner get() = draft.id
    val isNew get() = draft.isNew
    var hasUnsavedChanges = draft.dirty
        private set
    private var type = site?.type
    private var linkedSite = note?.siteId
    private var linkedTrip = note?.tripId?.takeIf { activity.store.trips.get(it) != null }
    private var parentId = site?.parentId
    private var customColor = site?.customColor
    private var lookingUpPlace = false
    private var timestamp = note?.created
    private var attachments = site?.attachments ?: note?.attachments.orEmpty()
    private val removedAttachments = draft.removed.toMutableSet()
    private val stagedAttachments = draft.staged.toMutableSet()
    private val uncertain = site?.uncertainFields.orEmpty().toMutableSet()

    init {
        body.setPadding(activity.dp(16), activity.dp(10), activity.dp(16), activity.dp(20))
        body.isFocusableInTouchMode = true
        site?.let(::buildSite)
        note?.let(::buildNote)
        if (site != null && !isNew) addRelatedNotes()
        body.addSpace(15)
        if (!isNew) body.addView(activity.action(if (site != null) "Удалить объект" else "Удалить запись") {
            activity.confirm("Удалить запись?", "Запись будет удалена из локальной базы.") {
                runCatching {
                    if (site != null) activity.store.deleteSite(owner) else activity.store.deleteNote(owner)
                }.onSuccess {
                    activity.closeDeletedEditor()
                    activity.message(if (site != null) "Объект удалён" else "Запись удалена")
                }.onFailure { activity.message("Не удалось удалить запись") }
            }
        }.apply { setTextColor(Palette.rust) }, -1, -2)
        addView(scroll, LayoutParams(-1, -1))
        body.requestFocus()
    }

    private fun section(title: String) {
        body.addSpace(15)
        val heading = activity.label(title, 19f, Palette.pine, true).apply {
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        body.addView(heading)
        body.addSpace(7)
    }

    fun scrollPosition() = scroll.scrollY

    fun restoreScroll(position: Int) {
        if (position > 0) scroll.post { scroll.scrollTo(0, position) }
    }

    private fun field(key: String, label: String, value: String, lines: Int = 1, parent: LinearLayout = body) {
        val titled = activity.label(label, 13f, Palette.muted, true)
        val input = activity.edit(label, value, lines)
        input.id = View.generateViewId()
        titled.labelFor = input.id
        if (site != null && key in verifiableSiteFields) {
            parent.addView(activity.horizontal().apply {
                addView(titled, LinearLayout.LayoutParams(0, -2, 1f))
                addView(CheckBox(activity).apply {
                    text = "Проверить"
                    textSize = 12f
                    contentDescription = "$label · требует проверки"
                    minimumHeight = activity.dp(48)
                    isChecked = key in uncertain
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) uncertain.add(key) else uncertain.remove(key)
                        changed()
                    }
                }, -2, -2)
            }, -1, -2)
        } else parent.addView(titled)
        parent.addSpace(3)
        parent.addView(input, -1, -2)
        parent.addSpace(9)
        inputs[key] = input
        input.watch { changed() }
    }

    private fun buildSite(value: Site) {
        val info = activity.vertical().apply {
            background = activity.panel(Palette.pale, 15)
            setPadding(activity.dp(12), activity.dp(11), activity.dp(12), activity.dp(12))
        }
        val typeRow = activity.horizontal()
        fun typeControl() {
            typeRow.removeAllViews()
            val base = objectType(type.orEmpty())
            val selected = customColor?.let { base.copy(color = it) } ?: base
            typeRow.addView(SymbolView(activity, selected), activity.dp(40), activity.dp(40))
            typeRow.addView(activity.action(selected.title) {
                activity.chooseType("Условный знак объекта") { picked ->
                    type = picked.id
                    typeControl()
                    changed()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = activity.dp(8) })
        }
        typeControl()
        info.addView(typeRow, -1, -2)
        info.addSpace(8)
        val details = buildList {
            add(value.anchor.display() + "  WGS 84")
            value.coordinateOrigin?.let { add(it.title) }
            value.measuredAt?.let { add("Замер · ${dateLabel(it)}") }
            if (value.area) {
                val area = areaSquareMeters(value.vertices) - value.holes.sumOf(::areaSquareMeters)
                add("${value.vertices.size} вершин · ${areaLabel(area.coerceAtLeast(0.0))}")
            }
            value.accuracy?.let { add("Точность ±${it.toInt()} м") }
            value.altitude?.let { add("Высота ${it.toInt()} м (эллипсоид WGS 84)") }
        }
        info.addView(activity.label(details.joinToString("\n"), 13f, Palette.pine).apply { setTextIsSelectable(true) })
        info.addSpace(8)
        val actions = activity.horizontal()
        chip(actions, "На карте") { activity.showOnMap(value) }
        chip(actions, "Изменить") {
            if (value.holes.isEmpty()) activity.editGeometry(value)
            else activity.confirm("Перерисовать границу?", "Внутренние контуры импортированного полигона будут заменены новой границей.") {
                activity.editGeometry(value)
            }
        }
        info.addView(actions, -1, -2)
        info.addSpace(5)
        info.addView(activity.action("Цвет на карте") {
            val colors = listOf("Цвет знака" to null, "Синий" to 0xff356295.toInt(),
                "Бирюзовый" to 0xff23746f.toInt(), "Зелёный" to 0xff48764b.toInt(),
                "Кирпичный" to 0xffa4512f.toInt(), "Фиолетовый" to 0xff765287.toInt(),
                "Охра" to 0xff8a641f.toInt())
            activity.fieldworkSheet("Цвет на карте") { sheet ->
                for ((name, color) in colors) {
                    val symbol = objectType(type.orEmpty()).let { if (color == null) it else it.copy(color = color) }
                    activity.card(this, name, if (customColor == color) "Выбран" else symbol.title,
                        SymbolView(activity, symbol)) {
                        customColor = color
                        typeControl()
                        changed()
                        sheet.dismiss()
                    }
                }
            }
        }, -1, -2)
        body.addView(info, -1, -2)
        section("Описание")
        field("title", "Название", value.title)
        body.addView(activity.action("Название с карты") { findPlace(value, details = false) }, -1, -2)
        body.addSpace(8)
        field("description", "Описание местности и объекта", value.description, 3)
        body.addView(activity.action("Заполнить по открытым источникам") { findPlace(value, details = true) }, -1, -2)
        addGroup(value)
        addMedia()
        section("Датировка и состояние")
        field("period", "Датировка / период", value.period)
        field("culture", "Культура / атрибуция", value.culture)
        field("condition", "Состояние и угрозы сохранности", value.condition, 2)
        section("Учёт и источники")
        field("inventory", "Инвентарный или реестровый номер", value.inventory)
        field("author", "Автор наблюдения / экспедиция", value.author)
        field("confidence", "Достоверность и происхождение координат", value.confidence)
        field("context", "Контекст, слой, глубина, находки", value.context, 2)
        field("sources", "Литература, архивные шифры и ссылки", value.sources, 3)
        body.addView(activity.action("Открыть ссылку из источников") {
            val links = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)
                .findAll(text("sources")).map { it.value.trimEnd('.', ',', ')', ']', ';') }.distinct().toList()
            when (links.size) {
                0 -> activity.message("Добавьте ссылку HTTPS в поле источников")
                1 -> activity.openLink(links.first())
                else -> AlertDialog.Builder(activity).setTitle("Ссылки").setItems(links.toTypedArray()) { _, i ->
                    activity.openLink(links[i])
                }.setPositiveButton("Отмена", null).show()
            }
        }, -1, -2)
        body.addSpace(6)
    }

    private fun addGroup(value: Site) {
        section("Связанные объекты")
        val parent = activity.label("", 14f, Palette.muted)
        fun updateParent() {
            parent.text = parentId?.let(activity.store::site)?.let { "В составе «${it.title}»" } ?: "Самостоятельный объект"
        }
        updateParent()
        body.addView(parent)
        body.addSpace(5)
        body.addView(activity.action("Родительская область") {
            val candidates = activity.store.groupCandidates(value)
            activity.fieldworkSheet("Родительская область") { sheet ->
                parentId?.let(activity.store::site)?.let { group ->
                    addView(activity.action("Открыть «${group.title}»") {
                        sheet.dismiss()
                        activity.openGroupSite(group.id)
                    }, -1, -2)
                    addSpace(6)
                }
                if (candidates.isEmpty()) addView(activity.label("Подходящих областей нет", 14f, Palette.muted))
                for (candidate in candidates) activity.card(this, candidate.title, candidate.symbol.title) {
                    parentId = candidate.id
                    updateParent()
                    changed()
                    sheet.dismiss()
                }
                if (parentId != null) addView(activity.action("Убрать связь") {
                    parentId = null
                    updateParent()
                    changed()
                    sheet.dismiss()
                }, -1, -2)
            }
        }, -1, -2)
        if (!value.area || isNew) return
        body.addSpace(6)
        val parts = activity.horizontal()
        chip(parts, "+ Точка внутри") { activity.createInside(value, area = false) }
        chip(parts, "+ Область внутри") { activity.createInside(value, area = true) }
        body.addView(parts, -1, -2)
        for (child in activity.store.children(value.id)) activity.card(body, child.title, child.symbol.title,
            SymbolView(activity, child.symbol)) { activity.openGroupSite(child.id) }
    }

    private fun findPlace(value: Site, details: Boolean) {
        if (lookingUpPlace) return
        if (MapTiles.get(activity).offline) {
            activity.message("Включите онлайн-режим для поиска по открытым источникам")
            return
        }
        activity.confirm("Искать по координатам?", "Координаты объекта получит публичный сервер данных OpenStreetMap. Текст карточки и фотографии остаются на устройстве.") {
            lookingUpPlace = true
            activity.message("Поиск по открытым источникам…")
            placeReads.execute {
                val result = runCatching { OpenSources.lookup(value.anchor, details) }
                activity.runOnUiThread {
                    lookingUpPlace = false
                    if (!isAttachedToWindow || activity.isFinishing) return@runOnUiThread
                    result.onSuccess { place ->
                        if (place == null) activity.message("Именованный объект рядом с точкой не найден")
                        else offerPlace(place, details)
                    }.onFailure { activity.message("Источники недоступны. Попробуйте позже.") }
                }
            }
        }
    }

    private fun offerPlace(place: PublicPlace, details: Boolean) {
        val suggestions = linkedMapOf("title" to ("Название" to place.name))
        if (details) {
            suggestions["description"] = "Описание" to place.description
            suggestions["period"] = "Датировка" to place.period
            suggestions["inventory"] = "Реестровый номер" to place.inventory
        }
        activity.fieldworkSheet("Данные открытых источников") { sheet ->
            addView(activity.label("Возможное совпадение. Сверьте объект перед заполнением. © OpenStreetMap contributors",
                13f, Palette.muted))
            addSpace(8)
            val selected = linkedMapOf<String, CheckBox>()
            for ((key, suggestion) in suggestions) {
                val (label, proposed) = suggestion
                if (proposed.isBlank()) continue
                val input = text(key)
                val control = CheckBox(activity).apply {
                    text = "$label · $proposed"
                    isChecked = input.isBlank() ||
                        (key == "title" && input == objectType(type.orEmpty()).title)
                    minHeight = activity.dp(48)
                }
                selected[key] = control
                addView(control, -1, -2)
            }
            for (link in place.links) {
                addView(activity.action(android.net.Uri.parse(link).host.orEmpty()) { activity.openLink(link) }, -1, -2)
                addSpace(4)
            }
            addView(activity.action("Применить выбранное", true) {
                val updates = selected.filterValues { it.isChecked }.keys
                if (updates.isEmpty()) {
                    sheet.dismiss()
                    return@action
                }
                for (key in updates) inputs[key]?.setText(suggestions.getValue(key).second)
                inputs["sources"]?.setText((text("sources").lineSequence().filter(String::isNotBlank).toList() +
                    place.links).distinct().joinToString("\n"))
                sheet.dismiss()
                activity.persistEditorDraft()
                activity.message("Данные добавлены в черновик")
            }, -1, -2)
        }
    }

    private fun tripLabel(): String = linkedTrip?.let(activity.store.trips::get)?.let { "Выезд · ${it.title}" }
        ?: "Привязать к выезду"

    private fun chooseTrip(button: TextView) {
        val trips = activity.store.trips.list()
        activity.fieldworkSheet("Выезд записи") { sheet ->
            if (linkedTrip != null) addView(activity.action("Без выезда") {
                linkedTrip = null
                button.text = tripLabel()
                changed()
                sheet.dismiss()
            }, -1, -2)
            if (trips.isEmpty()) addView(activity.label("Создайте выезд на вкладке «Выезды»", 14f, Palette.muted))
            for (trip in trips) activity.card(this, trip.title, dateLabel(trip.startedAt)) {
                linkedTrip = trip.id
                button.text = tripLabel()
                changed()
                sheet.dismiss()
            }
        }
    }

    private fun noteDetails(value: Note, parent: LinearLayout) {
        parent.addView(activity.vertical().apply {
            background = activity.panel(Palette.pale, 15)
            setPadding(activity.dp(12), activity.dp(12), activity.dp(12), activity.dp(12))
            addView(activity.action(dateLabel(value.created)) { }.apply {
                setOnClickListener { pickDate(this) }
            }, -1, -2)
            addSpace(6)
            addView(activity.action(linkTitle()) { }.apply {
                setOnClickListener { chooseSite(this) }
            }, -1, -2)
        }, -1, -2)
        parent.addSpace(8)
        field("title", "Заголовок", value.title, parent = parent)
    }

    private fun buildNote(value: Note) {
        body.addView(activity.action(tripLabel()) { }.apply {
            setOnClickListener { chooseTrip(this) }
        }, -1, -2)
        body.addSpace(6)
        if (draft.compact) {
            val context = listOfNotNull(
                value.siteId?.let(activity.store::site)?.title,
                dateLabel(value.created)
            ).joinToString(" · ")
            body.addView(activity.label(context, 14f, Palette.pine, true))
            body.addSpace(8)
            field("body", "Короткая заметка", value.body, 3)
            addMedia()
            body.addSpace(12)
            val details = activity.vertical().apply { visibility = View.GONE }
            body.addView(activity.action("Дополнительные поля") {
                details.visibility = if (details.visibility == View.GONE) View.VISIBLE else View.GONE
            }, -1, -2)
            body.addSpace(8)
            noteDetails(value, details)
            field("author", "Автор / группа", value.author, parent = details)
            field("weather", "Погода и условия работ", value.weather, parent = details)
            body.addView(details, -1, -2)
        } else {
            noteDetails(value, body)
            field("body", "Маршрут, работы и наблюдения", value.body, 7)
            field("author", "Автор / группа", value.author)
            field("weather", "Погода и условия работ", value.weather)
            addMedia()
        }
    }

    private fun pickDate(button: TextView) {
        val zone = ZoneId.systemDefault()
        val initial = Instant.ofEpochMilli(requireNotNull(timestamp)).atZone(zone)
        val date = DatePickerDialog(activity, { _, year, month, day ->
            val time = TimePicker(activity).apply {
                setIs24HourView(true)
                hour = initial.hour
                minute = initial.minute
            }
            val dialog = AlertDialog.Builder(activity).setTitle("Время наблюдения").setView(time)
                .setNegativeButton("Выбрать", null).setPositiveButton("Отмена", null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                    if (time.validateInput()) {
                        time.clearFocus()
                        timestamp = java.time.LocalDateTime.of(year, month + 1, day, time.hour, time.minute)
                            .atZone(zone).toInstant().toEpochMilli()
                        button.text = dateLabel(requireNotNull(timestamp))
                        changed()
                        dialog.dismiss()
                    }
                }
            }
            dialog.show()
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth)
        date.setButton(DialogInterface.BUTTON_NEGATIVE, "Выбрать", DialogInterface.OnClickListener { dialog, _ ->
            date.onClick(dialog, DialogInterface.BUTTON_POSITIVE)
        })
        date.setButton(DialogInterface.BUTTON_POSITIVE, "Отмена", null as DialogInterface.OnClickListener?)
        date.show()
    }

    private fun linkTitle(): String {
        val id = linkedSite ?: return "Привязать к объекту"
        val name = activity.store.site(id)?.title ?: "Объект удалён"
        return "Объект · $name"
    }

    private fun chooseSite(button: TextView) {
        val sites = activity.store.sites()
        val names = arrayOf("Без привязки") + sites.map(Site::title).toTypedArray()
        AlertDialog.Builder(activity).setTitle("Объект дневника").setItems(names) { _, index ->
            linkedSite = sites.getOrNull(index - 1)?.id
            button.text = linkTitle()
            changed()
        }.setPositiveButton("Отмена", null).show()
    }

    private fun chip(row: LinearLayout, title: String, click: () -> Unit) {
        row.addView(activity.action(title, click = click).apply {
            textSize = 12f
            setPadding(activity.dp(3), 0, activity.dp(3), 0)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply {
            setMargins(activity.dp(2), 0, activity.dp(2), 0)
        })
    }

    private fun addMedia() {
        section("Фотографии и материалы")
        val row = activity.horizontal()
        chip(row, "Снять") { activity.capturePhoto(owner, note != null) }
        chip(row, "Фото") { activity.pickAttachment(owner, note != null, true) }
        chip(row, "Документ") { activity.pickAttachment(owner, note != null, false) }
        body.addView(row, -1, -2)
        body.addSpace(7)
        mediaList = activity.vertical()
        body.addView(mediaList, -1, -2)
        showMedia()
    }

    private fun showMedia() {
        mediaList.removeAllViews()
        if (attachments.isEmpty()) {
            mediaList.addView(activity.label("Вложений пока нет", 13f, Palette.muted))
        }
        val photos = attachments.filter { it.mime.startsWith("image/") }
        for (attachment in attachments) {
            val row = activity.horizontal()
            val photoIndex = photos.indexOf(attachment)
            val open = {
                if (photoIndex >= 0) activity.openPhotoGallery(photos, photoIndex)
                else activity.openAttachment(attachment)
            }
            if (photoIndex >= 0) {
                row.addView(activity.photoThumbnail(attachment, open), LinearLayout.LayoutParams(activity.dp(58), activity.dp(58))
                    .apply { marginEnd = activity.dp(7) })
            }
            row.addView(activity.action(attachment.name, click = open), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(activity.action("×", description = "Убрать ${attachment.name}") {
                activity.confirm("Убрать вложение?", attachment.name) {
                    removedAttachments.add(attachment.id)
                    attachments = attachments.filterNot { it.id == attachment.id }
                    showMedia()
                    changed()
                    activity.message("Вложение убрано из черновика")
                }
            }, activity.dp(48), -2)
            mediaList.addView(row, -1, -2)
            mediaList.addSpace(5)
        }
    }

    private fun addRelatedNotes() {
        section("Полевой дневник")
        for (entry in activity.store.noteSummaries(owner)) {
            body.addView(activity.action("${entry.title} · ${dateLabel(entry.created)}") { activity.openRelatedNote(entry.id) }, -1, -2)
            body.addSpace(4)
        }
        body.addView(activity.action("+ Запись об объекте", true) { activity.createNote(owner) }, -1, -2)
    }

    fun stageAttachment(record: Attachment) {
        attachments = attachments + record
        stagedAttachments.add(record.id)
        showMedia()
        changed()
        activity.persistEditorDraft()
    }

    private fun changed() {
        hasUnsavedChanges = true
        activity.scheduleDraftSave()
    }

    private fun text(key: String) = inputs[key]?.text?.toString().orEmpty()

    fun snapshot() = EditorDraft(
        site = site?.let {
            it.copy(title = text("title"), type = type ?: it.type, description = text("description"),
                period = text("period"), culture = text("culture"), condition = text("condition"),
                inventory = text("inventory"), author = text("author"), confidence = text("confidence"),
                context = text("context"), sources = text("sources"), attachments = attachments,
                uncertainFields = uncertain.toSet(), parentId = parentId, customColor = customColor)
        },
        note = note?.let {
            it.copy(title = text("title"), body = text("body"), author = text("author"), weather = text("weather"),
                siteId = linkedSite, tripId = linkedTrip, created = timestamp ?: it.created, attachments = attachments)
        },
        isNew = isNew, dirty = hasUnsavedChanges, removed = removedAttachments.toSet(), staged = stagedAttachments.toSet(),
        compact = draft.compact, tripId = draft.tripId
    )

    fun saveConfirmed(): Boolean = runCatching { activity.store.commitDraft(snapshot()) }.onSuccess {
        stagedAttachments.clear()
        removedAttachments.clear()
        hasUnsavedChanges = false
    }.onFailure {
        android.util.Log.w("FieldJournal", "Could not save edited record", it)
        activity.message(if (it is IllegalArgumentException) it.message ?: "Проверьте поля карточки"
            else "Не удалось сохранить. Правки остались в карточке")
    }.isSuccess

    fun discardDraft(): Boolean = runCatching { activity.store.discardDraft(snapshot()) }.onSuccess {
        stagedAttachments.clear()
        hasUnsavedChanges = false
    }.onFailure { activity.message("Не удалось удалить черновик") }.isSuccess

}

private val placeReads = java.util.concurrent.Executors.newSingleThreadExecutor()

internal fun EditText.watch(onChange: (String) -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            onChange(s?.toString().orEmpty())
        }
        override fun afterTextChanged(s: Editable?) = Unit
    })
}
