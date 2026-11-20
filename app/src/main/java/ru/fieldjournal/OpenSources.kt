package ru.fieldjournal

import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal data class SourceArticle(
    val title: String,
    val pageId: Long,
    val excerpt: String,
    val wikidataId: String?
) {
    val url get() = "https://ru.wikipedia.org/?curid=$pageId"
    val wikidataUrl get() = wikidataId?.let { "https://www.wikidata.org/wiki/$it" }
}

internal data class SourceFacts(val year: String = "", val registry: String = "")

internal object OpenSources {
    const val ARTICLE_LICENSE = "https://creativecommons.org/licenses/by-sa/4.0/"
    private val qId = Regex("Q[1-9][0-9]{0,17}")
    private val wikidataYear = Regex("^([+-])([0-9]{1,10})-")

    internal fun search(name: String, fetch: (String) -> JSONObject = ::download): List<SourceArticle> {
        val query = name.trim().take(160)
        if (query.isEmpty()) return emptyList()
        val url = "https://ru.wikipedia.org/w/api.php?action=query&generator=search" +
            "&gsrsearch=${Uri.encode(query)}&gsrnamespace=0&gsrlimit=6" +
            "&prop=extracts%7Cpageprops&exchars=480&exintro=1&explaintext=1&exlimit=6" +
            "&ppprop=wikibase_item%7Cdisambiguation&formatversion=2&format=json"
        val result = fetch(url)
        if (result.has("error")) throw IOException("Ошибка поиска статьи")
        return result.optJSONObject("query")?.array("pages").orEmptyArticles()
    }

    private fun org.json.JSONArray?.orEmptyArticles(): List<SourceArticle> =
        this?.objects().orEmpty().sortedBy { it.optInt("index", Int.MAX_VALUE) }.mapNotNull { page ->
            val title = page.value("title")
            val id = page.optLong("pageid")
            val properties = page.optJSONObject("pageprops")
            if (id <= 0 || title.isBlank() || properties?.has("disambiguation") == true) return@mapNotNull null
            val wikidata = properties?.value("wikibase_item")?.takeIf(qId::matches)
            SourceArticle(title, id, page.value("extract").take(600), wikidata)
        }

    internal fun facts(id: String?, fetch: (String) -> JSONObject = ::download): SourceFacts {
        if (id == null || !qId.matches(id)) return SourceFacts()
        val url = "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=$id&props=claims&format=json"
        val claims = fetch(url).optJSONObject("entities")?.optJSONObject(id)?.optJSONObject("claims")
            ?: return SourceFacts()
        fun values(key: String): List<Any> {
            val statements = claims.array(key).objects().filterNot { it.value("rank") == "deprecated" }
            val selected = statements.filter { it.value("rank") == "preferred" }.ifEmpty { statements }
            return selected.mapNotNull {
                it.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.opt("value")
            }
        }
        val year = values("P571").mapNotNull { datum ->
            val date = datum as? JSONObject ?: return@mapNotNull null
            if (date.optInt("precision") < 9) return@mapNotNull null
            val match = wikidataYear.find(date.value("time")) ?: return@mapNotNull null
            val year = match.groupValues[2].toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            if (match.groupValues[1] == "-") "$year до н. э." else year.toString()
        }.distinct().singleOrNull().orEmpty()
        val registry = values("P5381").filterIsInstance<String>().filter { it.matches(Regex("[0-9]{6,24}")) }
            .distinct().singleOrNull().orEmpty()
        return SourceFacts(year, registry)
    }

    private fun JSONObject.value(key: String) = optString(key).takeUnless { isNull(key) }.orEmpty().trim()

    private fun download(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 6500
            connection.readTimeout = 6500
            connection.setRequestProperty("User-Agent", "FieldJournal/0.2.2 (https://github.com/go-0dboy/field_journal)")
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { it.copyLimited(output, 1_048_576L) }
            JSONObject(output.toString(Charsets.UTF_8.name()))
        } finally {
            connection.disconnect()
        }
    }
}
