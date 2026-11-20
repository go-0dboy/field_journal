package ru.fieldjournal

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-mdpi")
class EditorNavigationTest {
    @Test fun linkedNoteReturnsToOriginAfterRecreationAndNewNoteDoesTheSame() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val site = Site(title = "Стоянка у реки", vertices = listOf(Coordinate(59.0, 33.0)))
            val note = Note(title = "Первый обход", siteId = site.id)
            activity.store.save(site)
            activity.store.save(note)
            activity.openSite(site.id)
            val initial = activity.window.decorView
            initial.descendants().filterIsInstance<TextView>().first { it.text.startsWith(note.title) }.performClick()
            controller.recreate()

            val root = controller.get().window.decorView
            root.text("Запись дневника")
            root.action("Назад").performClick()
            root.text("Карточка объекта")
            assertEquals(site.title, root.input("Название").text.toString())

            root.text("+ Запись об объекте").performClick()
            root.input("Заголовок").setText("Повторный обход")
            root.action("Сохранить и закрыть").performClick()
            assertFalse(controller.get().store.notes().any { it.title == "Повторный обход" })
            chooseConfirmation("Сохранить")
            root.text("Карточка объекта")
            root.text("Сохранено")
            assertTrue(controller.get().store.notes().any { it.siteId == site.id && it.title == "Повторный обход" })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun leavingGeometryEditKeepsTheConfirmationForOtherCardChanges() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val original = Site(title = "Стоянка", vertices = listOf(Coordinate(59.0, 33.0)))
            activity.store.save(original)
            activity.openSite(original.id)
            val root = activity.window.decorView
            root.input("Название").setText("Стоянка у реки")
            activity.editGeometry(original)
            val map = root.descendants().filterIsInstance<FieldMap>().single()
            map.onTap?.invoke(Coordinate(60.0, 34.0), null)
            root.text("Дневник").performClick()
            assertFalse(map.editingGeometry)
            val confirmation = ShadowAlertDialog.getLatestAlertDialog().window!!.decorView
            confirmation.text("Сохранить изменения?")
            chooseConfirmation("Сохранить")
            assertTrue(root.text("+ Запись").isShown)
            val saved = requireNotNull(activity.store.site(original.id))
            assertEquals("Стоянка у реки", saved.title)
            assertEquals(original.vertices, saved.vertices)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun attachmentCopyReturnsToTheSameDraftAfterRecreation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val started = java.util.concurrent.CountDownLatch(1)
        val resumeCopy = java.util.concurrent.CountDownLatch(1)
        try {
            val activity = controller.get()
            val site = Site(title = "Стоянка", vertices = listOf(Coordinate(59.0, 33.0)))
            activity.store.save(site)
            activity.openSite(site.id)
            val file = java.io.File(activity.cacheDir, "скан.jpg").apply { writeText("image bytes") }
            val provider = object : android.content.ContentProvider() {
                override fun onCreate() = true
                override fun insert(uri: android.net.Uri, values: android.content.ContentValues?) = null
                override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?) = 0
                override fun update(
                    uri: android.net.Uri, values: android.content.ContentValues?,
                    selection: String?, selectionArgs: Array<out String>?
                ) = 0

                override fun openFile(uri: android.net.Uri, mode: String): android.os.ParcelFileDescriptor {
                    started.countDown()
                    check(resumeCopy.await(8, java.util.concurrent.TimeUnit.SECONDS))
                    return android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                }

                override fun query(
                    uri: android.net.Uri, projection: Array<out String>?, selection: String?,
                    selectionArgs: Array<out String>?, sortOrder: String?
                ) = android.database.MatrixCursor(arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)).apply {
                    addRow(arrayOf(file.name))
                }

                override fun getType(uri: android.net.Uri) = "image/jpeg"
            }
            val testAuthority = "journal.test.attachment"
            provider.attachInfo(activity.applicationContext, android.content.pm.ProviderInfo().apply {
                authority = testAuthority
                exported = true
            })
            org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(testAuthority, provider)
            activity.pickAttachment(site.id, note = false, photoOnly = true)
            val shadow = shadowOf(activity)
            val request = shadow.nextStartedActivityForResult
            shadow.receiveResult(request.intent, android.app.Activity.RESULT_OK, android.content.Intent().setData(
                android.net.Uri.parse("content://$testAuthority/photo")
            ))
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            controller.recreate()
            val restored = controller.get()
            resumeCopy.countDown()
            val root = restored.window.decorView
            val main = shadowOf(Looper.getMainLooper())
            val timeout = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(6)
            while (root.descendants().filterIsInstance<TextView>().none { it.text == file.name } &&
                System.nanoTime() < timeout) {
                main.idle()
                Thread.sleep(10)
            }
            assertEquals(1, root.descendants().filterIsInstance<TextView>().count { it.text == file.name })
            assertTrue(restored.store.site(site.id)!!.attachments.isEmpty())
            restored.closeEditor()
            chooseConfirmation("Сохранить")
            val saved = restored.store.site(site.id)!!.attachments.single()
            assertEquals("image bytes", restored.store.attachmentFile(saved.id).readText())
        } finally {
            resumeCopy.countDown()
            controller.pause().stop().destroy()
        }
    }

    @Test fun noticesFloatAboveMapButtonsAndNewNotesRequireConfirmation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val root = activity.window.decorView
            root.action(MapIcon.POINT.description).performClick()
            layout(root)
            layout(root)
            val hint = root.text("Коснитесь карты или выберите GPS, затем нажмите галочку")
            val install = root.action(MapIcon.SET_POINT.description)
            assertTrue(top(hint) >= top(install) + install.height)
            assertTrue(top(hint) + hint.height <= top(root.action("Моё место")))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertEquals(View.GONE, hint.visibility)
            root.action(MapIcon.CANCEL.description).performClick()

            root.text("Дневник").performClick()
            root.text("+ Запись").performClick()
            layout(root)
            val save = root.action("Сохранить и закрыть")
            val back = root.action("Назад")
            assertEquals(back.width, save.width)
            assertEquals((back as android.widget.ImageButton).drawable.intrinsicWidth,
                (save as android.widget.ImageButton).drawable.intrinsicWidth)
            assertNull(root.descendants().filterIsInstance<TextView>().firstOrNull {
                it.text == "Разделы" || it.text == "Сохранено" || it.text == "Готово"
            })
            val knownIds = activity.store.notes().map(Note::id).toSet()
            root.input("Заголовок").setText("Черновая запись")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            controller.recreate()
            val restored = controller.get().window.decorView
            assertEquals("Черновая запись", restored.input("Заголовок").text.toString())
            restored.action("Сохранить и закрыть").performClick()
            assertTrue(activity.store.notes().all { it.id in knownIds })
            chooseConfirmation("Отмена")
            assertEquals("Черновая запись", restored.input("Заголовок").text.toString())
            restored.action("Назад").performClick()
            chooseConfirmation("Не сохранять")
            restored.text("Дневник")
            assertTrue(activity.store.notes().all { it.id in knownIds })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun discardingChangesKeepsStoredObjectAndClearsStagedFile() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val original = Site(title = "Исходный объект", vertices = listOf(Coordinate(59.0, 33.0)))
            activity.store.save(original)
            activity.openSite(original.id)
            val root = activity.window.decorView
            root.input("Название").setText("Изменено")
            val attachment = activity.store.addAttachment("данные".byteInputStream(), "plan.txt", "text/plain")
            root.descendants().filterIsInstance<JournalEditor>().single().stageAttachment(attachment)
            assertEquals(original, activity.store.site(original.id))
            root.action("Назад").performClick()
            chooseConfirmation("Не сохранять")
            assertEquals(original, activity.store.site(original.id))
            assertFalse(activity.store.attachmentFile(attachment.id).exists())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun quickObservationRestoresTextPhotoAndTripAfterReopeningTheApp() {
        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = first.get()
        val site = Site(title = "Урочище", vertices = listOf(Coordinate(59.0, 33.0)))
        val trip = activity.store.trips.start("Первый выезд")
        val photo = activity.store.addAttachment("photo contents".byteInputStream(), "Урочище.jpg", "image/jpeg")
        try {
            activity.store.save(site)
            val map = activity.window.decorView.descendants().filterIsInstance<FieldMap>().single()
            map.onTap!!.invoke(site.anchor, site)
            val preview = ShadowAlertDialog.getLatestAlertDialog().window!!.decorView
            preview.text(site.title)
            preview.text("Новое наблюдение").performClick()
            val root = activity.window.decorView
            root.input("Короткая заметка").setText("Северная граница проверена")
            root.descendants().filterIsInstance<JournalEditor>().single().stageAttachment(photo)
            root.action("Назад").performClick()
            chooseConfirmation("В черновики")
            assertTrue(activity.store.noteSummaries(site.id).isEmpty())
            assertEquals(1, activity.store.draftCount())
        } finally {
            first.pause().stop().destroy()
        }
        val second = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val reopened = second.get()
            val root = reopened.window.decorView
            root.text("Дневник").performClick()
            root.text("Черновики · 1").performClick()
            ShadowAlertDialog.getLatestAlertDialog().window!!.decorView.text("Продолжить").performClick()
            assertEquals("Северная граница проверена", root.input("Короткая заметка").text.toString())
            root.text(photo.name)
            root.action("Сохранить и закрыть").performClick()
            chooseConfirmation("Сохранить")
            assertEquals(0, reopened.store.draftCount())
            val contents = reopened.store.trips.contents(trip.id)
            val note = contents.notes.single()
            assertEquals(site, contents.sites.single())
            assertEquals(site.id, note.siteId)
            assertEquals(trip.id, note.tripId)
            assertEquals(listOf(photo), note.attachments)
            assertEquals("photo contents", reopened.store.attachmentFile(photo.id).readText())
        } finally {
            activity.store.trips.finish(trip.id)
            second.pause().stop().destroy()
        }
    }

    private fun chooseConfirmation(button: String) {
        ShadowAlertDialog.getLatestAlertDialog().window!!.decorView.text(button).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 320, 640)
    }

    private fun top(view: View) = IntArray(2).also(view::getLocationOnScreen)[1]

    private fun View.action(description: String) = descendants().first { it.contentDescription == description }
    private fun View.text(value: String) = descendants().filterIsInstance<TextView>().first { it.text == value }
    private fun View.input(hint: String) = descendants().filterIsInstance<EditText>().first { it.hint == hint }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) for (index in 0 until childCount) yieldAll(getChildAt(index).descendants())
    }
}
