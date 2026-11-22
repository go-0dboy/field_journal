package ru.fieldjournal

import java.io.Reader

internal fun readCatalogCsv(input: Reader, accept: (Site) -> Unit): Int {
    val reader = java.io.PushbackReader(input.buffered(), 1)
    var header: List<String>? = null
    var count = 0
    val row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    fun nextCell() { row.add(cell.toString()); cell.clear() }
    fun nextRow() {
        nextCell()
        if (row.all(String::isBlank)) { row.clear(); return }
        val keys = header
        if (keys == null) {
            header = row.map { it.trim().removePrefix("\uFEFF") }
            require(header!!.containsAll(listOf("Идентификатор", "Название", "Долгота", "Широта"))) {
                "Нужна региональная таблица каталога с ID, названием и координатами"
            }
        } else {
            require(row.size == keys.size) { "Неверное число столбцов в строке ${count + 2}" }
            val entry = keys.zip(row).toMap()
            fun value(key: String) = entry[key].orEmpty()
            val id = value("Идентификатор").trim()
            val title = value("Название").trim()
            require(id.isNotEmpty() && title.isNotEmpty()) { "Пропущен ID или название в строке ${count + 2}" }
            val lat = value("Широта")
            val lon = value("Долгота")
            val coordinates = if (lat.isBlank() && lon.isBlank()) emptyList() else
                listOf(Coordinate(lat.replace(',', '.').toDouble(), lon.replace(',', '.').toDouble()))
            val links = listOf("Список-источник", "Фото", "Галерея", "Статья или публикация", "Дополнительная ссылка")
                .flatMap { label -> webAddresses(value(label)).map { (_, uri) -> WebLink(uri.toString(), label) } }
                .distinctBy(WebLink::url)
            val status = if (coordinates.isEmpty()) "Не локализован" else "Ориентир по источнику"
            accept(Site(id = id, title = title, vertices = coordinates, description = value("Описание и примечания"),
                period = value("Датировка"), condition = value("Статус и охрана"), confidence = value("Привязка"),
                inventory = value("Номер ЕГРОКН").ifBlank { value("Исходный номер") },
                sources = value("Список-источник"), links = links,
                extraProperties = entry.filterValues(String::isNotBlank),
                research = ResearchCard(mapOf("location_status" to status, "location_evidence" to value("Привязка")))))
            count++
        }
        row.clear()
    }
    while (true) {
        val char = reader.read()
        if (char < 0) break
        when {
            char == '"'.code && quoted -> {
                val next = reader.read()
                if (next == '"'.code) cell.append('"')
                else { quoted = false; if (next >= 0) reader.unread(next) }
            }
            char == '"'.code && (cell.isEmpty() || cell.toString() == "\uFEFF") -> quoted = true
            quoted -> cell.append(char.toChar())
            char == ';'.code -> nextCell()
            char == '\n'.code -> nextRow()
            char != '\r'.code -> cell.append(char.toChar())
        }
        require(cell.length < 2_000_000 && row.size < 200) { "Строка таблицы слишком большая" }
    }
    require(!quoted) { "В таблице отсутствует закрывающая кавычка" }
    if (row.isNotEmpty() || cell.isNotEmpty()) nextRow()
    require(header != null) { "Таблица пуста" }
    return count
}
