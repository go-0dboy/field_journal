package ru.fieldjournal

import org.json.JSONArray
import org.json.JSONObject

internal data class ResearchCard(
    val fields: Map<String, String> = emptyMap(),
    val entries: List<ResearchEntry> = emptyList()
) {
    fun json() = JSONObject().put("fields", JSONObject(fields)).put("entries", JSONArray(entries.map(ResearchEntry::json)))

    companion object {
        fun read(json: JSONObject?): ResearchCard {
            val fields = json?.optJSONObject("fields") ?: JSONObject()
            return ResearchCard(fields.keys().asSequence().associateWith(fields::getString),
                json?.array("entries")?.objects().orEmpty().map(ResearchEntry::read))
        }
    }
}

internal data class ResearchEntry(
    val id: String = newId(),
    val kind: String,
    val fields: Map<String, String>,
    val previous: List<Map<String, String>> = emptyList()
) {
    fun json() = JSONObject().put("id", id).put("kind", kind).put("fields", JSONObject(fields))
        .put("previous", JSONArray(previous.map { JSONObject(it) }))

    companion object {
        fun read(json: JSONObject): ResearchEntry {
            fun fields(objectValue: JSONObject) = objectValue.keys().asSequence().associateWith(objectValue::getString)
            return ResearchEntry(json.getString("id"), json.getString("kind"), fields(json.getJSONObject("fields")),
                json.array("previous").objects().map(::fields))
        }
    }
}
