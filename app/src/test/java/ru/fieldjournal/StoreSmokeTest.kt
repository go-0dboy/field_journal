package ru.fieldjournal

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class StoreSmokeTest {
    @Test fun backupRestoresMetadataDiaryAttachmentsAndDeduplicates() {
        val context = RuntimeEnvironment.getApplication()
        val store = JournalStore(context)
        val record = store.addAttachment("archive".byteInputStream(), "plan.txt", "text/plain")
        val initial = Site(title = "Тест", vertices = listOf(Coordinate(59.0, 33.0)), attachments = listOf(record))
        val note = Note(title = "Дневник", siteId = initial.id, attachments = listOf(record))
        store.save(initial)
        store.save(note)
        val backup = ByteArrayOutputStream().also(store::exportBackup).toByteArray()
        store.save(initial, setOf(record.id))
        assertTrue(store.attachmentFile(record.id).exists())
        assertTrue(store.site(initial.id)!!.attachments.isEmpty())
        store.deleteNote(note.id)
        assertFalse(store.attachmentFile(record.id).exists())
        store.deleteSite(initial.id)
        assertEquals(ImportResult(1, 1, 0), store.importBackup(ByteArrayInputStream(backup)))
        assertEquals(initial, store.sites().single())
        assertEquals(note, store.notes().single())
        assertEquals("archive", store.attachmentFile(record.id).readText())
        assertEquals(ImportResult(0, 0, 2), store.importBackup(ByteArrayInputStream(backup)))
        val geojson = ByteArrayOutputStream().also(store::exportGeoJson).toByteArray()
        store.deleteSite(initial.id)
        assertEquals(ImportResult(1, 0, 0), store.importGeoJson(ByteArrayInputStream(geojson)))
        assertTrue(store.sites().single().attachments.isEmpty())
    }

    @Test fun rejectedBackupDoesNotOpenDestination() {
        val store = JournalStore(RuntimeEnvironment.getApplication(), backupLimit = 1024)
        val record = store.addAttachment(ByteArray(1025).inputStream(), "plan.txt", "text/plain")
        store.save(Site(title = "Тест", vertices = listOf(Coordinate(59.0, 33.0)), attachments = listOf(record)))
        var destinationOpened = false
        assertThrows(IllegalArgumentException::class.java) {
            store.exportBackup {
                destinationOpened = true
                ByteArrayOutputStream()
            }
        }
        assertFalse(destinationOpened)
    }

    @Test fun geoJsonUsesTheSameLimitInBothDirectionsAndRollsBackInvalidImport() {
        val context = RuntimeEnvironment.getApplication()
        val store = JournalStore(context, geoJsonLimit = 850)
        val site = Site(title = "Стоянка", vertices = listOf(Coordinate(59.0, 33.0)))
        store.save(site)
        val geojson = ByteArrayOutputStream().also(store::exportGeoJson).toByteArray()
        store.deleteSite(site.id)
        assertEquals(ImportResult(1, 0, 0), store.importGeoJson(geojson.inputStream()))
        assertEquals(ImportResult(0, 0, 1), store.importGeoJson(geojson.inputStream()))
        val second = site.copy(id = "second")
        val invalidCollection = """{"features":[${second.feature()},{}],"type":"FeatureCollection"}"""
        assertThrows(Exception::class.java) { store.importGeoJson(invalidCollection.byteInputStream()) }
        assertNull(store.site(second.id))
        val oversized = site.copy(description = "я".repeat(1000))
        store.save(oversized)
        assertThrows(IllegalArgumentException::class.java) {
            store.exportGeoJson { fail("Opened an oversized export destination"); ByteArrayOutputStream() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.importGeoJson(oversized.feature().toString().byteInputStream())
        }
        store.close()
    }

    @Test fun migrationIndexesRelatedNotesWithoutRewritingTheirContents() {
        val context = RuntimeEnvironment.getApplication()
        val site = Site(title = "Стоянка", vertices = listOf(Coordinate(59.0, 33.0)))
        val earlier = Note(title = "Ранее", body = "Наблюдение", siteId = site.id, created = 10)
        val later = Note(title = "Позже", siteId = site.id, created = 20)
        val unrelated = Note(title = "Другой объект", created = 30)
        context.openOrCreateDatabase("field-journal.db", 0, null).use { legacy ->
            for (table in listOf("sites", "notes")) {
                legacy.execSQL("CREATE TABLE $table (id TEXT PRIMARY KEY, updated INTEGER NOT NULL, payload TEXT NOT NULL)")
            }
            legacy.execSQL("INSERT INTO sites VALUES (?,?,?)", arrayOf(site.id, site.updated, site.feature().toString()))
            for (entry in listOf(earlier, later, unrelated)) {
                legacy.execSQL("INSERT INTO notes VALUES (?,?,?)", arrayOf(entry.id, entry.updated, entry.json().toString()))
            }
            legacy.version = 1
        }
        JournalStore(context).use { store ->
            assertEquals(listOf(later.id, earlier.id), store.noteSummaries(site.id).map(NoteSummary::id))
            assertEquals(listOf(unrelated.id, later.id, earlier.id), store.noteSummaries().map(NoteSummary::id))
            assertEquals(earlier, store.note(earlier.id))
            assertEquals(site.title, store.noteSummaries(site.id).first().siteTitle)
            store.save(site.copy(title = "Переименована"))
            assertEquals("Переименована", store.noteSummaries(site.id).first().siteTitle)
            store.save(later.copy(siteId = null))
            assertEquals(listOf(earlier.id), store.noteSummaries(site.id).map(NoteSummary::id))
        }
    }

    @Test fun failedWriteKeepsDraftOpenAndSurvivesRecreation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val original = Site(title = "Старое название", vertices = listOf(Coordinate(59.0, 33.0)))
        activity.store.save(original)
        activity.openSite(original.id)
        fun titleInput(view: android.view.View): android.widget.EditText? = when (view) {
            is android.widget.EditText -> view.takeIf { it.hint == "Название" }
            is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { titleInput(view.getChildAt(it)) }
            else -> null
        }
        activity.store.writableDatabase.execSQL(
            "CREATE TRIGGER refuse_site BEFORE INSERT ON sites BEGIN SELECT RAISE(ABORT, 'write failed'); END"
        )
        try {
            val input = requireNotNull(titleInput(activity.window.decorView))
            input.setText("Исправленное название")
            activity.closeEditor()
            acceptSave()
            assertEquals("Исправленное название", titleInput(activity.window.decorView)?.text.toString())
            assertEquals(original.title, activity.store.site(original.id)?.title)
            controller.recreate()
            assertEquals("Исправленное название", titleInput(controller.get().window.decorView)?.text.toString())
        } finally {
            controller.get().store.writableDatabase.execSQL("DROP TRIGGER refuse_site")
        }
        controller.get().closeEditor()
        acceptSave()
        assertEquals("Исправленное название", controller.get().store.site(original.id)?.title)
        assertNull(titleInput(controller.get().window.decorView))
        controller.pause().stop().destroy()
    }

    @Test fun groupKeepsSeparateColorsAndSymbolsThroughGeoJsonAndDetachesChildrenOnDelete() {
        JournalStore(RuntimeEnvironment.getApplication()).use { store ->
            fun polygon(south: Double, west: Double, north: Double, east: Double) = listOf(
                Coordinate(south, west), Coordinate(south, east), Coordinate(north, east), Coordinate(north, west)
            )
            val complex = Site(title = "Монастырь", type = "complex", vertices = polygon(59.0, 33.0, 59.04, 33.04))
            val church = Site(title = "Храм", type = "church", vertices = polygon(59.005, 33.005, 59.015, 33.015),
                parentId = complex.id, customColor = 0xff23746f.toInt())
            val bell = Site(title = "Звонница", type = "bell_tower", vertices = listOf(Coordinate(59.01, 33.01)))
            store.save(complex)
            store.save(church)
            assertEquals(listOf(church.id, complex.id), store.groupCandidates(bell).map(Site::id))
            val linkedBell = bell.copy(parentId = church.id)
            store.save(linkedBell)
            assertThrows(IllegalArgumentException::class.java) {
                store.save(church.copy(vertices = polygon(59.002, 33.002, 59.008, 33.008)))
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.save(linkedBell.copy(vertices = listOf(Coordinate(59.03, 33.03))))
            }
            val json = ByteArrayOutputStream().also(store::exportGeoJson).toByteArray()
            store.deleteSite(complex.id)
            assertNull(store.site(church.id)?.parentId)
            assertEquals(linkedBell, store.site(bell.id))
            store.deleteSite(church.id)
            store.deleteSite(bell.id)
            assertEquals(ImportResult(3, 0, 0), store.importGeoJson(json.inputStream()))
            assertEquals(church, store.children(complex.id).single())
            assertEquals("bell_tower", store.children(church.id).single().symbol.id)
            assertEquals(church.customColor, store.site(church.id)?.symbol?.color)
        }
    }

    @Test fun deletingATripKeepsNotesDraftsSitesAndAttachments() {
        JournalStore(RuntimeEnvironment.getApplication()).use { store ->
            val trip = store.trips.start("Разведка")
            val photo = store.addAttachment("photo".byteInputStream(), "Фото.jpg", "image/jpeg")
            val site = Site(title = "Курган", vertices = listOf(Coordinate(59.0, 33.0)))
            store.save(site)
            val note = Note(title = "Осмотр", siteId = site.id, tripId = trip.id, attachments = listOf(photo))
            store.save(note)
            store.saveDraft(EditorDraft(note = note.copy(body = "Наблюдение"), dirty = true))
            store.trips.visit(trip.id, site.id)
            store.trips.append(trip.id, TrackPoint(site.anchor, trip.startedAt, 4.0, null, 0))

            store.deleteTrip(trip.id)

            assertNull(store.trips.get(trip.id))
            assertTrue(store.trips.points(trip.id).isEmpty())
            assertNull(store.trips.latestVisit(site.id))
            assertEquals(note.copy(tripId = null), store.note(note.id))
            assertEquals(note.copy(tripId = null, body = "Наблюдение"), store.draft(note.id)?.note)
            assertEquals(site, store.site(site.id))
            assertEquals("photo", store.attachmentFile(photo.id).readText())
            assertEquals(listOf(note.id), store.noteSummaries().map(NoteSummary::id))
        }
    }

    private fun acceptSave() {
        val decor = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().window!!.decorView
        val matches = ArrayList<android.view.View>()
        decor.findViewsWithText(matches, "Сохранить", android.view.View.FIND_VIEWS_WITH_TEXT)
        matches.filterIsInstance<android.widget.TextView>().single { it.text == "Сохранить" }.performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun replacingOfflinePackageUsesNewTiles() {
        val context = RuntimeEnvironment.getApplication()
        val tiles = MapTiles(context)
        fun install(slot: MapPackage, name: String, color: Int) {
            val file = java.io.File(context.cacheDir, "$name.mbtiles")
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)")
                db.execSQL("INSERT INTO metadata VALUES ('name',?),('format','png')", arrayOf(name))
                db.execSQL("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
                val bitmap = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(color)
                val raster = ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                db.execSQL("INSERT INTO tiles VALUES (4,8,10,?)", arrayOf(raster.toByteArray()))
            }
            file.inputStream().use { tiles.install(slot, it) }
        }

        install(MapPackage.STREET, "first", android.graphics.Color.BLUE)
        assertEquals("first", tiles.info(MapPackage.STREET)?.name)
        val tile = Tile(4, 8, 5)
        assertEquals(android.graphics.Color.BLUE, tiles.read(MapLayer.STREET, true, "", tile, TilePass.BASE)?.getPixel(10, 10))
        install(MapPackage.STREET, "replacement", android.graphics.Color.RED)
        assertEquals("replacement", tiles.info(MapPackage.STREET)?.name)
        assertEquals(android.graphics.Color.RED, tiles.read(MapLayer.STREET, true, "", tile, TilePass.BASE)?.getPixel(10, 10))

        val historicColor = android.graphics.Color.YELLOW
        val referenceColor = 0x80001824.toInt()
        install(MapPackage.HISTORICAL, "Архивная карта", historicColor)
        install(MapPackage.REFERENCES, "Подписи", referenceColor)
        assertEquals(historicColor, tiles.read(MapLayer.STREET, false, "", tile, TilePass.HISTORICAL)?.getPixel(10, 10))
        assertEquals(historicColor, tiles.read(MapLayer.SATELLITE, true, "", tile, TilePass.HISTORICAL)?.getPixel(10, 10))
        assertEquals(referenceColor, tiles.read(MapLayer.STREET, true, "", tile, TilePass.PLACES)?.getPixel(10, 10))
        assertNull(tiles.read(MapLayer.SATELLITE, true, "", tile, TilePass.BASE))
        install(MapPackage.SATELLITE, "Спутник", android.graphics.Color.GREEN)
        assertEquals(android.graphics.Color.GREEN, tiles.read(MapLayer.HYBRID, true, "", tile, TilePass.BASE)?.getPixel(10, 10))
        install(MapPackage.HYBRID, "Гибрид", android.graphics.Color.BLUE)
        assertEquals(android.graphics.Color.BLUE, tiles.read(MapLayer.HYBRID, true, "", tile, TilePass.BASE)?.getPixel(10, 10))
        assertNull(tiles.read(MapLayer.STREET, true, "", Tile(4, 0, 0), TilePass.HISTORICAL))

        tiles.layer = MapLayer.STREET
        assertFalse(tiles.showReferences())
        tiles.historicalEnabled = true
        tiles.historicalOpacity = 40
        val restored = MapTiles(context)
        assertEquals(40, restored.historicalOpacity)
        assertTrue(restored.showReferences())
        restored.referencesEnabled = false
        assertFalse(restored.showReferences())
        restored.historicalEnabled = false
        restored.referencesEnabled = true
    }

    @Test @Config(qualifiers = "w320dp-h600dp")
    fun initialActivityRendersMainSections() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        fun labels(view: android.view.View): List<String> = when (view) {
            is android.widget.TextView -> listOf(view.text.toString())
            is android.view.ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
            else -> emptyList()
        }
        val text = labels(root)
        assertFalse(text.contains("Полевой дневник"))
        assertTrue(text.containsAll(listOf("Карта", "Объекты", "Дневник")))
        val buttons = ArrayList<android.view.View>()
        for ((icon, label) in listOf(FieldIcon.POINT to "Точка", FieldIcon.AREA to "Область")) {
            buttons.clear()
            root.findViewsWithText(buttons, icon.description, android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
            assertTrue(buttons.filterIsInstance<android.widget.TextView>().any {
                it.isClickable && it.contentDescription == icon.description && it.text == label
            })
        }
        for (icon in listOf(FieldIcon.COORDINATES, FieldIcon.LAYERS)) {
            buttons.clear()
            root.findViewsWithText(buttons, icon.description, android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
            assertTrue(buttons.any { it is android.widget.ImageButton && it.contentDescription == icon.description })
        }
        controller.pause().stop().destroy()
    }
}
