package ru.fieldjournal

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest

private const val PART_BYTES = 512L * 1024 * 1024
private const val INDEX_NAME = "field-journal-index.json"

internal class BackupFolder(private val resolver: ContentResolver, private val tree: Uri) {
    private val directory = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun children(): Map<String, Uri> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return requireNotNull(resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)).use { rows ->
            buildMap {
                while (rows.moveToNext()) put(rows.getString(0), DocumentsContract.buildDocumentUriUsingTree(tree, rows.getString(1)))
            }
        }
    }

    fun write(input: InputStream) {
        require(INDEX_NAME !in children()) { "Выберите отдельную папку для новой копии" }
        val created = mutableListOf<Uri>()
        fun create(name: String, mime: String): Uri =
            requireNotNull(DocumentsContract.createDocument(resolver, directory, mime, name)).also(created::add)
        val prefix = "field-journal-${newId().take(8)}"
        val parts = JSONArray()
        val buffer = ByteArray(65536)
        var size = 0L
        try {
            input.buffered().use { stream ->
                var length = stream.read(buffer)
                while (length >= 0) {
                    val name = "$prefix-${parts.length() + 1}.part"
                    val part = create(name, "application/octet-stream")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var written = 0L
                    requireNotNull(resolver.openOutputStream(part, "wt")).use { out ->
                        while (length >= 0) {
                            out.write(buffer, 0, length)
                            digest.update(buffer, 0, length)
                            written += length
                            if (written == PART_BYTES) {
                                length = stream.read(buffer)
                                break
                            }
                            length = stream.read(buffer, 0, minOf(buffer.size.toLong(), PART_BYTES - written).toInt())
                        }
                    }
                    val actualName = children().entries.firstOrNull { it.value == part }?.key ?: name
                    parts.put(JSONObject().put("name", actualName).put("bytes", written).put("sha256", digest.digest().hex()))
                    size += written
                }
            }
            check(parts.length() > 0)
            val index = JSONObject().put("format", "field-journal-parts").put("version", 1)
                .put("bytes", size).put("parts", parts)
            requireNotNull(resolver.openOutputStream(create(INDEX_NAME, "application/json"), "wt")).use {
                it.write(index.toString().toByteArray(Charsets.UTF_8))
            }
        } catch (error: Exception) {
            created.asReversed().forEach { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            throw error
        }
    }

    fun read(): InputStream {
        val files = children()
        val index = requireNotNull(files[INDEX_NAME]) { "В этой папке нет индекса полевой копии" }
        val text = java.io.ByteArrayOutputStream()
        requireNotNull(resolver.openInputStream(index)).use { it.copyLimited(text, 65536) }
        val manifest = JSONObject(text.toString(Charsets.UTF_8.name()))
        require(manifest.getString("format") == "field-journal-parts" && manifest.getInt("version") == 1)
        val parts = manifest.getJSONArray("parts").objects()
        require(parts.size in 1..32 && parts.sumOf { it.getLong("bytes") } == manifest.getLong("bytes"))
        return object : InputStream() {
            private var position = 0
            private var current: InputStream? = null
            private var digest = MessageDigest.getInstance("SHA-256")
            private var count = 0L

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
            }

            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (position < parts.size) {
                    val part = parts[position]
                    val name = part.getString("name")
                    val size = part.getLong("bytes")
                    require(size in 1..PART_BYTES)
                    val source = current ?: requireNotNull(resolver.openInputStream(requireNotNull(files[name]) {
                        "Отсутствует часть $name"
                    })).also { current = it }
                    val length = source.read(buffer, off, len)
                    if (length >= 0) {
                        count += length
                        require(count <= size) { "Неверный размер части $name" }
                        digest.update(buffer, off, length)
                        return length
                    }
                    close()
                    require(count == size && digest.digest().hex() == part.getString("sha256")) { "Повреждена часть $name" }
                    position++
                    count = 0
                    digest = MessageDigest.getInstance("SHA-256")
                }
                return -1
            }

            override fun close() { current?.close(); current = null }
        }
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
