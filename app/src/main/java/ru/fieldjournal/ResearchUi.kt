package ru.fieldjournal

import android.app.AlertDialog
import android.content.DialogInterface
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject

private data class ResearchForm(val title: String, val labels: Map<String, String>)

private val forms = mapOf(
    "excerpt" to ResearchForm("Заимствованный фрагмент", linkedMapOf("title" to "Заглавие", "quote" to "Фрагмент",
        "url" to "Редакция источника", "accessed" to "Дата обращения", "license" to "Лицензия")),
    "location_history" to ResearchForm("Предыдущая привязка", linkedMapOf("coordinates" to "Геометрия",
        "status" to "Статус", "evidence" to "Основание", "saved_at" to "Дата редакции")),
    "observation" to ResearchForm("Осмотр участка", linkedMapOf(
        "purpose" to "Цель", "method" to "Метод обследования", "area" to "Обследованная полоса или ID контура",
        "visibility" to "Видимость поверхности", "result" to "Результат", "condition" to "Состояние",
        "threat" to "Угрозы", "limits" to "Ограничения", "observer" to "Исполнитель")),
    "context" to ResearchForm("Контекст раскопа", linkedMapOf(
        "number" to "Номер контекста", "excavation" to "Раскоп", "square" to "Квадрат",
        "observer" to "Исполнитель", "description" to "Описание слоя", "inventory" to "Номер в описи",
        "benchmark" to "Репер", "height_system" to "Система высот", "elevation" to "Отметка или глубина",
        "plan" to "Номер плана или разреза", "photo" to "Номер кадра")),
    "stratigraphy" to ResearchForm("Стратиграфическая связь", linkedMapOf(
        "context" to "Исходный контекст", "relation" to "Отношение", "target" to "Целевой контекст")),
    "complex_membership" to ResearchForm("Историческая принадлежность", linkedMapOf(
        "complex" to "Комплекс или историческая единица", "period" to "Период принадлежности", "source" to "Основание")),
    "find" to ResearchForm("Находка или проба", linkedMapOf(
        "number" to "Номер находки или пробы", "kind" to "Тип", "context" to "Номер контекста", "inventory" to "Номер описи",
        "owner" to "Исполнитель", "position" to "Положение и высота", "photo" to "Номер кадра", "plan" to "Номер плана")),
    "survey" to ResearchForm("Инструментальный замер", linkedMapOf(
        "number" to "Номер вершины или точки", "measured_at" to "Дата и время", "instrument" to "Прибор",
        "observer" to "Исполнитель", "method" to "Метод", "crs" to "Система координат", "height_system" to "Система высот",
        "benchmark" to "Репер", "xyz" to "Координаты X, Y, Z", "accuracy" to "Точность замера")),
    "media" to ResearchForm("Кадр, план или разрез", linkedMapOf(
        "number" to "Номер", "kind" to "Вид материала", "station" to "Место съёмки или номер серии",
        "direction" to "Направление съёмки", "scale" to "Масштаб", "context" to "Осмотр, объект или контекст",
        "observed_at" to "Дата и время", "author" to "Автор", "original" to "Оригинал",
        "public_photo" to "Включить копию в публичную выгрузку")),
    "claim" to ResearchForm("Историческое утверждение", linkedMapOf(
        "claim" to "Утверждение или датировка", "source_id" to "Источник",
        "locator" to "Страница, лист или фрагмент", "quote" to "Выдержка", "author" to "Автор интерпретации",
        "certainty" to "Степень уверенности")),
    "source" to ResearchForm("Источник", linkedMapOf(
        "type" to "Тип источника", "title" to "Заглавие", "author" to "Автор", "archive" to "Архив",
        "fond" to "Фонд", "opis" to "Опись", "file" to "Дело", "leaf" to "Лист и оборот",
        "edition" to "Издание", "page" to "Страница", "url" to "Постоянный адрес",
        "accessed" to "Дата обращения", "version" to "Редакция"))
)

private val researchChoices = mapOf(
    "result" to listOf("Обследовано, признаков не выявлено", "Обследовано, признаки выявлены", "Обследовано частично", "Не обследовано"),
    "relation" to listOf("Перекрывает", "Врезается", "Заполняет"),
    "type" to listOf("Архивный документ", "Публикация", "Карта", "Интернет-ресурс"),
    "public" to listOf("Да", "Нет"),
    "public_photo" to listOf("Да", "Нет"),
    "location_status" to listOf("Не локализован", "Ориентир по источнику", "Проверен в поле")
)

internal fun MainActivity.chooseResearchSite(title: String, allowNone: Boolean, bind: (String?) -> Unit) {
    fieldworkSheet(title) { dialog ->
        val search = searchField("Название или учётный номер", "")
        addView(search, -1, -2)
        if (allowNone) {
            addSpace(8)
            addView(action("Без привязки") { dialog.dismiss(); bind(null) }, -1, -2)
        }
        addSpace(8)
        val rows = ListView(this@chooseResearchSite)
        addView(rows, -1, dp(270))
        var nearby = false
        val ranking = action("Рядом с картой") { }
        addView(ranking, -1, -2)
        fun display(text: String) {
            val choices = if (!nearby) store.siteChoices(text, 60) else {
                val position = centerForPicker()
                store.locatedSites().asSequence().filter { it.title.contains(text, true) || it.inventory.contains(text, true) }
                    .sortedBy { candidate ->
                        val point = candidate.anchor
                        kotlin.math.hypot(point.latitude - position.latitude,
                            longitudeDelta(point.longitude, position.longitude) * kotlin.math.cos(Math.toRadians(position.latitude)))
                    }.take(60).map { SiteChoice(it.id, it.title) }.toList()
            }
            rows.adapter = ArrayAdapter(this@chooseResearchSite, android.R.layout.simple_list_item_1, choices.map(SiteChoice::title))
            rows.setOnItemClickListener { _, _, position, _ -> dialog.dismiss(); bind(choices[position].id) }
        }
        addView(label("До 60 совпадений · уточняйте поиск", 13f, Palette.muted), -1, -2)
        ranking.setOnClickListener {
            nearby = !nearby
            ranking.text = if (nearby) "Сначала недавние" else "Рядом с картой"
            display(search.text.toString())
        }
        search.watch(::display)
        display("")
    }
}

internal fun MainActivity.editStructuredFields(
    title: String, captions: Map<String, String>, current: Map<String, String>,
    selectors: Map<String, ((String) -> Unit) -> Unit> = emptyMap(),
    onSave: (Map<String, String>) -> Unit
) {
    val dialog = AlertDialog.Builder(this).setTitle(title).setNegativeButton("Применить", null)
        .setPositiveButton("Отмена", null).create()
    dialog.setCanceledOnTouchOutside(false)
    val readers = linkedMapOf<String, () -> String>()
    val form = vertical().apply {
        setPadding(dp(18), dp(12), dp(18), dp(16))
        for ((key, caption) in captions) {
            val fieldId = android.view.View.generateViewId()
            val options = researchChoices[key]
            val selector = selectors[key]
            if (options != null && key in setOf("location_status", "public", "public_photo")) {
                var value = current[key].orEmpty()
                val unset = "Не указано"
                val choices = listOf(unset) + options
                addView(valueRow(caption, value.ifBlank { "Выбрать" }) { button ->
                    chooseOption(caption, choices, value.ifBlank { unset }) { selected ->
                        value = selected.takeUnless { it == unset }.orEmpty()
                        button.text = value.ifBlank { "Выбрать" }
                    }
                }.apply { id = fieldId }, -1, -2)
                readers[key] = { value }
            } else if (selector != null) {
                var value = current[key].orEmpty()
                fun display() = when (key) {
                    "source_id" -> sources().firstOrNull { it.id == value }?.let(::entryName)
                    else -> null
                } ?: if (value.isBlank()) "Выбрать" else "Выбрано"
                val clear = action("Убрать привязку") {}
                val separator = android.view.View(this@editStructuredFields)
                fun updateClear() {
                    val visibility = if (value.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
                    clear.visibility = visibility
                    separator.visibility = visibility
                }
                val choice = valueRow(caption, display()) { button ->
                    selector { selected ->
                        value = selected
                        button.text = display()
                        updateClear()
                    }
                }.apply { id = fieldId }
                readers[key] = { value }
                clear.setOnClickListener {
                    value = ""
                    (choice.getChildAt(1) as android.widget.TextView).text = display()
                    updateClear()
                }
                addView(choice, -1, -2)
                addView(separator, 1, dp(8))
                addView(clear, -1, -2)
                updateClear()
            } else {
                addView(label(caption, 14f, Palette.heading, true).apply { labelFor = fieldId }, -1, -2)
                addSpace(4)
                val multiLine = key in setOf("claim", "quote", "description", "methods", "schema", "evidence", "location_evidence", "limits")
                val input = edit(caption, current[key].orEmpty(), if (multiLine) 2 else 1).apply { id = fieldId }
                addView(input, -1, -2)
                readers[key] = { input.text.toString().trim() }
                if (key == "url") webUri(current[key].orEmpty())?.let { url ->
                    addSpace(8)
                    addView(secondaryAction("Открыть источник", FieldIcon.LINK) { openLink(url.toString()) }, -1, -2)
                }
                if (options != null) {
                    addSpace(8)
                    addView(choiceAction("Выбрать из списка") {
                        chooseOption(caption, options, input.text.toString()) { input.setText(it) }
                    }, -1, -2)
                }
            }
            addSpace(14)
        }
    }
    dialog.setView(ScrollView(this).apply { clipToPadding = false; addView(form) })
    dialog.show()
    fun values() = current - captions.keys + readers.mapValues { (_, read) -> read() }.filterValues(String::isNotEmpty)
    fun applyFields() {
        onSave(values())
        dialog.dismiss()
    }
    fun confirmExit() {
        if (values() == current) {
            dialog.dismiss()
            return
        }
        if (!dialog.isShowing) dialog.show()
        fieldPrompt("Применить изменения формы?", PromptAction("Применить", ::applyFields),
            secondary = PromptAction("Не сохранять", dialog::dismiss), destructive = true)
    }
    dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener { applyFields() }
    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { confirmExit() }
    dialog.setOnCancelListener { confirmExit() }
}

private fun entryName(entry: ResearchEntry): String = sequenceOf("title", "claim", "number", "result", "relation", "complex", "purpose", "coordinates")
    .mapNotNull(entry.fields::get).firstOrNull().orEmpty().ifBlank { forms[entry.kind]?.title ?: entry.kind }

private fun MainActivity.editEntry(
    entry: ResearchEntry, attachments: List<Attachment> = emptyList(), saved: (ResearchEntry) -> Unit
) {
    val form = forms[entry.kind] ?: return
    val selectors = mapOf<String, ((String) -> Unit) -> Unit>(
        "source_id" to { pick -> chooseSource(pick) },
        "original" to { pick ->
            fieldworkSheet("Вложения карточки") { dialog ->
                for (file in attachments) card(this, file.name, file.mime) {
                    dialog.dismiss()
                    pick(file.id)
                }
                if (attachments.isEmpty()) addView(label("Сначала добавьте фото или файл в карточку", 14f, Palette.muted))
            }
        }
    )
    editStructuredFields(form.title, form.labels, entry.fields, selectors) { fields ->
        val previous = if (entry.fields.isNotEmpty() && fields != entry.fields)
            entry.previous + entry.fields else entry.previous
        saved(entry.copy(fields = fields, previous = previous))
    }
}

private val siteResearchCaptions = linkedMapOf(
    "aliases" to "Прежние названия", "location_status" to "Статус местоположения",
    "location_evidence" to "Основание привязки", "location_date" to "Дата уточнения",
    "uncertainty" to "Радиус или границы неопределённости", "map_source" to "Лист исторической карты",
    "public" to "Включить в публичную выгрузку", "public_title" to "Публичное название"
)

private fun siteResearchFields(card: ResearchCard, located: Boolean): Map<String, String> {
    val status = if (located) "Ориентир по источнику" else "Не локализован"
    return mapOf("location_status" to status) + card.fields
}

internal fun MainActivity.showSiteRequisites(list: LinearLayout, research: ResearchCard, located: Boolean, onSave: (ResearchCard) -> Unit) {
    val fields = siteResearchFields(research, located)
    val summary = siteResearchCaptions.mapNotNull { (key, name) ->
        fields[key]?.takeIf(String::isNotBlank)?.let { "$name · $it" }
    }
    card(list, "Реквизиты и происхождение", summary.joinToString("\n")) {
        editStructuredFields("Реквизиты", siteResearchCaptions, fields) { changes ->
            onSave(research.copy(fields = (research.fields - siteResearchCaptions.keys) + changes))
        }
    }
}

internal fun MainActivity.addResearchEntry(
    attachments: List<Attachment>, tripProfile: String? = null, onSave: (ResearchEntry) -> Unit
) {
    val kinds = when (tripProfile) {
        "Раскоп" -> listOf("context", "find", "stratigraphy", "survey", "media", "observation")
        "Разведка", "Мониторинг" -> listOf("observation", "media", "survey", "claim", "complex_membership", "context", "find", "stratigraphy")
        else -> forms.keys.filterNot { it in setOf("source", "location_history", "excerpt") }
    }
    AlertDialog.Builder(this).setTitle("Вид записи")
        .setItems(kinds.map { forms.getValue(it).title }.toTypedArray()) { _, index ->
            editEntry(ResearchEntry(kind = kinds[index], fields = emptyMap()), attachments) {
                if (it.fields.isNotEmpty()) onSave(it)
            }
        }.setPositiveButton("Отмена", null).show()
}

internal fun MainActivity.researchRow(
    entry: ResearchEntry, attachments: () -> List<Attachment>, onEdit: (ResearchEntry) -> Unit, onRemove: () -> Unit
) = DiaryRow(this).apply {
    val name = entryName(entry)
    val kind = forms[entry.kind]?.title
    val date = sequenceOf("measured_at", "observed_at", "saved_at").mapNotNull(entry.fields::get)
        .firstOrNull { it.isNotBlank() }
    bind(name, listOfNotNull(kind?.takeUnless { it == name }, date).joinToString(" · "))
    open { showResearchEntry(entry, attachments(), onEdit, onRemove) }
}

private fun MainActivity.showResearchEntry(
    entry: ResearchEntry, attachments: List<Attachment>, onEdit: (ResearchEntry) -> Unit, onRemove: () -> Unit
) {
    fieldworkSheet(entryName(entry)) { detail ->
        val fields = entry.fields
        val source = fields["source_id"]?.let { id -> sources().find { it.id == id } }
        if (source != null) {
            addView(action("Источник · ${entryName(source)}") { showSource(source) }, -1, -2)
            addSpace(8)
        }
        val original = attachments.find { it.id == fields["original"] }
        if (original != null) {
            addView(action("Оригинал · ${original.name}") { openExternalAttachment(original) }, -1, -2)
            addSpace(8)
        }
        fields["url"]?.let { url ->
            addView(secondaryAction("Открыть источник", FieldIcon.LINK) { openLink(url) }, -1, -2)
            addSpace(8)
        }
        val captions = forms[entry.kind]?.labels ?: fields.keys.associateWith { it }
        for ((key, label) in captions) fields[key]?.let {
            if (key != "source_id" && key != "original") addView(label("$label · $it", 14f, Palette.ink))
        }
        if (entry.kind != "location_history" && entry.kind in forms) {
            addSpace(8)
            addView(action("Изменить") {
                detail.dismiss()
                editEntry(entry, attachments, onEdit)
            }, -1, -2)
        }
        if (entry.previous.isNotEmpty()) {
            addSpace(8)
            addView(action("История изменений · ${entry.previous.size}") {
                AlertDialog.Builder(this@showResearchEntry).setTitle("Предыдущие редакции")
                    .setMessage(entry.previous.joinToString("\n\n") { fieldsAtTime ->
                        fieldsAtTime.entries.joinToString("\n") { (key, value) -> "${captions[key] ?: key} · $value" }
                    }).setPositiveButton("Закрыть", null).show()
            }, -1, -2)
        }
        if (entry.kind != "location_history") {
            addSpace(8)
            addView(action("Убрать из карточки") {
                detail.dismiss()
                onRemove()
            }, -1, -2)
        }
    }
}

internal fun MainActivity.editResearch(
    title: String, cardValue: ResearchCard, attachments: List<Attachment> = emptyList(),
    tripProfile: String? = null, isTrip: Boolean = false, onSave: (ResearchCard) -> Unit
) {
    var value = cardValue
    fieldworkSheet(title) {
        val list = vertical()
        addView(list, -1, -2)
        fun update(next: ResearchCard) {
            if (next != value) {
                onSave(next)
                value = next
            }
        }
        fun redraw() {
            list.removeAllViews()
            if (isTrip) {
                list.addView(action("Реквизиты и происхождение") {
                    val captions = linkedMapOf("purpose" to "Задача выезда", "observers" to "Исполнители",
                        "methods" to "Методы и границы работ", "limits" to "Ограничения")
                    editStructuredFields("Реквизиты", captions, value.fields) { fields ->
                        update(value.copy(fields = fields))
                        redraw()
                    }
                }, -1, -2)
                list.addSpace(8)
            }
            for (entry in value.entries) {
                list.addView(researchRow(entry, { attachments }, onEdit = { edited ->
                    update(value.copy(entries = value.entries.map { if (it.id == edited.id) edited else it }))
                    redraw()
                }, onRemove = {
                    update(value.copy(entries = value.entries.filterNot { it.id == entry.id }))
                    redraw()
                }), -1, -2)
                list.addSpace(8)
            }
            list.addView(addAction("Добавить научную запись", FieldIcon.NOTE, primary = false) {
                addResearchEntry(attachments, tripProfile) { saved ->
                    update(value.copy(entries = value.entries + saved))
                    redraw()
                }
            }, -1, -2)
        }
        redraw()
    }
}

private fun MainActivity.sources(): List<ResearchEntry> =
    store.setting("sources")?.array("items")?.objects().orEmpty().map(ResearchEntry::read)

private fun MainActivity.saveSource(value: ResearchEntry) {
    val all = sources().filterNot { it.id == value.id } + value
    store.saveSetting("sources", JSONObject().put("items", JSONArray(all.map(ResearchEntry::json))))
}

private fun MainActivity.showSource(source: ResearchEntry) {
    fieldworkSheet(entryName(source)) { dialog ->
        for ((key, caption) in forms.getValue("source").labels) source.fields[key]?.let {
            addView(label("$caption · $it", 14f, Palette.ink))
        }
        source.fields["url"]?.let { url ->
            addSpace(8)
            addView(secondaryAction("Открыть ресурс", FieldIcon.LINK) { openLink(url) }, -1, -2)
        }
        addSpace(8)
        addView(action("Изменить источник") {
            dialog.dismiss()
            editEntry(source) { saveSource(it); showSource(it) }
        }, -1, -2)
    }
}

internal fun MainActivity.chooseSource(pick: ((String) -> Unit)? = null) {
    fieldworkSheet("Источники проекта") { dialog ->
        addView(action("Новый источник", true) {
            dialog.dismiss()
            editEntry(ResearchEntry(kind = "source", fields = emptyMap())) {
                if (it.fields.isNotEmpty()) {
                    saveSource(it)
                    if (pick == null) showSource(it) else pick(it.id)
                }
            }
        }, -1, -2)
        addSpace(8)
        for (source in sources()) card(this, entryName(source), source.fields["type"].orEmpty()) {
            dialog.dismiss()
            if (pick == null) showSource(source) else pick(source.id)
        }
    }
}
