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

internal data class FieldTrip(val id: String, val title: String, val startedAt: Long, val endedAt: Long? = null) {
    init {
        require(attachmentId.matches(id) && startedAt > 0 && (endedAt == null || endedAt >= startedAt))
    }

    fun json() = JSONObject().put("id", id).put("title", title).put("started", startedAt)
        .apply { endedAt?.let { put("ended", it) } }

    companion object {
        fun read(value: JSONObject) = FieldTrip(
            value.getString("id"), value.getString("title"), value.getLong("started"),
            value.optLong("ended").takeIf { it > 0 }
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

internal data class TripContents(
    val trip: FieldTrip, val sites: List<Site>, val notes: List<Note>, val points: List<TrackPoint>,
    val visitTimes: Map<String, Long>
) {
    val distanceMeters: Double get() = points.zipWithNext().sumOf { (a, b) ->
        if (a.segment == b.segment) distanceMeters(a.point, b.point) else 0.0
    }
}

internal data class TripBackup(val trip: FieldTrip, val visitTimes: Map<String, Long>, val points: List<TrackPoint>) {
    fun json() = trip.json().put("visits", JSONObject(visitTimes)).put("track", JSONArray(points.map(TrackPoint::json)))

    companion object {
        fun read(value: JSONObject) = TripBackup(
            FieldTrip.read(value),
            value.getJSONObject("visits").let { times ->
                times.keys().asSequence().associateWith { id -> times.getLong(id).also { require(it > 0) } }
            },
            value.array("track").objects().map(TrackPoint::read)
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
        journal.readableDatabase.query("trips", arrayOf("id", "title", "started", "ended"),
            where, args, null, null, "started DESC").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(FieldTrip(cursor.getString(0), cursor.getString(1), cursor.getLong(2),
                        if (cursor.isNull(3)) null else cursor.getLong(3)))
                }
            }
        }

    fun start(title: String, now: Long = System.currentTimeMillis()): FieldTrip {
        val trip = FieldTrip(newId(), title.trim().ifBlank { "Выезд · ${dateLabel(now)}" }, now)
        check(insert(journal.writableDatabase, trip)) { "Сначала завершите текущий выезд" }
        journal.scheduleSnapshot()
        return trip
    }

    fun finish(id: String, now: Long = System.currentTimeMillis()) {
        val trip = get(id) ?: return
        journal.writableDatabase.update("trips", ContentValues().apply { put("ended", maxOf(now, trip.startedAt)) },
            "id = ? AND ended IS NULL", arrayOf(id))
        journal.scheduleSnapshot()
    }

    fun visit(db: SQLiteDatabase, tripId: String, siteId: String, now: Long) {
        db.execSQL("INSERT OR REPLACE INTO trip_sites (trip_id, site_id, visited_at) " +
            "SELECT id, ?, ? FROM trips WHERE id = ?", arrayOf(siteId, now, tripId))
    }

    fun visit(tripId: String, siteId: String) {
        visit(journal.writableDatabase, tripId, siteId, System.currentTimeMillis())
        journal.scheduleSnapshot()
    }

    fun latestVisit(siteId: String): Long? = journal.readableDatabase.rawQuery(
        "SELECT MAX(visited_at) FROM trip_sites WHERE site_id = ?", arrayOf(siteId)
    ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }

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

    private fun visitTimes(tripId: String): Map<String, Long> = journal.readableDatabase.query(
        "trip_sites", arrayOf("site_id", "visited_at"), "trip_id = ?", arrayOf(tripId), null, null, "site_id"
    ).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getLong(1)) } }

    fun contents(tripId: String): TripContents {
        val trip = requireNotNull(get(tripId)) { "Выезд не найден" }
        val notes = journal.notesForTrip(tripId)
        val visits = visitTimes(tripId)
        val siteIds = (visits.keys + notes.mapNotNull(Note::siteId)).distinct()
        return TripContents(trip, siteIds.mapNotNull(journal::site), notes, points(tripId), visits)
    }

    fun backup(): JSONArray = JSONArray(list().map { TripBackup(it, visitTimes(it.id), points(it.id)).json() })

    fun restore(db: SQLiteDatabase, backups: List<TripBackup>): Int {
        var added = 0
        for (backup in backups) {
            // Recording is a live session. Restored trips are available for viewing and export.
            val trip = backup.trip.copy(endedAt = backup.trip.endedAt ?: maxOf(
                backup.trip.startedAt, backup.points.maxOfOrNull(TrackPoint::time) ?: 0L
            ))
            if (!insert(db, trip)) continue
            added++
            backup.visitTimes.forEach { (siteId, time) -> visit(db, trip.id, siteId, time) }
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
        }, SQLiteDatabase.CONFLICT_IGNORE
    ) != -1L

    companion object {
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
