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
        store.deleteNote(note.id)
        store.deleteSite(initial.id)
        assertTrue(store.attachmentFile(record.id).delete())
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

    @Test fun initialActivityRendersMainSections() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        fun labels(view: android.view.View): List<String> = when (view) {
            is android.widget.TextView -> listOf(view.text.toString())
            is android.view.ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
            else -> emptyList()
        }
        assertTrue(labels(root).contains("Полевой дневник"))
        assertTrue(labels(root).contains("+ Точка"))
        assertTrue(labels(root).contains("+ Область"))
        assertTrue(labels(root).any { it.endsWith("Дневник") })
        controller.pause().stop().destroy()
    }
}
