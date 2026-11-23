package ru.fieldjournal

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AuditRegressionTest {
    private fun store(): JournalStore {
        val app = RuntimeEnvironment.getApplication()
        val folder = File(app.cacheDir, newId()).apply { mkdirs() }
        return JournalStore(object : android.content.ContextWrapper(app) {
            override fun getDatabasePath(name: String) = File(folder, name)
            override fun getFilesDir() = folder
        })
    }

    private fun kit(store: JournalStore, trip: String, number: Int? = null) =
        unzip(ByteArrayOutputStream().also { store.exportTripKit(trip, number) { it } }.toByteArray())

    private fun unzip(bytes: ByteArray) = ZipInputStream(bytes.inputStream()).use { zip ->
        buildMap { while (true) put((zip.nextEntry ?: break).name, zip.readBytes()) }
    }

    @Test fun visitHistoryAndOriginalEditionSurviveEditsDeletionAndTransfer() {
        store().use { source ->
            val parent = Site(title = "Комплекс", vertices = listOf(
                Coordinate(0.0, 0.0), Coordinate(0.0, 2.0), Coordinate(2.0, 2.0), Coordinate(2.0, 0.0)))
            source.save(parent)
            val trip = source.trips.start("Обход", 1)
            val site = Site(title = "Колокольня", vertices = listOf(Coordinate(1.0, 1.0)), parentId = parent.id)
            source.commitDraft(EditorDraft(site = site, isNew = true, tripId = trip.id))
            val photo = source.addAttachment("included photo".byteInputStream(), "field.jpg", "image/jpeg")
            val note = Note(title = "Второй осмотр", siteId = site.id, tripId = trip.id, created = 10L, attachments = listOf(photo))
            source.commitDraft(EditorDraft(note = note, isNew = true))
            source.trips.finish(trip.id)
            val original = kit(source, trip.id, 1)
            assertEquals(3, original.getValue("visits.csv").decodeToString().trimEnd().lines().size)
            assertEquals(2, JSONObject(original.getValue("objects.geojson").decodeToString()).getJSONArray("features").length())
            source.save(requireNotNull(source.site(site.id)).copy(title = "Другая редакция"))
            assertEquals(2, source.trips.recordEdition(trip.id).number)
            assertTrue(kit(source, trip.id, 2).getValue("objects.geojson").decodeToString().contains("Другая редакция"))
            source.deleteSite(site.id)
            source.deleteNote(note.id)
            assertArrayEquals(original.getValue("objects.geojson"), kit(source, trip.id, 1).getValue("objects.geojson"))
            store().use { target ->
                val backup = ByteArrayOutputStream().also { source.exportBackup(full = true) { it } }.toByteArray()
                target.importBackup(backup.inputStream())
                val restored = kit(target, trip.id, 1)
                for ((name, bytes) in original) assertArrayEquals(name, bytes, restored.getValue(name))
            }
        }
    }

    @Test fun fullBackupRestoresDraftMediaAndRequiresResolutionForOtherEditors() {
        store().use { first -> store().use { second ->
            val site = Site(title = "Первая версия", vertices = emptyList(), author = "Первый")
            first.save(site)
            val staged = first.addAttachment("scan".byteInputStream(), "scan.pdf", "application/pdf")
            val draft = EditorDraft(note = Note(title = "Черновой осмотр", siteId = site.id, attachments = listOf(staged)),
                isNew = true, staged = setOf(staged.id))
            first.saveDraft(draft)
            val full = ByteArrayOutputStream().also { first.exportBackup(full = true) { it } }.toByteArray()
            assertEquals(1, second.importBackup(full.inputStream()).drafts)
            assertEquals(draft, second.draft(draft.id))
            assertEquals("scan", second.attachmentFile(staged.id).readText())
            second.save(site.copy(title = "Вторая версия", author = "Второй"))
            val incoming = ByteArrayOutputStream().also { second.exportBackup(full = true) { it } }.toByteArray()
            assertEquals(1, first.importBackup(incoming.inputStream()).conflicts)
            assertEquals("Первая версия", first.site(site.id)?.title)
            val conflict = first.conflicts().single()
            first.resolveConflict(conflict.id, useIncoming = true)
            assertEquals("Вторая версия", first.site(site.id)?.title)
            assertEquals("incoming", first.conflicts().single().status)
            assertEquals(0, first.importBackup(incoming.inputStream()).conflicts)
        } }
    }

    @Test fun geoJsonPreviewKeepsNullAndXyzAndSkipsUnsupportedLine() {
        store().use { journal ->
            val input = """{"type":"FeatureCollection","features":[
                {"type":"Feature","id":"survey","geometry":{"type":"Point","coordinates":[32.1,58.2,112.45]},
                "properties":{"title":"Репер","instrument":"station","archive_ref":{"fond":5}}},
                {"type":"Feature","id":"line","geometry":{"type":"LineString","coordinates":[[0,0],[1,1]]},"properties":{}},
                {"type":"Feature","id":"unlocated","geometry":null,"properties":{"title":"Утраченная часовня"}}
            ]}"""
            val plan = journal.inspectGeoJson(input.byteInputStream())
            assertEquals(listOf(true, false, true), plan.rows.map(GeoImportRow::accepted))
            assertTrue(journal.sites().isEmpty())
            assertEquals(2, journal.applyGeoImport(plan).sites)
            val site = requireNotNull(journal.site("survey"))
            assertEquals(112.45, site.anchor.elevation)
            assertEquals("station", site.extraProperties["instrument"])
            assertEquals(5, (site.extraProperties["archive_ref"] as JSONObject).getInt("fond"))
            assertEquals(listOf(site.id), journal.locatedSites().map(Site::id))
            assertEquals("unlocated", journal.catalogPage("ЧАСОВНЯ", null, "Не локализован", 40).single().id)
            val unlocated = requireNotNull(journal.site("unlocated"))
            assertFalse(unlocated.located)
            journal.save(unlocated.copy(research = ResearchCard(fields = mapOf("location_status" to "Ориентир по источнику"))))
            assertEquals("unlocated", journal.catalogPage("", null, null, 40, withoutCoordinates = true).single().id)
            val feature = site.feature()
            assertEquals(112.45, feature.getJSONObject("geometry").getJSONArray("coordinates").getDouble(2), 0.0)
        }
    }

    @Test fun regionalCsvRetainsIdsDescriptionsAndSourcesWhenAPlaceIsLocatedLater() {
        store().use { journal ->
            val csv = """Идентификатор;Название;Долгота;Широта;Исходный номер;Описание и примечания;Список-источник
                |row-1;Часовня;;;К-18;"Линия 1
                |Линия 2; с уточнением";https://example.org/catalog
                |row-2;Второй объект;32.0;59.0;К-19;;
                |""".trimMargin()
            assertEquals(2, journal.importCatalog(csv.byteInputStream()).sites)
            val site = requireNotNull(journal.site("row-1"))
            assertEquals("row-1", journal.siteChoices("к-18").single().id)
            assertEquals("Линия 1\nЛиния 2; с уточнением", site.description)
            assertEquals("https://example.org/catalog", site.links.single().url)
            journal.save(site.copy(vertices = listOf(Coordinate(60.0, 31.0))))
            assertEquals(0, journal.importCatalog(csv.byteInputStream()).sites)
            assertTrue(requireNotNull(journal.site("row-1")).located)
        }
    }
    @Test fun mapOffersAllElevenCoincidentCards() {
        val app = RuntimeEnvironment.getApplication()
        val tiles = MapTiles(app).apply { offline = true }
        val map = FieldMap(app, tiles)
        try {
            val coordinate = Coordinate(1.1, 2.2)
            map.layout(0, 0, 360, 720)
            map.moveTo(coordinate, 18.0)
            map.sites = (1..11).map { Site(title = "Объект $it", vertices = listOf(coordinate)) }
            var selection = emptyList<Site>()
            map.onOverlappingSites = { _, sites -> selection = sites }
            val main = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            val down = android.os.SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, 180f, 360f, 0)
                map.dispatchTouchEvent(event)
                event.recycle()
                main.idleFor(java.time.Duration.ofMillis(30))
            }
            main.idleFor(java.time.Duration.ofMillis(450))
            assertEquals(map.sites.map(Site::id).toSet(), selection.map(Site::id).toSet())
        } finally { map.release() }
    }

}
