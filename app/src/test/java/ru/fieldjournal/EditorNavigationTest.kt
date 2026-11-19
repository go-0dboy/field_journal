package ru.fieldjournal

import android.app.AlertDialog
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
            chooseConfirmation(AlertDialog.BUTTON_NEGATIVE)
            root.text("Карточка объекта")
            root.text("Сохранено")
            assertTrue(controller.get().store.notes().any { it.siteId == site.id && it.title == "Повторный обход" })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun noticesFloatAboveMapButtonsAndNewNotesRequireConfirmation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val root = activity.window.decorView
            root.text("+ Точка").performClick()
            layout(root)
            layout(root)
            val hint = root.text("Коснитесь места и нажмите «Установить»")
            val install = root.text("Установить")
            assertTrue(top(hint) + hint.height <= top(install))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertEquals(View.GONE, hint.visibility)
            root.text("Отмена").performClick()

            root.text("Дневник").performClick()
            root.text("+ Запись").performClick()
            layout(root)
            val save = root.action("Сохранить и закрыть")
            assertEquals("✓", (save as TextView).text.toString())
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
            chooseConfirmation(AlertDialog.BUTTON_POSITIVE)
            assertEquals("Черновая запись", restored.input("Заголовок").text.toString())
            restored.action("Назад").performClick()
            chooseConfirmation(AlertDialog.BUTTON_NEUTRAL)
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
            chooseConfirmation(AlertDialog.BUTTON_NEUTRAL)
            assertEquals(original, activity.store.site(original.id))
            assertFalse(activity.store.attachmentFile(attachment.id).exists())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun chooseConfirmation(button: Int) {
        ShadowAlertDialog.getLatestAlertDialog().getButton(button).performClick()
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
