package ru.fieldjournal

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.DialogInterface
import android.text.Editable
import android.text.TextWatcher
import android.view.View
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
    val staged: Set<String> = emptySet()
) {
    init {
        require((site == null) != (note == null))
    }

    val id get() = site?.id ?: requireNotNull(note).id

    fun json() = org.json.JSONObject().put("site", site?.feature()).put("note", note?.json())
        .put("new", isNew).put("dirty", dirty)
        .put("removed", org.json.JSONArray(removed)).put("staged", org.json.JSONArray(staged))

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
                staged = ids("staged")
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
    private var timestamp = note?.created
    private var attachments = site?.attachments ?: note?.attachments.orEmpty()
    private val removedAttachments = draft.removed.toMutableSet()
    private val stagedAttachments = draft.staged.toMutableSet()

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

    private fun field(key: String, label: String, value: String, lines: Int = 1) {
        val titled = activity.label(label, 13f, Palette.muted, true)
        val input = activity.edit(label, value, lines)
        input.id = View.generateViewId()
        titled.labelFor = input.id
        body.addView(titled)
        body.addSpace(3)
        body.addView(input, -1, -2)
        body.addSpace(9)
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
            val selected = objectType(type.orEmpty())
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
        body.addView(info, -1, -2)
        section("Описание")
        field("title", "Название", value.title)
        field("description", "Описание местности и объекта", value.description, 3)
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

    private fun buildNote(value: Note) {
        val row = activity.vertical().apply {
            background = activity.panel(Palette.pale, 15)
            setPadding(activity.dp(12), activity.dp(12), activity.dp(12), activity.dp(12))
        }
        val time = activity.action(dateLabel(value.created)) { }.apply {
            setOnClickListener { pickDate(this) }
        }
        val link = activity.action(linkTitle()) { }.apply {
            setOnClickListener { chooseSite(this) }
        }
        row.addView(time, -1, -2)
        row.addSpace(6)
        row.addView(link, -1, -2)
        body.addView(row, -1, -2)
        section("Наблюдение")
        field("title", "Заголовок", value.title)
        field("body", "Маршрут, работы и наблюдения", value.body, 7)
        field("author", "Автор / группа", value.author)
        field("weather", "Погода и условия работ", value.weather)
        addMedia()
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
        for (attachment in attachments) {
            val row = activity.horizontal()
            row.addView(activity.action(attachment.name) { activity.openAttachment(attachment) }, LinearLayout.LayoutParams(0, -2, 1f))
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
        for (entry in activity.store.notes().filter { it.siteId == owner }.sortedByDescending(Note::created)) {
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
    }

    private fun changed() {
        hasUnsavedChanges = true
    }

    private fun text(key: String) = inputs[key]?.text?.toString().orEmpty()

    fun snapshot() = EditorDraft(
        site = site?.let {
            it.copy(title = text("title"), type = type ?: it.type, description = text("description"),
                period = text("period"), culture = text("culture"), condition = text("condition"),
                inventory = text("inventory"), author = text("author"), confidence = text("confidence"),
                context = text("context"), sources = text("sources"), attachments = attachments)
        },
        note = note?.let {
            it.copy(title = text("title"), body = text("body"), author = text("author"), weather = text("weather"),
                siteId = linkedSite, created = timestamp ?: it.created, attachments = attachments)
        },
        isNew = isNew, dirty = hasUnsavedChanges, removed = removedAttachments.toSet(), staged = stagedAttachments.toSet()
    )

    fun saveConfirmed(): Boolean {
        val state = snapshot()
        val now = System.currentTimeMillis()
        return runCatching {
            state.site?.let {
                activity.store.save(it.copy(title = it.title.ifBlank { "Объект без названия" }, updated = now), state.removed)
            }
            state.note?.let {
                activity.store.save(it.copy(title = it.title.ifBlank { "Полевое наблюдение" }, updated = now), state.removed)
            }
        }.onSuccess {
            val kept = attachments.map(Attachment::id).toSet()
            (stagedAttachments - kept).forEach { activity.store.attachmentFile(it).delete() }
            stagedAttachments.clear()
            removedAttachments.clear()
            hasUnsavedChanges = false
        }.onFailure {
            android.util.Log.w("FieldJournal", "Could not save edited record", it)
            activity.message("Не удалось сохранить. Правки остались в карточке")
        }.isSuccess
    }

    fun discardDraft() {
        stagedAttachments.forEach { activity.store.attachmentFile(it).delete() }
        stagedAttachments.clear()
    }

}

internal fun EditText.watch(onChange: (String) -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            onChange(s?.toString().orEmpty())
        }
        override fun afterTextChanged(s: Editable?) = Unit
    })
}
