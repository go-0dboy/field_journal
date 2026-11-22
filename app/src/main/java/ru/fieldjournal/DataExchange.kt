package ru.fieldjournal

import org.json.JSONArray
import org.json.JSONObject

internal data class GeoImportRow(val name: String, val type: String, val status: String, val site: Site?, val accepted: Boolean)
internal data class GeoImportPlan(val rows: List<GeoImportRow>)

internal fun recordFields(kind: String, value: JSONObject): Map<String, String> {
    fun canonical(element: Any?): String = when (element) {
        is JSONObject -> element.keys().asSequence().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(element.get(it))
        }
        is JSONArray -> (0 until element.length()).joinToString(",", "[", "]") { canonical(element.get(it)) }
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(element)
        is Number -> java.math.BigDecimal(element.toString()).stripTrailingZeros().toPlainString()
        else -> element.toString()
    }
    val fields = if (kind == "sites") value.getJSONObject("properties") else value
    val result = fields.keys().asSequence().filterNot { it == "updated" || it == "created" }
        .associateWith { canonical(fields.get(it)) }.toMutableMap()
    if (kind == "sites") result["geometry"] = canonical(value.opt("geometry"))
    if (kind == "notes") result["observed_at"] = fields.getLong("created").toString()
    return result.toSortedMap()
}

internal data class ImportConflict(
    val id: String, val kind: String, val recordId: String,
    val original: JSONObject, val incoming: JSONObject, val status: String
) {
    fun json() = JSONObject().put("id", id).put("kind", kind).put("record_id", recordId)
        .put("original", original).put("incoming", incoming).put("status", status)

    fun attachments(): List<Attachment> = listOf(original, incoming).flatMap {
        if (kind == "sites") Site.read(it, true, validate = false).attachments else Note.read(it).attachments
    }

    companion object {
        fun read(json: JSONObject): ImportConflict {
            val kind = json.getString("kind")
            require(kind == "sites" || kind == "notes")
            val id = json.getString("record_id")
            val original = json.getJSONObject("original")
            val incoming = json.getJSONObject("incoming")
            require(original.getString("id") == id && incoming.getString("id") == id)
            return ImportConflict(json.getString("id"), kind, id, original, incoming, json.getString("status"))
        }
    }
}
