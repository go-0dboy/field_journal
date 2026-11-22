package ru.fieldjournal

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal data class FieldTrip(val id: String, val title: String, val startedAt: Long, val endedAt: Long? = null, val profile: String = "Разведка",
    val research: ResearchCard = ResearchCard()) {
    init {
        require(attachmentId.matches(id) && startedAt > 0 && (endedAt == null || endedAt >= startedAt))
    }

    fun json() = JSONObject().put("id", id).put("title", title).put("started", startedAt)
        .put("profile", profile).put("research", research.json())
        .apply { endedAt?.let { put("ended", it) } }

    companion object {
        fun read(value: JSONObject) = FieldTrip(
            value.getString("id"), value.getString("title"), value.getLong("started"),
            value.optLong("ended").takeIf { it > 0 }, value.optString("profile", "Разведка"),
            ResearchCard.read(value.optJSONObject("research"))
        )
    }
}

internal data class TrackPoint(
    val point: Coordinate, val time: Long, val accuracy: Double, val altitude: Double?, val segment: Int
) {
    init {
        require(time > 0 && segment >= 0 && accuracy.isFinite() && accuracy > 0)
        require(altitude == null || altitude.isFinite())
    }

    fun json() = JSONObject().put("point", point.json()).put("time", time).put("accuracy", accuracy)
        .put("altitude", altitude ?: JSONObject.NULL).put("segment", segment)

    companion object {
        fun read(value: JSONObject) = TrackPoint(
            Coordinate.read(value.getJSONArray("point")), value.getLong("time"),
            value.getDouble("accuracy"), value.nullableDouble("altitude"), value.getInt("segment")
        )
    }
}

internal data class Visit(val id: String, val siteId: String, val time: Long, val sites: List<Site>) {
    fun json() = JSONObject().put("id", id).put("site_id", siteId).put("time", time)
        .put("sites", JSONArray(sites.map { it.feature() }))

    companion object {
        fun read(json: JSONObject) = Visit(json.getString("id"), json.getString("site_id"), json.getLong("time"),
            json.array("sites").objects().map { Site.read(it, withAttachments = true, validate = false) })
    }
}

internal data class TripEdition(val number: Int, val time: Long, val contents: TripContents, val project: JSONObject = JSONObject()) {
    fun json() = JSONObject().put("number", number).put("time", time).put("trip", contents.trip.json())
        .put("project", project).put("sites", JSONArray(contents.sites.map { it.feature() })).put("notes", JSONArray(contents.notes.map(Note::json)))
        .put("points", JSONArray(contents.points.map(TrackPoint::json))).put("visits", JSONArray(contents.visits.map(Visit::json)))

    companion object {
        fun read(json: JSONObject): TripEdition {
            val visits = json.array("visits").objects().map(Visit::read)
            return TripEdition(json.getInt("number"), json.getLong("time"), TripContents(
                FieldTrip.read(json.getJSONObject("trip")),
                json.array("sites").objects().map { Site.read(it, withAttachments = true, validate = false) },
                json.array("notes").objects().map(Note::read),
                json.array("points").objects().map(TrackPoint::read),
                visits.groupBy(Visit::siteId).mapValues { (_, rows) -> rows.maxOf(Visit::time) }, visits
            ), json.optJSONObject("project") ?: JSONObject())
        }
    }
}

internal data class TripContents(
    val trip: FieldTrip, val sites: List<Site>, val notes: List<Note>, val points: List<TrackPoint>,
    val visitTimes: Map<String, Long>, val visits: List<Visit> = emptyList()
) {
    val distanceMeters: Double get() = points.zipWithNext().sumOf { (a, b) ->
        if (a.segment == b.segment) distanceMeters(a.point, b.point) else 0.0
    }
    fun attachments() = sites.flatMap(Site::attachments) + notes.flatMap(Note::attachments) +
        visits.flatMap { it.sites.flatMap(Site::attachments) }
}

internal data class TripBackup(
    val trip: FieldTrip, val visitTimes: Map<String, Long>, val points: List<TrackPoint>,
    val visits: List<Visit> = emptyList(), val editions: List<TripEdition> = emptyList()
) {
    fun json() = trip.json().put("visits", JSONObject(visitTimes)).put("track", JSONArray(points.map(TrackPoint::json)))
        .put("visit_events", JSONArray(visits.map(Visit::json))).put("editions", JSONArray(editions.map(TripEdition::json)))

    companion object {
        fun read(value: JSONObject) = TripBackup(
            FieldTrip.read(value),
            value.getJSONObject("visits").let { times ->
                times.keys().asSequence().associateWith { id -> times.getLong(id).also { require(it > 0) } }
            },
            value.array("track").objects().map(TrackPoint::read),
            value.array("visit_events").objects().map(Visit::read),
            value.array("editions").objects().map(TripEdition::read)
        )
    }
}

private fun distanceMeters(a: Coordinate, b: Coordinate): Double {
    val latA = Math.toRadians(a.latitude)
    val latB = Math.toRadians(b.latitude)
    val delta = Math.toRadians(longitudeDelta(b.longitude, a.longitude))
    val haversine = sin((latB - latA) / 2).pow(2) + cos(latA) * cos(latB) * sin(delta / 2).pow(2)
    return 12_742_000 * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
}

internal class FieldTrips(private val journal: JournalStore) {
    fun list(): List<FieldTrip> = readTrips(null, null)
    fun get(id: String): FieldTrip? = readTrips("id = ?", arrayOf(id)).singleOrNull()
    fun active(): FieldTrip? = readTrips("ended IS NULL", null).firstOrNull()

    private fun readTrips(where: String?, args: Array<String>?): List<FieldTrip> =
        journal.readableDatabase.query("trips", arrayOf("id", "title", "started", "ended", "research"),
            where, args, null, null, "started DESC").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val fields = if (cursor.isNull(4)) JSONObject() else JSONObject(cursor.getString(4))
                    add(FieldTrip(cursor.getString(0), cursor.getString(1), cursor.getLong(2),
                        if (cursor.isNull(3)) null else cursor.getLong(3), fields.optString("profile", "Разведка"),
                        ResearchCard.read(fields.optJSONObject("card"))))
                }
            }
        }

    fun start(title: String, now: Long = System.currentTimeMillis(), profile: String = "Разведка"): FieldTrip {
        val trip = FieldTrip(newId(), title.trim().ifBlank { "Выезд · ${dateLabel(now)}" }, now, profile = profile)
        check(insert(journal.writableDatabase, trip)) { "Сначала завершите текущий выезд" }
        journal.scheduleSnapshot()
        return trip
    }

    fun updateResearch(trip: FieldTrip) = synchronized(journal) {
        journal.writableDatabase.update("trips", ContentValues().apply {
            put("research", tripFields(trip))
        }, "id = ?", arrayOf(trip.id))
        journal.scheduleSnapshot()
    }

    fun finish(id: String, now: Long = System.currentTimeMillis()) = synchronized(journal) {
        val trip = get(id) ?: return@synchronized
        if (trip.endedAt != null) return@synchronized
        val db = journal.writableDatabase
        db.beginTransaction()
        try {
            db.update("trips", ContentValues().apply { put("ended", maxOf(now, trip.startedAt)) },
                "id = ?", arrayOf(id))
            recordEdition(id)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        journal.scheduleSnapshot()
    }

    private fun saveVisit(db: SQLiteDatabase, tripId: String, value: Visit) {
        db.insertWithOnConflict("trip_visits", null, ContentValues().apply {
            put("id", value.id); put("trip_id", tripId); put("site_id", value.siteId)
            put("visited_at", value.time); put("payload", value.json().toString())
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun noteVisit(db: SQLiteDatabase, tripId: String, siteId: String, noteId: String, now: Long) {
        if (get(tripId) == null || journal.site(siteId) == null) return
        val id = java.util.UUID.nameUUIDFromBytes("$tripId $noteId".toByteArray(Charsets.UTF_8)).toString()
        saveVisit(db, tripId, Visit(id, siteId, now, includeParents(listOf(siteId), emptyMap())))
    }

    fun visit(db: SQLiteDatabase, tripId: String, siteId: String, now: Long) {
        if (get(tripId) == null || journal.site(siteId) == null) return
        saveVisit(db, tripId, Visit(newId(), siteId, now, includeParents(listOf(siteId), emptyMap())))
    }

    fun visit(tripId: String, siteId: String) = synchronized(journal) {
        visit(journal.writableDatabase, tripId, siteId, System.currentTimeMillis())
        journal.scheduleSnapshot()
    }

    fun latestVisit(siteId: String): Long? = journal.readableDatabase.rawQuery(
        "SELECT MAX(visited_at) FROM trip_visits WHERE site_id = ?", arrayOf(siteId)
    ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }

    private fun visits(id: String): List<Visit> = journal.readableDatabase.query(
        "trip_visits", arrayOf("payload"), "trip_id = ?", arrayOf(id), null, null, "visited_at, rowid"
    ).use { rows -> buildList { while (rows.moveToNext()) add(Visit.read(JSONObject(rows.getString(0)))) } }

    fun editions(id: String): List<TripEdition> = journal.readableDatabase.query(
        "trip_editions", arrayOf("payload"), "trip_id = ?", arrayOf(id), null, null, "number"
    ).use { rows -> buildList { while (rows.moveToNext()) add(TripEdition.read(JSONObject(rows.getString(0)))) } }

    fun recordEdition(id: String): TripEdition = synchronized(journal) {
        val contents = liveContents(id)
        require(contents.trip.endedAt != null) { "Сначала завершите выезд" }
        val edition = TripEdition((editions(id).lastOrNull()?.number ?: 0) + 1, System.currentTimeMillis(), contents, journal.exchangeProject())
        saveEdition(journal.writableDatabase, id, edition)
        journal.scheduleSnapshot()
        edition
    }

    private fun saveEdition(db: SQLiteDatabase, id: String, edition: TripEdition) {
        db.insertWithOnConflict("trip_editions", null, ContentValues().apply {
            put("trip_id", id); put("number", edition.number); put("payload", edition.json().toString())
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun nextSegment(tripId: String): Int = journal.readableDatabase.rawQuery(
        "SELECT COALESCE(MAX(segment) + 1, 0) FROM track_points WHERE trip_id = ?", arrayOf(tripId)
    ).use { it.moveToFirst(); it.getInt(0) }

    fun append(tripId: String, point: TrackPoint): Boolean {
        val db = journal.writableDatabase
        return db.compileStatement("INSERT OR IGNORE INTO track_points (trip_id, segment, time, lat, lon, accuracy, altitude) " +
            "SELECT id, ?, ?, ?, ?, ?, ? FROM trips WHERE id = ? AND ended IS NULL").use { statement ->
            statement.bindLong(1, point.segment.toLong())
            statement.bindLong(2, point.time)
            statement.bindDouble(3, point.point.latitude)
            statement.bindDouble(4, point.point.longitude)
            statement.bindDouble(5, point.accuracy)
            point.altitude?.let { statement.bindDouble(6, it) } ?: statement.bindNull(6)
            statement.bindString(7, tripId)
            statement.executeInsert() != -1L
        }
    }

    fun points(tripId: String): List<TrackPoint> = journal.readableDatabase.query(
        "track_points", arrayOf("lat", "lon", "time", "accuracy", "altitude", "segment"),
        "trip_id = ?", arrayOf(tripId), null, null, "segment,time"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(TrackPoint(Coordinate(cursor.getDouble(0), cursor.getDouble(1)), cursor.getLong(2),
                    cursor.getDouble(3), if (cursor.isNull(4)) null else cursor.getDouble(4), cursor.getInt(5)))
            }
        }
    }

    private fun includeParents(ids: List<String>, saved: Map<String, Site>): List<Site> {
        val result = linkedMapOf<String, Site>()
        for (id in ids) {
            var next: String? = id
            while (next != null && next !in result) {
                val site = saved[next] ?: journal.site(next) ?: break
                result[site.id] = site
                next = site.parentId
            }
        }
        return result.values.map { site -> if (site.parentId in result) site else site.copy(parentId = null) }
    }

    fun contents(tripId: String): TripContents = liveContents(tripId)

    fun exportContents(tripId: String, number: Int? = null): Pair<TripContents, TripEdition?> = synchronized(journal) {
        val trip = requireNotNull(get(tripId)) { "Выезд не найден" }
        if (trip.endedAt == null) return@synchronized liveContents(tripId) to null
        val versions = editions(tripId)
        val edition = if (number == null) versions.lastOrNull() ?: recordEdition(tripId)
            else requireNotNull(versions.find { it.number == number }) { "Редакция не найдена" }
        edition.contents to edition
    }

    private fun liveContents(tripId: String): TripContents {
        val trip = requireNotNull(get(tripId)) { "Выезд не найден" }
        val visits = visits(tripId)
        val saved = visits.flatMap(Visit::sites).associateBy(Site::id)
        val notes = journal.notesForTrip(tripId)
        val ids = (visits.map(Visit::siteId) + notes.mapNotNull(Note::siteId)).distinct()
        val sites = includeParents(ids, saved.filterKeys { journal.site(it) == null })
        val exported = sites.map(Site::id).toSet()
        return TripContents(trip, sites, notes.map { if (it.siteId in exported) it else it.copy(siteId = null) },
            points(tripId), visits.groupBy(Visit::siteId).mapValues { (_, rows) -> rows.maxOf(Visit::time) },
            visits.filter { it.siteId in exported })
    }

    fun backups(): List<TripBackup> = list().map { trip ->
        val visits = visits(trip.id)
        TripBackup(trip, visits.groupBy(Visit::siteId).mapValues { (_, rows) -> rows.maxOf(Visit::time) },
            points(trip.id), visits, editions(trip.id))
    }

    fun referencedFiles() = backups().flatMap { backup ->
        backup.visits.flatMap { visit -> visit.sites.flatMap(Site::attachments) } +
            backup.editions.flatMap { it.contents.attachments() }
    }

    fun backup(): JSONArray = JSONArray(backups().map(TripBackup::json))

    fun restore(db: SQLiteDatabase, backups: List<TripBackup>): Int {
        var added = 0
        for (backup in backups) {
            // Recording is a live session. Restored trips are available for viewing and export.
            val trip = backup.trip.copy(endedAt = backup.trip.endedAt ?: maxOf(
                backup.trip.startedAt, backup.points.maxOfOrNull(TrackPoint::time) ?: 0L
            ))
            if (!insert(db, trip)) continue
            added++
            if (backup.visits.isEmpty()) backup.visitTimes.forEach { (siteId, time) -> visit(db, trip.id, siteId, time) }
            else backup.visits.forEach { saveVisit(db, trip.id, it) }
            backup.editions.forEach { saveEdition(db, trip.id, it) }
            for (point in backup.points) {
                db.insertWithOnConflict("track_points", null, ContentValues().apply {
                    put("trip_id", trip.id)
                    put("segment", point.segment)
                    put("time", point.time)
                    put("lat", point.point.latitude)
                    put("lon", point.point.longitude)
                    put("accuracy", point.accuracy)
                    put("altitude", point.altitude)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
        return added
    }

    private fun insert(db: SQLiteDatabase, trip: FieldTrip) = db.insertWithOnConflict(
        "trips", null, ContentValues().apply {
            put("id", trip.id)
            put("title", trip.title)
            put("started", trip.startedAt)
            put("ended", trip.endedAt)
            put("research", tripFields(trip))
        }, SQLiteDatabase.CONFLICT_IGNORE
    ) != -1L

    private fun tripFields(trip: FieldTrip) = JSONObject().put("profile", trip.profile)
        .put("card", trip.research.json()).toString()

    companion object {
        fun upgrade(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE trips ADD COLUMN research TEXT")
            db.execSQL("CREATE TABLE trip_visits (id TEXT PRIMARY KEY, trip_id TEXT NOT NULL, site_id TEXT NOT NULL, visited_at INTEGER NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE INDEX trip_visits_trip ON trip_visits(trip_id, visited_at)")
            db.execSQL("CREATE INDEX trip_visits_site ON trip_visits(site_id, visited_at)")
            db.execSQL("CREATE TABLE trip_editions (trip_id TEXT NOT NULL, number INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(trip_id, number))")
            db.rawQuery("SELECT trip_sites.trip_id, trip_sites.site_id, visited_at, sites.payload " +
                "FROM trip_sites LEFT JOIN sites ON sites.id = trip_sites.site_id", null).use { rows ->
                while (rows.moveToNext()) {
                    val siteId = rows.getString(1)
                    val site = rows.getString(3)?.let { Site.read(JSONObject(it), true, validate = false) }
                    val visit = Visit(newId(), siteId, rows.getLong(2), listOfNotNull(site))
                    db.insertOrThrow("trip_visits", null, ContentValues().apply {
                        put("id", visit.id); put("trip_id", rows.getString(0)); put("site_id", siteId)
                        put("visited_at", visit.time); put("payload", visit.json().toString())
                    })
                }
            }
        }

        fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE trips (id TEXT PRIMARY KEY, title TEXT NOT NULL, started INTEGER NOT NULL, ended INTEGER)")
            db.execSQL("CREATE UNIQUE INDEX one_active_trip ON trips((1)) WHERE ended IS NULL")
            db.execSQL("CREATE TABLE trip_sites (trip_id TEXT NOT NULL, site_id TEXT NOT NULL, visited_at INTEGER NOT NULL, " +
                "PRIMARY KEY(trip_id, site_id))")
            db.execSQL("CREATE INDEX trip_sites_recent ON trip_sites(site_id, visited_at)")
            db.execSQL("CREATE TABLE track_points (trip_id TEXT NOT NULL, segment INTEGER NOT NULL, time INTEGER NOT NULL, " +
                "lat REAL NOT NULL, lon REAL NOT NULL, accuracy REAL NOT NULL, altitude REAL, PRIMARY KEY(trip_id, segment, time))")
        }
    }
}
