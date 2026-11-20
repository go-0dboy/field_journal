package ru.fieldjournal

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PushbackReader

internal fun readGeoJson(input: InputStream, limit: Long, readFeature: (JSONObject) -> Unit) {
    val limited = object : FilterInputStream(input) {
        private var remaining = limit

        override fun read(): Int = super.read().also {
            if (it != -1) consume(1)
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            super.read(bytes, offset, minOf(length.toLong(), remaining + 1).toInt()).also {
                if (it > 0) consume(it.toLong())
            }

        private fun consume(count: Long) {
            remaining -= count
            require(remaining >= 0) { "Превышен допустимый размер GeoJSON" }
        }
    }
    val source = PushbackReader(limited.reader(Charsets.UTF_8))
    val first = source.read()
    if (first != -1 && first != '\uFEFF'.code) source.unread(first)

    val reader = JsonReader(source)
    val root = JSONObject()
    var hasFeatures = false
    reader.beginObject()
    while (reader.hasNext()) {
        when (val name = reader.nextName()) {
            "features" -> {
                require(!hasFeatures) { "В GeoJSON повторяется массив features" }
                hasFeatures = true
                reader.beginArray()
                while (reader.hasNext()) readFeature(reader.readObject(1))
                reader.endArray()
            }
            else -> root.put(name, reader.readValue(1))
        }
    }
    reader.endObject()
    require(reader.peek() == JsonToken.END_DOCUMENT) { "После GeoJSON есть лишние данные" }
    when (root.optString("type")) {
        "FeatureCollection" -> require(hasFeatures) { "В GeoJSON нет массива features" }
        "Feature" -> {
            require(!hasFeatures) { "Массив features допустим в FeatureCollection" }
            readFeature(root)
        }
        else -> error("Нужен GeoJSON Feature или FeatureCollection в WGS 84")
    }
}

private fun JsonReader.readObject(depth: Int): JSONObject = JSONObject().also { json ->
    beginObject()
    while (hasNext()) json.put(nextName(), readValue(depth + 1))
    endObject()
}

private fun JsonReader.readValue(depth: Int): Any {
    require(depth <= 64) { "Слишком глубокая вложенность GeoJSON" }
    return when (peek()) {
        JsonToken.BEGIN_OBJECT -> readObject(depth)
        JsonToken.BEGIN_ARRAY -> JSONArray().also { array ->
            beginArray()
            while (hasNext()) array.put(readValue(depth + 1))
            endArray()
        }
        JsonToken.NULL -> {
            nextNull()
            JSONObject.NULL
        }
        JsonToken.BOOLEAN -> nextBoolean()
        JsonToken.STRING -> nextString()
        JsonToken.NUMBER -> nextString().let { it.toLongOrNull() ?: it.toDouble() }
        else -> error("Некорректное значение GeoJSON")
    }
}
