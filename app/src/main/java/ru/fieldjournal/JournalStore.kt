package ru.fieldjournal

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal class JournalStore(context: Context, private val backupLimit: Long = MAX_ARCHIVE) : SQLiteOpenHelper(context, "field-journal.db", null, 1) {
    private val cache = context.cacheDir
    val attachments = File(context.filesDir, "attachments").apply { mkdirs() }
    private val snapshot = AtomicFile(File(context.filesDir, "journal-autosave.json"))
    private var snapshotTask: ScheduledFuture<*>? = null

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sites (id TEXT PRIMARY KEY, updated INTEGER NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE notes (id TEXT PRIMARY KEY, updated INTEGER NOT NULL, payload TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun rows(table: String) =
        readableDatabase.query(table, arrayOf("payload"), null, null, null, null, "updated DESC").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(JSONObject(cursor.getString(0)))
            }
        }

    fun sites() = rows("sites").map { Site.read(it, withAttachments = true, validate = false) }
    fun notes() = rows("notes").map(Note::read)

    private fun write(db: SQLiteDatabase, table: String, id: String, updated: Long, json: JSONObject, policy: Int) =
        db.insertWithOnConflict(table, null, ContentValues().apply {
            put("id", id)
            put("updated", updated)
            put("payload", json.toString())
        }, policy)

    private fun record(table: String, id: String): JSONObject? =
        readableDatabase.query(table, arrayOf("payload"), "id = ?", arrayOf(id), null, null, null).use {
            if (it.moveToFirst()) JSONObject(it.getString(0)) else null
        }

    fun site(id: String) = record("sites", id)?.let { Site.read(it, withAttachments = true, validate = false) }
    fun note(id: String) = record("notes", id)?.let(Note::read)

    @Synchronized fun save(site: Site, removeAttachments: Set<String> = emptySet()) {
        val previous = site(site.id)?.attachments.orEmpty()
        val files = (site.attachments + previous).filterNot { it.id in removeAttachments }.distinctBy(Attachment::id)
        val saved = site.copy(attachments = files)
        check(write(writableDatabase, "sites", saved.id, saved.updated, saved.feature(), SQLiteDatabase.CONFLICT_REPLACE) != -1L)
        scheduleSnapshot()
        removeUnreferenced(removeAttachments)
    }

    @Synchronized fun save(note: Note, removeAttachments: Set<String> = emptySet()) {
        val previous = note(note.id)?.attachments.orEmpty()
        val files = (note.attachments + previous).filterNot { it.id in removeAttachments }.distinctBy(Attachment::id)
        val saved = note.copy(attachments = files)
        check(write(writableDatabase, "notes", saved.id, saved.updated, saved.json(), SQLiteDatabase.CONFLICT_REPLACE) != -1L)
        scheduleSnapshot()
        removeUnreferenced(removeAttachments)
    }

    @Synchronized fun deleteSite(id: String) {
        val files = site(id)?.attachments.orEmpty()
        writableDatabase.delete("sites", "id = ?", arrayOf(id))
        scheduleSnapshot()
        removeUnreferenced(files.map(Attachment::id).toSet())
    }

    @Synchronized fun deleteNote(id: String) {
        val files = note(id)?.attachments.orEmpty()
        writableDatabase.delete("notes", "id = ?", arrayOf(id))
        scheduleSnapshot()
        removeUnreferenced(files.map(Attachment::id).toSet())
    }

    // Only consider files from committed references. Fresh camera and picker files may still be in flight.
    private fun removeUnreferenced(candidates: Set<String>) {
        if (candidates.isEmpty()) return
        runCatching {
            val referenced = attachmentRecords(sites(), notes()).map(Attachment::id).toSet()
            (candidates - referenced).forEach {
                val file = attachmentFile(it)
                if (file.exists() && !file.delete()) android.util.Log.w("FieldJournal", "Could not remove an unused attachment")
            }
        }.onFailure { android.util.Log.w("FieldJournal", "Could not check attachment references", it) }
    }

    private fun attachmentRecords(sites: List<Site>, notes: List<Note>) =
        (sites.flatMap(Site::attachments) + notes.flatMap(Note::attachments)).distinctBy(Attachment::id)

    fun attachmentFile(id: String): File {
        require(attachmentId.matches(id))
        return File(attachments, id)
    }

    fun addAttachment(input: InputStream, name: String, mime: String): Attachment {
        val record = Attachment(newId(), name.ifBlank { "Вложение" }, mime)
        val file = attachmentFile(record.id)
        try {
            file.outputStream().use { input.copyLimited(it, MAX_ATTACHMENT) }
        } catch (error: Exception) {
            file.delete()
            throw error
        }
        return record
    }

    private fun collection(sites: List<Site>, includeAttachments: Boolean): JSONObject = JSONObject()
        .put("type", "FeatureCollection")
        .put("features", JSONArray(sites.map {
            if (includeAttachments) it.feature() else it.copy(attachments = emptyList()).feature(normalizeWinding = true)
        }))

    private fun manifest(sites: List<Site>, notes: List<Note>) = JSONObject()
        .put("format", "field-journal").put("version", 1)
        .put("sites", collection(sites, true)).put("notes", JSONArray(notes.map(Note::json)))

    private fun scheduleSnapshot() {
        snapshotTask?.cancel(false)
        snapshotTask = snapshotExecutor.schedule(::saveSnapshot, 1, TimeUnit.SECONDS)
    }

    private fun saveSnapshot() {
        runCatching {
            val sitesAndNotes = synchronized(this) { sites() to notes() }
            val bytes = manifest(sitesAndNotes.first, sitesAndNotes.second).toString().toByteArray(Charsets.UTF_8)
            val out = snapshot.startWrite()
            try {
                out.write(bytes)
                snapshot.finishWrite(out)
            } catch (error: Exception) {
                snapshot.failWrite(out)
                throw error
            }
        }.onFailure { android.util.Log.w("FieldJournal", "Could not refresh journal snapshot", it) }
    }

    fun exportGeoJson(out: OutputStream) {
        out.write(collection(sites(), false).toString(2).toByteArray(Charsets.UTF_8))
    }

    fun exportBackup(out: OutputStream) = exportBackup { out }

    fun exportBackup(openOutput: () -> OutputStream) {
        val archive = File.createTempFile("journal-backup-", ".zip", cache)
        try {
            synchronized(this) { writeBackup(archive) }
            openOutput().use { out -> archive.inputStream().use { it.copyTo(out) } }
        } finally {
            archive.delete()
        }
    }

    private fun writeBackup(archive: File) {
        val sites = sites()
        val notes = notes()
        val bytes = manifest(sites, notes).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_JSON) { "Описания записей превышают допустимый размер резервной копии" }
        val files = attachmentRecords(sites, notes).map { record ->
            attachmentFile(record.id).also {
                require(it.isFile && it.length() <= MAX_ATTACHMENT) { "Недоступное или слишком большое вложение: ${record.name}" }
            }
        }
        require(files.sumOf(File::length) <= backupLimit) { "Вложения превышают допустимый объём восстановления ZIP" }
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("journal.json"))
            zip.write(bytes)
            zip.closeEntry()
            for (file in files) {
                zip.putNextEntry(ZipEntry("attachments/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        require(archive.length() <= backupLimit) { "ZIP превышает допустимый объём восстановления" }
    }

    fun importGeoJson(input: InputStream): ImportResult {
        val json = input.readJson()
        return importRecords(readFeatures(json, false), emptyList())
    }

    @Synchronized fun importBackup(input: InputStream): ImportResult {
        val folder = File(cache, "restore-${newId()}").apply { check(mkdirs()) }
        val installed = mutableListOf<File>()
        try {
            val archive = File(folder, "backup.zip")
            archive.outputStream().use { input.copyLimited(it, backupLimit) }
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry("journal.json") ?: error("В архиве отсутствует journal.json")
                val json = zip.getInputStream(entry).use { it.readJson() }
                require(json.optString("format") == "field-journal" && json.optInt("version") == 1) {
                    "Неподдерживаемый формат резервной копии"
                }
                val sites = readFeatures(json.getJSONObject("sites"), true)
                val notes = json.getJSONArray("notes").objects().map(Note::read)
                val files = attachmentRecords(sites, notes)
                var remaining = backupLimit
                for (attachment in files) {
                    val member = zip.getEntry("attachments/${attachment.id}") ?: error("В архиве отсутствует ${attachment.name}")
                    require(!member.isDirectory && member.size in 0..minOf(remaining, MAX_ATTACHMENT)) { "Превышен размер вложений" }
                    val destination = attachmentFile(attachment.id)
                    val temp = File(attachments, "${attachment.id}.pending")
                    try {
                        val size = zip.getInputStream(member).use { stream ->
                            temp.outputStream().use { stream.copyLimited(it, minOf(remaining, MAX_ATTACHMENT)) }
                        }
                        remaining -= size
                        if (destination.exists()) {
                            require(sameContent(temp, destination)) { "Конфликт вложения ${attachment.name}" }
                        } else {
                            check(temp.renameTo(destination)) { "Не удалось сохранить вложение" }
                            installed.add(destination)
                        }
                    } finally {
                        temp.delete()
                    }
                }
                return importRecords(sites, notes).also { removeUnreferenced(installed.map(File::getName).toSet()) }
            }
        } catch (error: Exception) {
            installed.forEach(File::delete)
            throw error
        } finally {
            folder.deleteRecursively()
        }
    }

    @Synchronized private fun importRecords(sites: List<Site>, notes: List<Note>): ImportResult {
        val db = writableDatabase
        var addedSites = 0
        var addedNotes = 0
        db.beginTransaction()
        try {
            for (site in sites) {
                if (write(db, "sites", site.id, site.updated, site.feature(), SQLiteDatabase.CONFLICT_IGNORE) != -1L) addedSites++
            }
            for (note in notes) {
                if (write(db, "notes", note.id, note.updated, note.json(), SQLiteDatabase.CONFLICT_IGNORE) != -1L) addedNotes++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        scheduleSnapshot()
        return ImportResult(addedSites, addedNotes, sites.size + notes.size - addedSites - addedNotes)
    }

    private fun readFeatures(json: JSONObject, withAttachments: Boolean): List<Site> {
        val features = when (json.getString("type")) {
            "FeatureCollection" -> json.getJSONArray("features").objects()
            "Feature" -> listOf(json)
            else -> error("Нужен GeoJSON Feature или FeatureCollection в WGS 84")
        }
        return features.map { Site.read(it, withAttachments) }
    }

    private fun InputStream.readJson(): JSONObject {
        val bytes = java.io.ByteArrayOutputStream()
        copyLimited(bytes, MAX_JSON)
        return JSONObject(bytes.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF"))
    }

    private fun sameContent(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        fun digest(file: File): ByteArray {
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = stream.read(buffer)
                    if (count == -1) break
                    hash.update(buffer, 0, count)
                }
            }
            return hash.digest()
        }
        return digest(a).contentEquals(digest(b))
    }

    companion object {
        const val MAX_ATTACHMENT = 100L * 1024 * 1024
        private const val MAX_JSON = 32L * 1024 * 1024
        private const val MAX_ARCHIVE = 2L * 1024 * 1024 * 1024
        private val snapshotExecutor = Executors.newSingleThreadScheduledExecutor()
        private var instance: JournalStore? = null

        @Synchronized fun get(context: Context) =
            instance ?: JournalStore(context.applicationContext).also { instance = it }
    }
}

internal data class ImportResult(val sites: Int, val notes: Int, val skipped: Int)

internal fun InputStream.copyLimited(out: OutputStream, max: Long): Long {
    val buffer = ByteArray(65536)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count == -1) return total
        total += count
        require(total <= max) { "Превышен допустимый размер файла" }
        out.write(buffer, 0, count)
    }
}
