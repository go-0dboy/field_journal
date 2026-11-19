package ru.fieldjournal

import android.app.AlertDialog
import android.content.ClipboardManager
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
@Config(sdk = [34], qualifiers = "w360dp-h700dp-mdpi")
class MapEditingTest {
    @Test fun quickTapsBuildBoundaryAndVertexCanBeDraggedThenRemoved() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val savedIds = activity.store.sites().map(Site::id).toSet()
            val root = activity.window.decorView
            root.text("+ Область").performClick()
            layout(root)
            val map = root.descendants().filterIsInstance<FieldMap>().first()
            map.moveTo(Coordinate(59.0, 33.0), 19.0)
            val initialCenter = map.center
            tap(map, 70f, 110f)
            tap(map, 145f, 110f)
            tap(map, 145f, 190f)
            tap(map, 70f, 190f)
            assertEquals(4, map.draft.size)
            assertNull(polygonError(map.draft))
            val before = map.draft.first()
            val down = SystemClock.uptimeMillis()
            send(map, down, MotionEvent.ACTION_DOWN, 70f, 110f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
            send(map, down, MotionEvent.ACTION_MOVE, 60f, 94f)
            send(map, down, MotionEvent.ACTION_UP, 60f, 94f)
            assertEquals(initialCenter, map.center)
            assertNotEquals(before, map.draft.first())
            tap(map, 60f, 94f)
            assertEquals(3, map.draft.size)
            assertNull(polygonError(map.draft))
            root.text("Готово").performClick()
            chooseFirstType()
            val saved = activity.store.sites().single { it.id !in savedIds }
            assertEquals(3, saved.vertices.size)
            activity.closeEditor()
            activity.editGeometry(saved)
            assertEquals(3, map.draft.size)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun coordinatesCopyTheCenterThenThePointSelectedByTap() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val savedIds = activity.store.sites().map(Site::id).toSet()
            val root = activity.window.decorView
            val map = root.descendants().filterIsInstance<FieldMap>().first()
            val clipboard = activity.getSystemService(ClipboardManager::class.java)
            root.text("Координаты").performClick()
            val centerDialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals("Отмена", centerDialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            centerDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(map.center.display(), clipboard.primaryClip?.getItemAt(0)?.text.toString())

            root.text("+ Точка").performClick()
            layout(root)
            tap(map, 85f, 130f)
            val selected = requireNotNull(map.pointPreview)
            assertNotEquals(map.center, selected)
            root.text("Координаты").performClick()
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(selected.display(), clipboard.primaryClip?.getItemAt(0)?.text.toString())
            root.text("Установить").performClick()
            chooseFirstType()
            assertEquals(selected, activity.store.sites().single { it.id !in savedIds }.anchor)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun chooseFirstType() {
        val chooser = ShadowAlertDialog.getLatestAlertDialog()
        val label = requireNotNull(chooser.window).decorView.text(objectTypes.first().title)
        (label.parent as View).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 360, 700)
    }

    private fun tap(view: View, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        send(view, down, MotionEvent.ACTION_DOWN, x, y)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
        send(view, down, MotionEvent.ACTION_UP, x, y)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun send(view: View, down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun View.text(value: String) = descendants().filterIsInstance<TextView>().first { it.text == value }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) for (index in 0 until childCount) yieldAll(getChildAt(index).descendants())
    }
}
