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

internal class JournalStore(
    context: Context, private val backupLimit: Long = MAX_ARCHIVE, private val geoJsonLimit: Long = MAX_JSON
) : SQLiteOpenHelper(context, "field-journal.db", null, 4) {
    private val cache = context.cacheDir
    val attachments = File(context.filesDir, "attachments").apply { mkdirs() }
    private val snapshot = AtomicFile(File(context.filesDir, "journal-autosave.json"))
    private var snapshotTask: ScheduledFuture<*>? = null
    val trips = FieldTrips(this)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sites (id TEXT PRIMARY KEY, updated INTEGER NOT NULL, payload TEXT NOT NULL, title TEXT)")
        db.execSQL("CREATE TABLE notes (id TEXT PRIMARY KEY, updated INTEGER NOT NULL, payload TEXT NOT NULL, " +
            "title TEXT, created INTEGER, site_id TEXT)")
        createNoteIndexes(db)
        createFieldworkTables(db)
        createSiteGroups(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE sites ADD COLUMN title TEXT")
            db.execSQL("ALTER TABLE notes ADD COLUMN title TEXT")
            db.execSQL("ALTER TABLE notes ADD COLUMN created INTEGER")
            db.execSQL("ALTER TABLE notes ADD COLUMN site_id TEXT")
            for (table in listOf("sites", "notes")) {
                db.query(table, arrayOf("id", "payload"), null, null, null, null, "rowid").use { cursor ->
                    while (cursor.moveToNext()) {
                        val json = JSONObject(cursor.getString(1))
                        val values = summaryValues(table, json, includeLinks = false)
                        db.update(table, values, "id = ?", arrayOf(cursor.getString(0)))
                    }
                }
            }
            createNoteIndexes(db)
        }
        if (oldVersion < 3) createFieldworkTables(db)
        if (oldVersion < 4) createSiteGroups(db)
    }

    private fun createSiteGroups(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE sites ADD COLUMN parent_id TEXT")
        db.execSQL("CREATE INDEX sites_parent ON sites(parent_id)")
    }

    private fun createFieldworkTables(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE notes ADD COLUMN trip_id TEXT")
        db.execSQL("CREATE INDEX notes_trip_created ON notes(trip_id, created DESC)")
        db.execSQL("CREATE TABLE editor_drafts (id TEXT PRIMARY KEY, updated INTEGER NOT NULL, payload TEXT NOT NULL)")
        FieldTrips.createTables(db)
    }

    private fun createNoteIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX notes_created ON notes(created DESC)")
        db.execSQL("CREATE INDEX notes_site_created ON notes(site_id, created DESC)")
    }

    private fun <T> readRows(table: String, read: (JSONObject) -> T): List<T> =
        readableDatabase.query(table, arrayOf("payload"), null, null, null, null, "updated DESC").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(read(JSONObject(cursor.getString(0))))
            }
        }

    fun sites() = readRows("sites") { Site.read(it, withAttachments = true, validate = false) }
    fun notes() = readRows("notes", Note::read)

    fun notesForTrip(id: String): List<Note> = readableDatabase.query(
        "notes", arrayOf("payload"), "trip_id = ?", arrayOf(id), null, null, "created DESC"
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(Note.read(JSONObject(cursor.getString(0)))) }
    }

    fun latestObservation(siteId: String): Note? = readableDatabase.query(
        "notes", arrayOf("payload"), "site_id = ?", arrayOf(siteId), null, null, "created DESC", "1"
    ).use { cursor -> if (cursor.moveToFirst()) Note.read(JSONObject(cursor.getString(0))) else null }

    fun noteSummaries(siteId: String? = null, tripId: String? = null): List<NoteSummary> {
        val where = listOfNotNull(siteId?.let { "notes.site_id = ?" }, tripId?.let { "notes.trip_id = ?" })
        val sql = "SELECT notes.id, notes.title, notes.created, sites.title, trips.title FROM notes " +
            "LEFT JOIN sites ON notes.site_id = sites.id LEFT JOIN trips ON notes.trip_id = trips.id " +
            where.takeIf { it.isNotEmpty() }?.joinToString(" AND ", prefix = "WHERE ", postfix = " ").orEmpty() +
            "ORDER BY notes.created DESC"
        return readableDatabase.rawQuery(sql, listOfNotNull(siteId, tripId).toTypedArray()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(NoteSummary(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getString(3), cursor.getString(4)))
                }
            }
        }
    }

    private fun summaryValues(table: String, json: JSONObject, includeLinks: Boolean = true) = ContentValues().apply {
        if (table == "sites") {
            val properties = json.getJSONObject("properties")
            put("title", properties.getString("title"))
            if (includeLinks) put("parent_id", properties.optString("parent_id").takeIf { it.isNotBlank() })
        } else {
            put("title", json.getString("title"))
            put("created", json.getLong("created"))
            put("site_id", if (json.isNull("site_id")) null else json.getString("site_id"))
            if (includeLinks) put("trip_id", json.optString("trip_id").takeIf(attachmentId::matches))
        }
    }

    private fun write(db: SQLiteDatabase, table: String, id: String, updated: Long, json: JSONObject, policy: Int) =
        db.insertWithOnConflict(table, null, summaryValues(table, json).apply {
            put("id", id)
            put("updated", updated)
            put("payload", json.toString())
        }, policy)

    private fun record(table: String, id: String): JSONObject? =
        readableDatabase.query(table, arrayOf("payload"), "id = ?", arrayOf(id), null, null, null).use {
            if (it.moveToFirst()) JSONObject(it.getString(0)) else null
        }

    fun site(id: String) = record("sites", id)?.let { Site.read(it, withAttachments = true, validate = false) }
    fun groupCandidates(value: Site) = sites().filter { candidate ->
        candidate.id != value.id && candidate.containsGeometry(value.vertices, value.holes)
    }.sortedBy { areaSquareMeters(it.vertices) }

    fun children(id: String): List<Site> = readableDatabase.query(
        "sites", arrayOf("payload"), "parent_id = ?", arrayOf(id), null, null, "updated DESC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(Site.read(JSONObject(cursor.getString(0)), withAttachments = true, validate = false))
        }
    }

    fun note(id: String) = record("notes", id)?.let(Note::read)

    fun drafts() = readRows("editor_drafts", EditorDraft::read)
    fun draftCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM editor_drafts", null)
        .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
    fun draft(id: String) = record("editor_drafts", id)?.let(EditorDraft::read)

    @Synchronized fun saveDraft(draft: EditorDraft) {
        check(writableDatabase.insertWithOnConflict("editor_drafts", null, ContentValues().apply {
            put("id", draft.id)
            put("updated", System.currentTimeMillis())
            put("payload", draft.json().toString())
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "Не удалось сохранить черновик" }
    }

    @Synchronized fun discardDraft(draft: EditorDraft) {
        writableDatabase.delete("editor_drafts", "id = ?", arrayOf(draft.id))
        val recordExists = if (draft.site != null) site(draft.id) != null else note(draft.id) != null
        val deletedRecordFiles = if (recordExists) emptyList() else draft.attachmentIds
        removeUnreferenced(draft.staged + deletedRecordFiles)
    }

    @Synchronized fun commitDraft(draft: EditorDraft) {
        val timestamp = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            draft.site?.let { site ->
                writeSite(db, site.copy(title = site.title.ifBlank { "Объект без названия" }, updated = timestamp), draft.removed)
                draft.tripId?.let { trips.visit(db, it, site.id, timestamp) }
            }
            draft.note?.let { note ->
                val tripId = note.tripId?.takeIf { trips.get(it) != null }
                writeNote(db, note.copy(title = note.title.ifBlank { "Полевое наблюдение" }, updated = timestamp,
                    tripId = tripId), draft.removed)
            }
            db.delete("editor_drafts", "id = ?", arrayOf(draft.id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        scheduleSnapshot()
        removeUnreferenced(draft.removed + draft.staged)
    }

    @Synchronized fun save(site: Site, removeAttachments: Set<String> = emptySet()) {
        writeSite(writableDatabase, site, removeAttachments)
        scheduleSnapshot()
        removeUnreferenced(removeAttachments)
    }

    private fun writeSite(db: SQLiteDatabase, site: Site, removed: Set<String>) {
        site.parentId?.let { parentId ->
            require(parentId != site.id && this.site(parentId)?.containsGeometry(site.vertices, site.holes) == true) {
                "Объект должен целиком лежать в границах выбранной области"
            }
        }
        require(children(site.id).all { site.containsGeometry(it.vertices, it.holes) }) {
            "Новая граница оставит вложенные объекты снаружи"
        }
        val previous = site(site.id)?.attachments.orEmpty()
        val files = (site.attachments + previous).filterNot { it.id in removed }.distinctBy(Attachment::id)
        val saved = site.copy(attachments = files)
        check(write(db, "sites", saved.id, saved.updated, saved.feature(), SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }

    @Synchronized fun save(note: Note, removeAttachments: Set<String> = emptySet()) {
        writeNote(writableDatabase, note, removeAttachments)
        scheduleSnapshot()
        removeUnreferenced(removeAttachments)
    }

    private fun writeNote(db: SQLiteDatabase, note: Note, removed: Set<String>) {
        val previous = note(note.id)?.attachments.orEmpty()
        val files = (note.attachments + previous).filterNot { it.id in removed }.distinctBy(Attachment::id)
        val saved = note.copy(attachments = files)
        check(write(db, "notes", saved.id, saved.updated, saved.json(), SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }

    @Synchronized fun deleteSite(id: String) {
        val files = site(id)?.attachments.orEmpty()
        val db = writableDatabase
        db.beginTransaction()
        try {
            children(id).forEach { child ->
                val detached = child.copy(parentId = null)
                write(db, "sites", child.id, child.updated, detached.feature(), SQLiteDatabase.CONFLICT_REPLACE)
            }
            drafts().filter { it.site?.parentId == id }.forEach { draft ->
                saveDraft(draft.copy(site = draft.site?.copy(parentId = null)))
            }
            db.delete("sites", "id = ?", arrayOf(id))
            db.delete("trip_sites", "site_id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        scheduleSnapshot()
        removeUnreferenced(files.map(Attachment::id).toSet())
    }

    @Synchronized fun deleteTrip(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (note in notesForTrip(id)) writeNote(db, note.copy(tripId = null), emptySet())
            for (draft in drafts()) {
                if (draft.note?.tripId == id || draft.tripId == id) {
                    saveDraft(draft.copy(note = draft.note?.copy(tripId = draft.note.tripId?.takeUnless { it == id }),
                        tripId = draft.tripId?.takeUnless { it == id }))
                }
            }
            db.delete("track_points", "trip_id = ?", arrayOf(id))
            db.delete("trip_sites", "trip_id = ?", arrayOf(id))
            db.delete("trips", "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        scheduleSnapshot()
    }

    @Synchronized fun deleteNote(id: String) {
        val files = note(id)?.attachments.orEmpty()
        writableDatabase.delete("notes", "id = ?", arrayOf(id))
        scheduleSnapshot()
        removeUnreferenced(files.map(Attachment::id).toSet())
    }

    // Only remove known candidates. Fresh camera and picker files may still be in flight.
    private fun removeUnreferenced(candidates: Set<String>) {
        if (candidates.isEmpty()) return
        runCatching {
            val referenced = attachmentRecords(sites(), notes()).map(Attachment::id).toSet() +
                drafts().flatMap { it.staged + it.attachmentIds }
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

    private fun collection(sites: List<Site>): JSONObject = JSONObject()
        .put("type", "FeatureCollection")
        .put("features", JSONArray(sites.map { it.feature() }))

    private fun manifest(sites: List<Site>, notes: List<Note>) = JSONObject()
        .put("format", "field-journal").put("version", 1)
        .put("sites", collection(sites)).put("notes", JSONArray(notes.map(Note::json)))
        .put("trips", trips.backup())

    @Synchronized internal fun scheduleSnapshot() {
        snapshotTask?.cancel(false)
        snapshotTask = snapshotExecutor.schedule(::saveSnapshot, 1, TimeUnit.SECONDS)
    }

    private fun saveSnapshot() {
        runCatching {
            val data = synchronized(this) { manifest(sites(), notes()) }
            val bytes = data.toString().toByteArray(Charsets.UTF_8)
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

    fun exportGeoJson(out: OutputStream) = exportGeoJson { out }

    fun exportGeoJson(openOutput: () -> OutputStream) {
        val file = File.createTempFile("journal-", ".geojson", cache)
        try {
            file.outputStream().buffered().use { out ->
                var remaining = geoJsonLimit
                fun writePart(part: String) {
                    val bytes = part.toByteArray(Charsets.UTF_8)
                    remaining -= bytes.size
                    require(remaining >= 0) { "Каталог превышает допустимый размер GeoJSON. Используйте меньший набор объектов" }
                    out.write(bytes)
                }
                writePart("{\"type\":\"FeatureCollection\",\"features\":[")
                readableDatabase.query("sites", arrayOf("payload"), null, null, null, null, "updated DESC").use { cursor ->
                    while (cursor.moveToNext()) {
                        val site = Site.read(JSONObject(cursor.getString(0)), withAttachments = false, validate = false)
                        if (site.area) {
                            holeError(listOf(site.vertices) + site.holes)?.let { error ->
                                throw IllegalArgumentException("«${site.title}». $error")
                            }
                        }
                        if (cursor.position > 0) writePart(",")
                        writePart(site.feature(normalizeWinding = true).toString())
                    }
                }
                writePart("]}")
            }
            openOutput().use { out -> file.inputStream().use { it.copyTo(out) } }
        } finally {
            file.delete()
        }
    }

    fun exportTripGpx(tripId: String, openOutput: () -> OutputStream) {
        val contents = trips.contents(tripId)
        require(contents.points.isNotEmpty()) { "Для этого выезда ещё нет трека" }
        openOutput().use { writeTripGpx(contents, it) }
    }

    fun exportTripKit(tripId: String, openOutput: () -> OutputStream) {
        val archive = File.createTempFile("fieldwork-", ".zip", cache)
        try {
            synchronized(this) {
                val contents = trips.contents(tripId)
                archive.outputStream().buffered().use { writeTripKit(contents, it, ::attachmentFile) }
            }
            openOutput().use { out -> archive.inputStream().use { it.copyTo(out) } }
        } finally {
            archive.delete()
        }
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

    @Synchronized fun importGeoJson(input: InputStream): ImportResult {
        val db = writableDatabase
        var added = 0
        var skipped = 0
        db.beginTransaction()
        try {
            readGeoJson(input, geoJsonLimit) { feature ->
                val site = Site.read(feature, withAttachments = false)
                if (write(db, "sites", site.id, site.updated, site.feature(), SQLiteDatabase.CONFLICT_IGNORE) != -1L) added++
                else skipped++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (added > 0) scheduleSnapshot()
        return ImportResult(added, 0, skipped)
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
                val sites = readBackupSites(json.getJSONObject("sites"))
                val notes = json.getJSONArray("notes").objects().map(Note::read)
                val tripBackups = json.array("trips").objects().map(TripBackup::read)
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
                return importRecords(sites, notes, tripBackups).also { removeUnreferenced(installed.map(File::getName).toSet()) }
            }
        } catch (error: Exception) {
            installed.forEach(File::delete)
            throw error
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun importRecords(sites: List<Site>, notes: List<Note>, tripBackups: List<TripBackup>): ImportResult {
        val db = writableDatabase
        var addedSites = 0
        var addedNotes = 0
        val addedTrips: Int
        db.beginTransaction()
        try {
            for (site in sites) {
                if (write(db, "sites", site.id, site.updated, site.feature(), SQLiteDatabase.CONFLICT_IGNORE) != -1L) addedSites++
            }
            for (note in notes) {
                if (write(db, "notes", note.id, note.updated, note.json(), SQLiteDatabase.CONFLICT_IGNORE) != -1L) addedNotes++
            }
            addedTrips = trips.restore(db, tripBackups)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        scheduleSnapshot()
        return ImportResult(addedSites, addedNotes,
            sites.size + notes.size + tripBackups.size - addedSites - addedNotes - addedTrips, addedTrips)
    }

    private fun readBackupSites(json: JSONObject): List<Site> {
        val features = when (json.getString("type")) {
            "FeatureCollection" -> json.getJSONArray("features").objects()
            "Feature" -> listOf(json)
            else -> error("Нужен GeoJSON Feature или FeatureCollection в WGS 84")
        }
        // Version 1 backups can contain internal contours accepted by older releases.
        return features.map { Site.read(it, withAttachments = true, validateTopology = false) }
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

internal data class NoteSummary(val id: String, val title: String, val created: Long, val siteTitle: String?, val tripTitle: String? = null)

internal data class ImportResult(val sites: Int, val notes: Int, val skipped: Int, val trips: Int = 0)

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
