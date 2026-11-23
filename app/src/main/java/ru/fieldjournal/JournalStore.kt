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
) : SQLiteOpenHelper(context, "field-journal.db", null, 5) {
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
        createAuditTables(db)
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
        if (oldVersion < 5) createAuditTables(db)
    }

    private fun createAuditTables(db: SQLiteDatabase) {
        FieldTrips.upgrade(db)
        db.execSQL("ALTER TABLE sites ADD COLUMN located INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE sites ADD COLUMN search_key TEXT")
        db.execSQL("ALTER TABLE sites ADD COLUMN catalog_type TEXT NOT NULL DEFAULT 'other'")
        db.execSQL("ALTER TABLE sites ADD COLUMN catalog_subtitle TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE sites ADD COLUMN catalog_color INTEGER")
        db.execSQL("ALTER TABLE sites ADD COLUMN location_status TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX sites_catalog_updated ON sites(updated DESC, id)")
        db.rawQuery("SELECT id, payload FROM sites", null).use { cursor ->
            while (cursor.moveToNext()) {
                val json = JSONObject(cursor.getString(1))
                db.update("sites", summaryValues("sites", json), "id = ?", arrayOf(cursor.getString(0)))
            }
        }
        db.execSQL("CREATE TABLE import_conflicts (id TEXT PRIMARY KEY, kind TEXT NOT NULL, record_id TEXT NOT NULL, payload TEXT NOT NULL, status TEXT NOT NULL)")
        db.execSQL("CREATE TABLE journal_settings (name TEXT PRIMARY KEY, payload TEXT NOT NULL)")
    }

    fun setting(name: String): JSONObject? = readableDatabase.query(
        "journal_settings", arrayOf("payload"), "name = ?", arrayOf(name), null, null, null
    ).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }

    fun exchangeProject(): JSONObject = JSONObject()
        .put("details", setting("project") ?: JSONObject())
        .put("sources", setting("sources")?.array("items") ?: JSONArray())
        .put("historical_map", setting("historical_map") ?: JSONObject())

    @Synchronized fun saveSetting(name: String, value: JSONObject) {
        putSetting(writableDatabase, name, value)
    }

    private fun putSetting(db: SQLiteDatabase, name: String, value: JSONObject) {
        db.replaceOrThrow("journal_settings", null, ContentValues().apply {
            put("name", name)
            put("payload", value.toString())
        })
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

    fun locatedSites(): List<Site> = readableDatabase.query("sites", arrayOf("payload"), "located = 1",
        null, null, null, "updated DESC").use { rows ->
        buildList { while (rows.moveToNext()) add(Site.read(JSONObject(rows.getString(0)), true, validate = false)) }
    }

    fun catalogPage(query: String, type: String?, status: String?, limit: Int, withoutCoordinates: Boolean = false): List<CatalogEntry> {
        val parts = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (type != null) { parts += "child.catalog_type = ?"; args += type }
        if (status != null) { parts += "child.location_status = ?"; args += status }
        if (withoutCoordinates) parts += "child.located = 0"
        if (query.isNotBlank()) {
            parts += "(instr(child.search_key, ?) > 0 OR instr(parent.search_key, ?) > 0)"
            repeat(2) { args += query.trim().lowercase(java.util.Locale.ROOT) }
        }
        args += limit.toString()
        val where = parts.joinToString(" AND ", prefix = "WHERE ").takeIf { parts.isNotEmpty() }.orEmpty()
        val sql = "SELECT child.id, child.title, child.catalog_type, child.catalog_subtitle, parent.title, child.catalog_color " +
            "FROM sites child LEFT JOIN sites parent ON child.parent_id = parent.id $where " +
            "ORDER BY child.updated DESC, child.id LIMIT ?"
        return readableDatabase.rawQuery(sql, args.toTypedArray()).use { rows ->
            buildList { while (rows.moveToNext()) add(CatalogEntry(rows.getString(0), rows.getString(1),
                rows.getString(2), rows.getString(3), rows.getString(4), rows.getInt(5).takeUnless { rows.isNull(5) })) }
        }
    }

    fun siteChoices(query: String = "", limit: Int = 80): List<SiteChoice> =
        catalogPage(query, null, null, limit).map { SiteChoice(it.id, it.title) }

    fun notes() = readRows("notes", Note::read)

    fun notesForTrip(id: String): List<Note> = readableDatabase.query(
        "notes", arrayOf("payload"), "trip_id = ?", arrayOf(id), null, null, "created DESC"
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(Note.read(JSONObject(cursor.getString(0)))) }
    }

    fun latestObservation(siteId: String): Note? = readableDatabase.query(
        "notes", arrayOf("payload"), "site_id = ?", arrayOf(siteId), null, null, "created DESC", "1"
    ).use { cursor -> if (cursor.moveToFirst()) Note.read(JSONObject(cursor.getString(0))) else null }

    fun noteSummaries(siteId: String? = null, tripId: String? = null, query: String = ""): List<NoteSummary> {
        val where = listOfNotNull(siteId?.let { "notes.site_id = ?" }, tripId?.let { "notes.trip_id = ?" })
        val sql = "SELECT notes.id, notes.title, notes.created, sites.title, trips.title, notes.payload FROM notes " +
            "LEFT JOIN sites ON notes.site_id = sites.id LEFT JOIN trips ON notes.trip_id = trips.id " +
            where.takeIf { it.isNotEmpty() }?.joinToString(" AND ", prefix = "WHERE ", postfix = " ").orEmpty() +
            "ORDER BY notes.created DESC"
        return readableDatabase.rawQuery(sql, listOfNotNull(siteId, tripId).toTypedArray()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (query.isNotBlank() && listOf(cursor.getString(1), cursor.getString(3), cursor.getString(4),
                            JSONObject(cursor.getString(5)).optString("body")).none { it.orEmpty().contains(query, true) }) continue
                    add(NoteSummary(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getString(3), cursor.getString(4)))
                }
            }
        }
    }

    private fun summaryValues(table: String, json: JSONObject, includeLinks: Boolean = true) = ContentValues().apply {
        if (table == "sites") {
            val properties = json.getJSONObject("properties")
            put("title", properties.getString("title"))
            if (includeLinks) {
                put("parent_id", properties.optString("parent_id").takeIf { it.isNotBlank() })
                run {
                    val located = !json.isNull("geometry")
                    val research = properties.optJSONObject("research")
                    val status = research?.optJSONObject("fields")?.optString("location_status").orEmpty().ifBlank {
                        if (!located) "Не локализован"
                        else if (properties.optString("coordinate_origin") == "device") "Проверен в поле"
                        else "Ориентир по источнику"
                    }
                    put("located", if (located) 1 else 0)
                    put("location_status", status)
                    val type = properties.optString("type", "other")
                    val color = properties.optString("color").removePrefix("#").toIntOrNull(16)?.or(0xff000000.toInt())
                    put("catalog_type", type)
                    put("catalog_color", color)
                    put("catalog_subtitle", listOf(properties.optString("inventory"), properties.optString("period"), status)
                        .filter(String::isNotBlank).joinToString(" · "))
                    put("search_key", listOf("title", "type", "inventory", "description", "region", "district", "locality",
                        "period", "sources", "research", "Регион", "Район", "Населённый пункт").joinToString(" ") { properties.optString(it) }.plus(" " + objectType(type).title).lowercase(java.util.Locale.ROOT))
                }
            }
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
    fun groupCandidates(value: Site) = locatedSites().filter { candidate ->
        candidate.id != value.id && candidate.containsGeometry(value.vertices, value.holes)
    }.sortedBy { areaSquareMeters(it.vertices) }

    fun childrenForNewArea(area: Site): List<Site> {
        if (!area.area) return emptyList()
        val sites = locatedSites()
        val byId = sites.associateBy(Site::id)
        val size = areaSquareMeters(area.vertices)
        return sites.filter { child ->
            if (child.id == area.id || !area.containsGeometry(child.vertices, child.holes)) return@filter false
            val parent = child.parentId?.let(byId::get)
            parent == null || !parent.containsGeometry(child.vertices, child.holes) ||
                areaSquareMeters(parent.vertices) > size
        }
    }

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
                val children = if (draft.isNew) childrenForNewArea(site) else emptyList()
                writeSite(db, site.copy(title = site.title.ifBlank { "Объект без названия" }, updated = timestamp), draft.removed)
                for (child in children) {
                    val linked = child.copy(parentId = site.id, updated = timestamp)
                    check(write(db, "sites", linked.id, linked.updated, linked.feature(),
                        SQLiteDatabase.CONFLICT_REPLACE) != -1L)
                    val childDraft = this.draft(child.id)
                    val edited = childDraft?.site
                    if (edited != null && edited.parentId == child.parentId &&
                        site.containsGeometry(edited.vertices, edited.holes)) {
                        saveDraft(childDraft.copy(site = edited.copy(parentId = site.id)))
                    }
                }
                draft.tripId?.let { trips.visit(db, it, site.id, timestamp) }
            }
            draft.note?.let { note ->
                val tripId = note.tripId?.takeIf { trips.get(it) != null }
                if (tripId != null && note.siteId != null && (draft.isNew || this.note(note.id)?.let {
                    it.tripId != tripId || it.siteId != note.siteId
                } == true)) trips.noteVisit(db, tripId, note.siteId, note.id, note.created)
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
        val previous = site(site.id)
        val files = (site.attachments + previous?.attachments.orEmpty()).filterNot { it.id in removed }.distinctBy(Attachment::id)
        val oldPosition = previous?.takeIf { it.located && (it.vertices != site.vertices || it.holes != site.holes) }
        val history = oldPosition?.let { old ->
            ResearchEntry(kind = "location_history", fields = mapOf(
                "coordinates" to old.feature().getJSONObject("geometry").toString(),
                "status" to old.research.fields["location_status"].orEmpty(),
                "evidence" to old.research.fields["location_evidence"].orEmpty(),
                "saved_at" to dateLabel(old.updated)))
        }
        val saved = site.copy(attachments = files, research = if (history == null) site.research
            else site.research.copy(entries = site.research.entries + history))
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
            val deleted = site(id)
            if (deleted != null) for (note in notes().filter { it.siteId == id }) {
                if (note.tripId != null) trips.noteVisit(db, note.tripId, id, note.id, note.created)
                writeNote(db, note.copy(siteId = null, research = note.research.copy(
                    fields = note.research.fields + ("former_object" to deleted.title) + ("former_object_id" to id)
                )), emptySet())
            }
            for (draft in drafts().filter { it.note?.siteId == id }) {
                saveDraft(draft.copy(note = requireNotNull(draft.note).copy(siteId = null)))
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
            db.delete("trip_visits", "trip_id = ?", arrayOf(id))
            db.delete("trip_editions", "trip_id = ?", arrayOf(id))
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
            val referenced = notes().flatMap(Note::attachments).map(Attachment::id).toSet() + candidates.filter {
                readableDatabase.rawQuery("SELECT 1 FROM sites WHERE instr(payload, ?) > 0 LIMIT 1", arrayOf(it))
                    .use { rows -> rows.moveToFirst() }
            } +
                drafts().flatMap { it.staged + it.attachmentIds } +
                trips.referencedFiles().map(Attachment::id) + conflicts().flatMap { it.attachments().map(Attachment::id) }
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

    @Synchronized internal fun scheduleSnapshot() {
        snapshotTask?.cancel(false)
        snapshotTask = snapshotExecutor.schedule(::saveSnapshot, 1, TimeUnit.SECONDS)
    }

    private fun saveSnapshot() {
        runCatching {
            val bytes = synchronized(this) {
                val db = readableDatabase
                JSONObject().put("saved_at", System.currentTimeMillis())
                    .put("sites", android.database.DatabaseUtils.queryNumEntries(db, "sites"))
                    .put("notes", android.database.DatabaseUtils.queryNumEntries(db, "notes"))
                    .put("drafts", android.database.DatabaseUtils.queryNumEntries(db, "editor_drafts"))
                    .toString().toByteArray(Charsets.UTF_8)
            }
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

    fun exportPublic(decimals: Int, openOutput: () -> OutputStream) {
        val archive = File.createTempFile("journal-public-", ".zip", cache)
        try {
            synchronized(this) {
                archive.outputStream().use { writePublicCatalog(sites(), decimals, it, ::attachmentFile) }
            }
            openOutput().use { output -> archive.inputStream().use { it.copyTo(output) } }
        } finally {
            archive.delete()
        }
    }

    fun exportTripGpx(tripId: String, openOutput: () -> OutputStream) {
        val (contents) = trips.exportContents(tripId)
        require(contents.points.isNotEmpty()) { "Для этого выезда ещё нет трека" }
        openOutput().use { writeTripGpx(contents, it) }
    }

    fun exportTripKit(tripId: String, edition: Int? = null, openOutput: () -> OutputStream) {
        val archive = File.createTempFile("fieldwork-", ".zip", cache)
        try {
            synchronized(this) {
                val (contents, frozen) = trips.exportContents(tripId, edition)
                val project = frozen?.project ?: exchangeProject()
                archive.outputStream().buffered().use { writeTripKit(contents, it, ::attachmentFile, frozen, project) }
            }
            openOutput().use { out -> archive.inputStream().use { it.copyTo(out) } }
        } finally {
            archive.delete()
        }
    }

    fun exportBackup(out: OutputStream) = exportBackup { out }

    data class BackupInfo(val sites: Long, val notes: Long, val drafts: Long, val files: Int, val bytes: Long, val free: Long)

    @Synchronized fun backupInfo(): BackupInfo {
        val siteFiles = readableDatabase.rawQuery("SELECT payload FROM sites WHERE instr(payload, ?) = 0",
            arrayOf("\"attachments\":[]")).use { rows ->
            buildList { while (rows.moveToNext()) addAll(JSONObject(rows.getString(0)).getJSONObject("properties")
                .array("attachments").objects().map(Attachment::read)) }
        }
        val files = (siteFiles + fullAttachments(emptyList(), notes(), drafts(), trips.backups(), conflicts())).distinctBy(Attachment::id)
        fun count(table: String) = android.database.DatabaseUtils.queryNumEntries(readableDatabase, table)
        return BackupInfo(count("sites"), count("notes"), count("editor_drafts"), files.size,
            files.sumOf { attachmentFile(it.id).length() }, cache.usableSpace)
    }

    fun exportBackup(full: Boolean = false, openOutput: () -> OutputStream) {
        val archive = File.createTempFile("journal-backup-", ".zip", cache)
        try {
            synchronized(this) { writeBackup(archive, full) }
            openOutput().use { out -> archive.inputStream().use { it.copyTo(out) } }
        } finally {
            archive.delete()
        }
    }

    fun exportBackupParts(writeParts: (InputStream) -> Unit) {
        val archive = File.createTempFile("journal-parts-", ".zip", cache)
        try {
            synchronized(this) { writeBackup(archive, true, MAX_MULTIPART) }
            archive.inputStream().use(writeParts)
        } finally {
            archive.delete()
        }
    }

    private fun fullAttachments(
        sites: List<Site>, notes: List<Note>, drafts: List<EditorDraft>, tripBackups: List<TripBackup>,
        conflicts: List<ImportConflict>
    ) = (attachmentRecords(sites, notes) + drafts.flatMap { draft ->
        draft.site?.attachments ?: draft.note?.attachments.orEmpty()
    } + tripBackups.flatMap { backup ->
        backup.visits.flatMap { it.sites.flatMap(Site::attachments) } + backup.editions.flatMap { it.contents.attachments() }
    } + conflicts.flatMap(ImportConflict::attachments)).distinctBy(Attachment::id)

    private fun writeBackup(archive: File, full: Boolean, limit: Long = backupLimit) {
        val notes = notes()
        val drafts = if (full) drafts() else emptyList()
        val tripBackups = trips.backups()
        val conflicts = if (full) conflicts() else emptyList()
        val settings = JSONObject()
        readableDatabase.rawQuery("SELECT name, payload FROM journal_settings", null).use { rows ->
            while (rows.moveToNext()) settings.put(rows.getString(0), JSONObject(rows.getString(1)))
        }
        val siteFiles = mutableListOf<Attachment>()
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("sites.ndjson"))
            readableDatabase.query("sites", arrayOf("payload"), null, null, null, null, "updated DESC").use { rows ->
                while (rows.moveToNext()) {
                    val value = rows.getString(0)
                    siteFiles += JSONObject(value).getJSONObject("properties").array("attachments").objects().map(Attachment::read)
                    zip.write(value.toByteArray(Charsets.UTF_8))
                    zip.write(10)
                }
            }
            zip.closeEntry()
            val metadata = JSONObject().put("format", "field-journal").put("version", 2)
                .put("notes", JSONArray(notes.map(Note::json))).put("trips", JSONArray(tripBackups.map(TripBackup::json)))
                .put("drafts", JSONArray(drafts.map(EditorDraft::json))).put("settings", settings)
                .put("conflicts", JSONArray(conflicts.map(ImportConflict::json))).toString().toByteArray(Charsets.UTF_8)
            require(metadata.size <= MAX_JSON) { "Метаданные превышают допустимый размер резервной копии" }
            zip.putNextEntry(ZipEntry("journal.json"))
            zip.write(metadata)
            zip.closeEntry()
            val files = (siteFiles + fullAttachments(emptyList(), notes, drafts, tripBackups, conflicts))
                .distinctBy(Attachment::id).map { record ->
                    attachmentFile(record.id).also {
                        require(it.isFile && it.length() <= MAX_ATTACHMENT) { "Недоступное вложение: ${record.name}" }
                    }
                }
            require(files.sumOf(File::length) <= limit) { "Вложения превышают допустимый объём восстановления ZIP" }
            for (file in files) {
                zip.putNextEntry(ZipEntry("attachments/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        require(archive.length() <= limit) { "ZIP превышает допустимый объём восстановления" }
        ZipFile(archive).use { zip ->
            val buffer = ByteArray(65536)
            zip.entries().asSequence().forEach { entry ->
                val crc = java.util.zip.CRC32()
                val size = zip.getInputStream(entry).use { input ->
                    var count = 0L
                    while (true) {
                        val length = input.read(buffer)
                        if (length < 0) break
                        crc.update(buffer, 0, length)
                        count += length
                    }
                    count
                }
                check(entry.crc == crc.value && entry.size == size) { "Проверка резервного ZIP не пройдена" }
            }
        }
    }

    fun inspectGeoJson(input: InputStream): GeoImportPlan {
        val rows = mutableListOf<GeoImportRow>()
        val ids = hashSetOf<String>()
        readGeoJson(input, geoJsonLimit) { feature ->
            val name = feature.optJSONObject("properties")?.let { it.optString("title").ifBlank { it.optString("name") } }
                .orEmpty().ifBlank { "Объект ${rows.size + 1}" }
            val type = feature.optJSONObject("geometry")?.optString("type").orEmpty().ifBlank { "без геометрии" }
            val result = runCatching { Site.read(feature, withAttachments = false) }
            val site = result.getOrNull()
            val status = when {
                site == null -> result.exceptionOrNull()?.message ?: "Геометрия не поддерживается"
                !ids.add(site.id) -> "Повтор ID в файле"
                this.site(site.id) != null -> "Существующий ID · сравнение редакций"
                else -> "Будет добавлен"
            }
            rows += GeoImportRow(name, type, status, site, site != null && status != "Повтор ID в файле")
        }
        return GeoImportPlan(rows)
    }

    @Synchronized fun applyGeoImport(plan: GeoImportPlan): ImportResult {
        val db = writableDatabase
        var added = 0
        var conflicts = 0
        db.beginTransaction()
        try {
            for (row in plan.rows) {
                val site = row.site?.takeIf { row.accepted } ?: continue
                val json = site.feature()
                if (write(db, "sites", site.id, site.updated, json, SQLiteDatabase.CONFLICT_IGNORE) != -1L) added++
                else if (registerConflict(db, "sites", site.id, json)) conflicts++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (added > 0) scheduleSnapshot()
        return ImportResult(added, 0, plan.rows.size - added, conflicts = conflicts)
    }

    fun conflicts(): List<ImportConflict> = readableDatabase.rawQuery("SELECT payload FROM import_conflicts ORDER BY rowid DESC", null)
        .use { cursor -> buildList { while (cursor.moveToNext()) add(ImportConflict.read(JSONObject(cursor.getString(0)))) } }

    private fun registerConflict(db: SQLiteDatabase, kind: String, id: String, incoming: JSONObject): Boolean {
        val original = record(kind, id) ?: return false
        if (recordFields(kind, original) == recordFields(kind, incoming)) return false
        val fingerprint = java.util.UUID.nameUUIDFromBytes(
            (kind + id + recordFields(kind, original) + recordFields(kind, incoming)).toByteArray(Charsets.UTF_8)
        ).toString()
        return saveConflict(db, ImportConflict(fingerprint, kind, id, original, incoming, "pending"))
    }

    private fun saveConflict(db: SQLiteDatabase, conflict: ImportConflict): Boolean =
        db.insertWithOnConflict("import_conflicts", null, ContentValues().apply {
            put("id", conflict.id); put("kind", conflict.kind); put("record_id", conflict.recordId)
            put("status", conflict.status); put("payload", conflict.json().toString())
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L

    @Synchronized fun resolveConflict(id: String, useIncoming: Boolean) {
        val conflict = conflicts().find { it.id == id && it.status == "pending" } ?: return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = requireNotNull(record(conflict.kind, conflict.recordId)) { "Карточка была удалена" }
            require(recordFields(conflict.kind, current) == recordFields(conflict.kind, conflict.original)) {
                "Локальная карточка изменилась после импорта. Импортируйте её версию повторно для нового сравнения"
            }
            if (useIncoming) {
                val updated = System.currentTimeMillis()
                if (conflict.kind == "sites") {
                    val site = Site.read(conflict.incoming, true)
                    writeSite(db, site.copy(updated = updated), emptySet())
                } else writeNote(db, Note.read(conflict.incoming).copy(updated = updated), emptySet())
            }
            db.delete("import_conflicts", "id = ?", arrayOf(id))
            check(saveConflict(db, conflict.copy(status = if (useIncoming) "incoming" else "local")))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        scheduleSnapshot()
    }

    @Synchronized fun importCatalog(table: InputStream): ImportResult = importCatalogFiles(listOf(table))

    @Synchronized fun expandAttachedCatalog(): ImportResult {
        val files = notes().flatMap(Note::attachments).distinctBy(Attachment::id)
            .filter { it.name.endsWith("— археологические памятники.csv") }
        return importCatalogFiles(files.map { attachmentFile(it.id).inputStream() })
    }

    private fun importCatalogFiles(files: List<InputStream>): ImportResult {
        var added = 0
        var total = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (file in files) {
                total += file.bufferedReader(Charsets.UTF_8).use { reader ->
                    readCatalogCsv(reader) { value ->
                        if (write(db, "sites", value.id, value.updated, value.feature(), SQLiteDatabase.CONFLICT_IGNORE) != -1L) added++
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            files.forEach { runCatching { it.close() } }
        }
        if (added > 0) scheduleSnapshot()
        return ImportResult(added, 0, total - added)
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

    @Synchronized fun importBackup(input: InputStream, multipart: Boolean = false): ImportResult {
        val archiveLimit = if (multipart) MAX_MULTIPART else backupLimit
        val folder = File(cache, "restore-${newId()}").apply { check(mkdirs()) }
        val installed = mutableListOf<File>()
        try {
            val archive = File(folder, "backup.zip")
            archive.outputStream().use { input.copyLimited(it, archiveLimit) }
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry("journal.json") ?: error("В архиве отсутствует journal.json")
                val json = zip.getInputStream(entry).use { it.readJson() }
                require(json.optString("format") == "field-journal" && json.optInt("version") in 1..2) {
                    "Неподдерживаемый формат резервной копии"
                }
                val legacySites = if (json.getInt("version") == 1) readBackupSites(json.getJSONObject("sites")) else null
                val member = if (legacySites == null) requireNotNull(zip.getEntry("sites.ndjson")) {
                    "В архиве отсутствует каталог"
                } else null
                fun readSites(accept: (Site) -> Unit) {
                    if (legacySites != null) legacySites.forEach(accept) else {
                        val entry = requireNotNull(member)
                        require(entry.size in 0..MAX_SITE_LINES) { "Каталог превышает допустимый размер" }
                        var readChars = 0L
                        zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).useLines { lines ->
                            lines.filter(String::isNotBlank).forEach { line ->
                                readChars += line.length + 1
                                require(readChars <= MAX_SITE_LINES) { "Каталог превышает допустимый размер" }
                                accept(Site.read(JSONObject(line), withAttachments = true, validateTopology = false))
                            }
                        }
                    }
                }
                val notes = json.getJSONArray("notes").objects().map(Note::read)
                val tripBackups = json.array("trips").objects().map(TripBackup::read)
                val drafts = json.array("drafts").objects().map(EditorDraft::read)
                val conflicts = json.array("conflicts").objects().map(ImportConflict::read)
                val settings = json.optJSONObject("settings") ?: JSONObject()
                val siteAttachments = linkedMapOf<String, Attachment>()
                readSites { site -> site.attachments.forEach { siteAttachments.putIfAbsent(it.id, it) } }
                val files = (siteAttachments.values + fullAttachments(emptyList(), notes, drafts, tripBackups, conflicts))
                    .distinctBy(Attachment::id)
                var remaining = archiveLimit
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
                return importRecords(::readSites, notes, tripBackups, drafts, settings, conflicts).also { removeUnreferenced(installed.map(File::getName).toSet()) }
            }
        } catch (error: Exception) {
            installed.forEach(File::delete)
            throw error
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun importRecords(
        readSites: ((Site) -> Unit) -> Unit, notes: List<Note>, tripBackups: List<TripBackup>, drafts: List<EditorDraft>,
        settings: JSONObject, conflicts: List<ImportConflict>
    ): ImportResult {
        val db = writableDatabase
        var addedSites = 0
        var countSites = 0
        var addedNotes = 0
        val addedTrips: Int
        var addedDrafts = 0
        var newConflicts = 0
        db.beginTransaction()
        try {
            readSites { site ->
                countSites++
                val feature = site.feature()
                if (write(db, "sites", site.id, site.updated, feature, SQLiteDatabase.CONFLICT_IGNORE) != -1L) addedSites++
                else if (registerConflict(db, "sites", site.id, feature)) newConflicts++
            }
            for (note in notes) {
                if (write(db, "notes", note.id, note.updated, note.json(), SQLiteDatabase.CONFLICT_IGNORE) != -1L) addedNotes++
                else if (registerConflict(db, "notes", note.id, note.json())) newConflicts++
            }
            addedTrips = trips.restore(db, tripBackups)
            for (draft in drafts) if (this.draft(draft.id) == null) {
                saveDraft(draft)
                addedDrafts++
            }
            settings.keys().forEach { key ->
                val incoming = settings.getJSONObject(key)
                val local = setting(key)
                if (local == null) putSetting(db, key, incoming)
                else if (key == "sources") {
                    val merged = (local.array("items").objects() + incoming.array("items").objects()).distinctBy { it.getString("id") }
                    putSetting(db, key, JSONObject().put("items", JSONArray(merged)))
                }
            }
            conflicts.forEach { saveConflict(db, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        scheduleSnapshot()
        return ImportResult(addedSites, addedNotes,
            countSites + notes.size + tripBackups.size - addedSites - addedNotes - addedTrips, addedTrips, addedDrafts, newConflicts)
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
        private const val MAX_SITE_LINES = 256L * 1024 * 1024
        private const val MAX_MULTIPART = 16L * 1024 * 1024 * 1024
        private const val MAX_ARCHIVE = 2L * 1024 * 1024 * 1024
        private val snapshotExecutor = Executors.newSingleThreadScheduledExecutor()
        private var instance: JournalStore? = null

        @Synchronized fun get(context: Context) =
            instance ?: JournalStore(context.applicationContext).also { instance = it }
    }
}

internal data class NoteSummary(val id: String, val title: String, val created: Long, val siteTitle: String?, val tripTitle: String? = null)

internal data class CatalogEntry(val id: String, val title: String, val type: String, val subtitle: String, val parentTitle: String?, val color: Int?)

internal data class SiteChoice(val id: String, val title: String)

internal data class ImportResult(val sites: Int, val notes: Int, val skipped: Int, val trips: Int = 0,
    val drafts: Int = 0, val conflicts: Int = 0)

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
