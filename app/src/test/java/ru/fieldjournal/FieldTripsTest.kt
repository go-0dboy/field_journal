package ru.fieldjournal

import android.Manifest
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FieldTripsTest {
    @Test fun completedTripExportsTracksLinkedObservationsAndFilesAndSurvivesBackup() {
        JournalStore(RuntimeEnvironment.getApplication()).use { store ->
            val start = 1_700_000_000_000L
            val trip = store.trips.start("Обход у реки", start)
            assertThrows(IllegalStateException::class.java) { store.trips.start("Второй выезд", start) }

            val site = Site(title = "Стоянка", vertices = listOf(Coordinate(59.0, 33.0)),
                coordinateOrigin = CoordinateOrigin.DEVICE, measuredAt = start, uncertainFields = setOf("period"),
                links = listOf(WebLink("https://example.org/registry/42", "Реестр")))
            val draft = EditorDraft(site = site, isNew = true, tripId = trip.id)
            store.saveDraft(draft)
            store.commitDraft(draft)
            assertNull(store.draft(draft.id))
            assertEquals(listOf(site.id), store.trips.contents(trip.id).sites.map(Site::id))
            val visitedAt = requireNotNull(store.trips.latestVisit(site.id))
            val photo = store.addAttachment("фотография".byteInputStream(), "Общий вид.jpg", "image/jpeg")
            val note = Note(title = "Обход", body = "Вид на \"север\"\nГраница уточнена",
                siteId = site.id, tripId = trip.id, created = start, attachments = listOf(photo),
                links = listOf(WebLink("https://example.org/archive", "Архив")))
            store.save(note)

            val first = TrackPoint(site.anchor, start, 4.0, 94.0, 0)
            val second = TrackPoint(Coordinate(59.001, 33.0), start + 5_000, 5.0, null, 0)
            val resumed = TrackPoint(Coordinate(59.200, 0.0000001), start + 15_000, 5.0, 93.0, 1)
            assertTrue(store.trips.append(trip.id, first))
            assertFalse(store.trips.append(trip.id, first))
            assertTrue(store.trips.append(trip.id, second))
            assertEquals(1, store.trips.nextSegment(trip.id))
            assertTrue(store.trips.append(trip.id, resumed))
            store.trips.finish(trip.id, start + 20_000)
            assertFalse(store.trips.append(trip.id, resumed.copy(time = start + 30_000)))
            assertNull(store.trips.active())
            val contents = store.trips.contents(trip.id)
            assertEquals(listOf(first, second, resumed), contents.points)
            assertEquals(111.2, contents.distanceMeters, 0.3)

            val archive = ByteArrayOutputStream().also { store.exportTripKit(trip.id) { it } }
            val files = ZipInputStream(archive.toByteArray().inputStream()).use { zip ->
                buildMap { while (true) put((zip.nextEntry ?: break).name, zip.readBytes()) }
            }
            val photoPath = "media/${photo.id}.jpg"
            assertEquals(setOf("objects.geojson", "observations.csv", "attachments.csv", "track.gpx",
                "trip.json", "README.txt", photoPath), files.keys)
            val feature = JSONObject(files.getValue("objects.geojson").decodeToString())
                .getJSONArray("features").getJSONObject(0)
            assertEquals(site.id, feature.getJSONObject("properties").getString("site_id"))
            assertEquals(java.time.Instant.ofEpochMilli(visitedAt).toString(),
                feature.getJSONObject("properties").getString("visited_at"))
            assertEquals(setOf("period"), Site.read(feature, withAttachments = false).uncertainFields)
            assertEquals(site.links, Site.read(feature, withAttachments = false).links)
            assertTrue(files.getValue("observations.csv").decodeToString().contains("\"${note.id}\",\"${site.id}\""))
            assertTrue(files.getValue("observations.csv").decodeToString().contains("Вид на \"\"север\"\"\nГраница"))
            val encodedLinks = note.links.linksJson().toString().replace("\"", "\"\"")
            assertTrue(files.getValue("observations.csv").decodeToString().contains(",\"$encodedLinks\""))
            assertTrue(files.getValue("attachments.csv").decodeToString().contains(photoPath))
            assertEquals("фотография", files.getValue(photoPath).decodeToString())
            val gpx = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
                .parse(files.getValue("track.gpx").inputStream())
            val gpxNamespace = "http://www.topografix.com/GPX/1/1"
            assertEquals(2, gpx.getElementsByTagNameNS(gpxNamespace, "trkseg").length)
            val trackPoints = gpx.getElementsByTagNameNS(gpxNamespace, "trkpt")
            assertEquals(3, trackPoints.length)
            assertEquals("0.00000010", trackPoints.item(2).attributes.getNamedItem("lon").nodeValue)
            assertEquals("94.0", gpx.getElementsByTagNameNS(gpxNamespace, "ele").item(0).textContent)
            assertEquals("2023-11-14T22:13:20Z", gpx.getElementsByTagNameNS(gpxNamespace, "time").item(0).textContent)

            val backup = ByteArrayOutputStream().also(store::exportBackup).toByteArray()
            store.deleteNote(note.id)
            store.deleteSite(site.id)
            for (table in listOf("track_points", "trip_sites", "trips")) store.writableDatabase.delete(table, null, null)
            assertEquals(ImportResult(1, 1, 0, 1), store.importBackup(backup.inputStream()))
            assertEquals(contents, store.trips.contents(trip.id))
            assertEquals("фотография", store.attachmentFile(photo.id).readText())
            assertEquals(ImportResult(0, 0, 3), store.importBackup(backup.inputStream()))
        }
    }

    @Test fun foregroundRecordingPersistsPointsAndResumingStartsANewSegment() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager = shadowOf(app.getSystemService(LocationManager::class.java))
        manager.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val store = JournalStore.get(app)
        store.trips.active()?.let { store.trips.finish(it.id) }
        val trip = store.trips.start("Маршрут")
        val main = shadowOf(Looper.getMainLooper())
        val provider = LocationManager.GPS_PROVIDER

        fun recordAt(latitude: Double, time: Long) {
            main.idleFor(Duration.ofSeconds(6))
            manager.simulateLocation(Location(provider).apply {
                this.latitude = latitude
                longitude = 33.0
                accuracy = 5f
                this.time = time
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            })
            main.idle()
        }

        val start = Intent(app, TripRecordingService::class.java).putExtra("trip_id", trip.id)
        val controller = Robolectric.buildService(TripRecordingService::class.java, start).create().startCommand(0, 1)
        try {
            assertEquals(trip.id, TripRecording.tripId)
            assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
            recordAt(59.0, trip.startedAt + 6_000)
            assertEquals(listOf(0), store.trips.points(trip.id).map(TrackPoint::segment))
        } finally {
            controller.destroy()
        }
        assertNull(TripRecording.tripId)
        val resumed = Robolectric.buildService(TripRecordingService::class.java, start).create().startCommand(0, 2)
        try {
            recordAt(59.001, trip.startedAt + 12_000)
            assertEquals(listOf(0, 1), store.trips.points(trip.id).map(TrackPoint::segment))
        } finally {
            resumed.destroy()
            store.trips.finish(trip.id)
        }
    }
}
