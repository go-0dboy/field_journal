package ru.fieldjournal

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap

internal data class PublicPlace(
    val name: String,
    val description: String,
    val period: String,
    val inventory: String,
    val links: List<String>,
    val wikidata: String?
)

internal object OpenSources {
    private val cache = object : LinkedHashMap<String, PublicPlace?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PublicPlace?>?) = size > 32
    }
    private val qId = Regex("Q[1-9][0-9]{0,17}")
    private var lastLookup: Long? = null

    @Synchronized fun lookup(point: Coordinate, details: Boolean): PublicPlace? {
        val key = point.display()
        val local = if (cache.containsKey(key)) cache[key] else {
            val elapsed = android.os.SystemClock.elapsedRealtime()
            lastLookup?.let { if (elapsed - it < 1200) Thread.sleep(1200 - (elapsed - it)) }
            lastLookup = android.os.SystemClock.elapsedRealtime()
            val query = "[out:json][timeout:8];" +
                "nwr(around:40,${point.latitude},${point.longitude})[name][!highway][!boundary][!waterway];out bb 30;"
            parse(nearby(query), point).also { cache[key] = it }
        } ?: return null
        if (!details || local.wikidata == null) return local
        val id = local.wikidata
        val entity = runCatching {
            download("https://www.wikidata.org/wiki/Special:EntityData/$id.json")
                .getJSONObject("entities").getJSONObject(id)
        }.getOrNull() ?: return local
        val description = entity.optJSONObject("descriptions")?.optJSONObject("ru")?.value("value").orEmpty()
        return local.copy(description = local.description.ifBlank { description })
    }

    internal fun parse(value: JSONObject, point: Coordinate): PublicPlace? =
        value.array("elements").objects().mapNotNull { candidate ->
            val tags = candidate.optJSONObject("tags") ?: return@mapNotNull null
            val name = tags.value("name:ru").ifBlank { tags.value("name") }
            if (name.isBlank() || listOf("highway", "boundary", "waterway").any(tags::has)) return@mapNotNull null
            val bounds = candidate.optJSONObject("bounds")
            val south = bounds?.optDouble("minlat") ?: Double.NaN
            val north = bounds?.optDouble("maxlat") ?: Double.NaN
            val west = bounds?.optDouble("minlon") ?: Double.NaN
            val east = bounds?.optDouble("maxlon") ?: Double.NaN
            val latitude = candidate.optDouble("lat", (south + north) / 2)
            val longitude = candidate.optDouble("lon", (west + east) / 2)
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return@mapNotNull null
            val radius = FloatArray(1)
            android.location.Location.distanceBetween(point.latitude, point.longitude, latitude, longitude, radius)
            val covers = point.latitude in south..north && point.longitude in west..east
            if (!covers && radius[0] > 35f) return@mapNotNull null
            val kind = candidate.value("type")
            val id = candidate.value("id")
            if (kind !in setOf("node", "way", "relation") || id.toLongOrNull()?.let { it > 0 } != true) {
                return@mapNotNull null
            }
            val score = if (covers) radius[0] * 0.25f else 15f + radius[0]
            score to place(name, tags, kind, id)
        }.minByOrNull { it.first }?.second

    private fun place(name: String, tags: JSONObject, kind: String, id: String): PublicPlace {
        val wikidata = tags.value("wikidata").takeIf(qId::matches)
        val links = buildList {
            add("https://www.openstreetmap.org/$kind/$id")
            wikidata?.let { add("https://www.wikidata.org/wiki/$it") }
            val wikipedia = tags.value("wikipedia")
            if (wikipedia.startsWith("ru:") && wikipedia.length > 3) {
                add("https://ru.wikipedia.org/wiki/" + android.net.Uri.encode(wikipedia.substring(3).replace(' ', '_')))
            }
            tags.value("website").takeIf {
                it.startsWith("https://") && android.net.Uri.parse(it).host?.isNotBlank() == true && it.none(Char::isWhitespace)
            }?.let(::add)
        }
        return PublicPlace(name, tags.value("description:ru").ifBlank { tags.value("description") },
            tags.value("start_date"), tags.value("ref:ru:okrn"), links, wikidata)
    }

    private fun JSONObject.value(key: String) = optString(key).takeUnless { isNull(key) }.orEmpty().trim().take(1000)

    internal fun nearby(query: String, fetch: (String) -> JSONObject = ::download): JSONObject {
        val encoded = android.net.Uri.encode(query)
        fun read(endpoint: String) = fetch("$endpoint?data=$encoded").also {
            if (!it.has("elements") || it.has("remark")) throw java.io.IOException("Источник карты недоступен")
        }
        return try {
            read("https://overpass-api.de/api/interpreter")
        } catch (_: java.io.IOException) {
            read("https://overpass.private.coffee/api/interpreter")
        }
    }

    private fun download(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 6500
            connection.readTimeout = 6500
            connection.setRequestProperty("User-Agent", "FieldJournal/0.2.1 (https://github.com/go-0dboy/field_journal)")
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode == 404) return JSONObject()
            if (connection.responseCode != 200) throw java.io.IOException("HTTP ${connection.responseCode}")
            val output = java.io.ByteArrayOutputStream()
            connection.inputStream.use { it.copyLimited(output, 1_048_576L) }
            JSONObject(output.toString(Charsets.UTF_8.name()))
        } finally {
            connection.disconnect()
        }
    }
}
