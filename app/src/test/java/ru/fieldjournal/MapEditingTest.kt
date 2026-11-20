package ru.fieldjournal

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
            root.icon(MapIcon.AREA).performClick()
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
            root.icon(MapIcon.DONE_AREA).performClick()
            chooseFirstType()
            assertTrue(activity.store.sites().none { it.id !in savedIds })
            confirmSave(activity)
            val saved = activity.store.sites().single { it.id !in savedIds }
            assertEquals(3, saved.vertices.size)
            activity.openSite(saved.id)
            activity.editGeometry(saved)
            assertEquals(3, map.draft.size)
            val first = saved.vertices.first()
            val moved = Coordinate(first.latitude - 0.000001, first.longitude)
            map.onVertexMove?.invoke(0, moved)
            root.icon(MapIcon.DONE_AREA).performClick()
            assertEquals(saved.vertices, activity.store.site(saved.id)?.vertices)
            confirmSave(activity)
            assertEquals(moved, activity.store.site(saved.id)?.vertices?.first())
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
            root.icon(MapIcon.COORDINATES).performClick()
            val centerDialog = ShadowAlertDialog.getLatestAlertDialog()
            val prompt = requireNotNull(centerDialog.window).decorView
            layout(prompt)
            prompt.text(map.center.display())
            assertTrue(prompt.text("Отмена").left > prompt.text("Ввести").left)
            prompt.text("Скопировать").performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(map.center.display(), clipboard.primaryClip?.getItemAt(0)?.text.toString())

            root.icon(MapIcon.POINT).performClick()
            layout(root)
            tap(map, 85f, 130f)
            val selected = requireNotNull(map.pointPreview)
            assertNotEquals(map.center, selected)
            root.icon(MapIcon.COORDINATES).performClick()
            val pointPrompt = ShadowAlertDialog.getLatestAlertDialog().window!!.decorView
            layout(pointPrompt)
            assertTrue(pointPrompt.text("Отмена").left > pointPrompt.text("Скопировать").left)
            pointPrompt.text("Скопировать").performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(selected.display(), clipboard.primaryClip?.getItemAt(0)?.text.toString())
            root.icon(MapIcon.SET_POINT).performClick()
            chooseFirstType()
            assertTrue(activity.store.sites().none { it.id !in savedIds })
            confirmSave(activity)
            assertEquals(selected, activity.store.sites().single { it.id !in savedIds }.anchor)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun layerSheetKeepsTheSelectedBaseAndOffersOfflineReferenceImport() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val root = activity.window.decorView
            val tiles = MapTiles.get(activity)
            root.icon(MapIcon.LAYERS).performClick()
            val sheet = requireNotNull(ShadowAlertDialog.getLatestAlertDialog().window).decorView
            sheet.text("Гибрид").performClick()
            assertEquals(MapLayer.HYBRID, tiles.layer)
            assertTrue(sheet.text("Гибрид").isSelected)
            sheet.text("Офлайн-режим").performClick()
            assertTrue(tiles.offline)
            assertFalse(sheet.text("Современные дороги и подписи").isEnabled)
            sheet.text("Подключить подписи").performClick()
            val request = shadowOf(activity).nextStartedActivityForResult
            assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun changingTabsCancelsPointAndAreaWhileKeepingNavigationAvailable() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val root = activity.window.decorView
            val map = root.descendants().filterIsInstance<FieldMap>().first()
            val savedIds = activity.store.sites().map(Site::id)
            root.icon(MapIcon.POINT).performClick()
            layout(root)
            tap(map, 85f, 140f)
            assertNotNull(map.pointPreview)
            root.text("Карта").performClick()
            assertTrue(map.editingGeometry)
            assertTrue(root.text("Объекты").isShown)
            root.text("Объекты").performClick()
            assertFalse(map.editingGeometry)
            assertNull(map.pointPreview)
            root.text("Карта").performClick()
            root.icon(MapIcon.AREA).performClick()
            layout(root)
            root.icon(MapIcon.ADD_VERTEX).performClick()
            tap(map, 70f, 200f)
            assertEquals(2, map.draft.size)
            assertTrue(root.text("Дневник").isShown)
            root.text("Дневник").performClick()
            assertFalse(map.editingGeometry)
            assertTrue(map.draft.isEmpty())
            root.text("Карта").performClick()
            assertTrue(root.icon(MapIcon.POINT).isShown)
            assertEquals(savedIds, activity.store.sites().map(Site::id))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun mapReadoutStaysTopRightAndUsesAltitudeOnlyForMeasuredCoordinates() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val root = activity.window.decorView
            val map = root.descendants().filterIsInstance<FieldMap>().first()
            val manager = shadowOf(activity.getSystemService(android.location.LocationManager::class.java))
            val provider = android.location.LocationManager.GPS_PROVIDER
            shadowOf(activity.application).grantPermissions(
                android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION
            )
            manager.setProviderEnabled(provider, true)
            manager.setLastKnownLocation(provider, android.location.Location(provider).apply {
                latitude = 59.0
                longitude = 33.0
                altitude = 123.0
                accuracy = 8f
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            })
            root.icon(MapIcon.LOCATION).performClick()
            layout(root)
            val point = Coordinate(59.0, 33.0)
            val readout = root.text(point.display())
            val mapPosition = IntArray(2).also(map::getLocationInWindow)
            val readoutPosition = IntArray(2).also(readout::getLocationInWindow)
            assertTrue(readoutPosition[1] - mapPosition[1] in 0..22)
            assertTrue(mapPosition[0] + map.width - (readoutPosition[0] + readout.width) in 0..22)
            assertTrue(root.text("Высота 123 м · ±8 м").isShown)

            root.icon(MapIcon.POINT).performClick()
            layout(root)
            val confirm = root.icon(MapIcon.SET_POINT)
            assertTrue(root.icon(MapIcon.CANCEL).left > confirm.left)
            tap(map, 70f, 200f)
            assertTrue(root.text(requireNotNull(map.pointPreview).display()).isShown)
            assertTrue(root.text("Высота —").isShown)
            root.icon(MapIcon.CANCEL).performClick()

            root.icon(MapIcon.AREA).performClick()
            root.icon(MapIcon.ADD_VERTEX).performClick()
            assertTrue(root.text(point.display()).isShown)
            assertTrue(root.text("Высота 123 м · ±8 м").isShown)
            assertTrue(root.descendants().filterIsInstance<TextView>().any { it.text.startsWith("Вершин 1 ·") })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun confirmSave(activity: MainActivity) {
        activity.closeEditor()
        ShadowAlertDialog.getLatestAlertDialog().window!!.decorView.text("Сохранить").performClick()
        shadowOf(Looper.getMainLooper()).idle()
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

    private fun View.icon(icon: MapIcon) =
        descendants().filterIsInstance<android.widget.ImageButton>().first { it.contentDescription == icon.description }

    private fun View.text(value: String) = descendants().filterIsInstance<TextView>().first { it.text == value }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) for (index in 0 until childCount) yieldAll(getChildAt(index).descendants())
    }
}
