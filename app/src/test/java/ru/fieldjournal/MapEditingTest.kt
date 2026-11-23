package ru.fieldjournal

import android.content.ClipboardManager
import android.location.Location
import android.location.LocationManager
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
            root.icon(FieldIcon.AREA).performClick()
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
            root.icon(FieldIcon.DONE_AREA).performClick()
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
            root.icon(FieldIcon.DONE_AREA).performClick()
            assertEquals(saved.vertices, activity.store.site(saved.id)?.vertices)
            confirmSave(activity)
            assertEquals(moved, activity.store.site(saved.id)?.vertices?.first())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun newAreaWarnsBeforeLinkingExistingObjectsAndKeepsNestedGroups() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val store = activity.store
            val root = activity.window.decorView
            val map = root.descendants().filterIsInstance<FieldMap>().first()
            fun boundary(south: Double, west: Double, north: Double, east: Double) = listOf(
                Coordinate(south, west), Coordinate(south, east), Coordinate(north, east), Coordinate(north, west)
            )
            val inner = Site(title = "Первый корпус", vertices = boundary(57.002, 40.002, 57.004, 40.004))
            val innerPoint = Site(title = "Вход", vertices = listOf(Coordinate(57.003, 40.003)), parentId = inner.id)
            val existing = Site(title = "Отдельная точка", vertices = listOf(Coordinate(57.006, 40.006)))
            val outside = Site(title = "За границей", vertices = boundary(57.008, 40.008, 57.015, 40.015))
            for (site in listOf(inner, innerPoint, existing, outside)) store.save(site)
            val known = store.sites().map(Site::id).toSet()

            root.icon(FieldIcon.AREA).performClick()
            for (point in boundary(57.0, 40.0, 57.01, 40.01)) map.onTap?.invoke(point, null)
            root.icon(FieldIcon.DONE_AREA).performClick()
            chooseFirstType()
            val notice = "После сохранения эта область станет родительской для объектов внутри неё. Объектов для привязки — 2."
            root.text("Будут связаны после сохранения")
            fun linkedCard(title: String): ViewGroup = root.text(title).parent.parent as ViewGroup
            for (child in listOf(inner, existing)) {
                assertTrue(linkedCard(child.title).descendants().any { it is SymbolView })
            }
            assertTrue(root.descendants().filterIsInstance<TextView>().none { it.text == outside.title })
            assertTrue(root.descendants().none { it.contentDescription == FieldIcon.PARENT_AREA.description })
            assertTrue(root.descendants().filterIsInstance<TextView>().none { it.text == "Самостоятельный объект" })
            activity.closeEditor()
            val confirmation = ShadowAlertDialog.getLatestAlertDialog().window!!.decorView
            confirmation.text(notice)
            assertNull(store.site(inner.id)?.parentId)
            assertNull(store.site(existing.id)?.parentId)
            assertEquals(inner.id, store.site(innerPoint.id)?.parentId)
            confirmation.text("Отмена").performClick()
            assertEquals(known, store.sites().map(Site::id).toSet())

            confirmSave(activity)
            val parent = store.sites().single { it.id !in known }
            assertEquals(setOf(inner.id, existing.id), store.children(parent.id).map(Site::id).toSet())
            assertEquals(inner.id, store.site(innerPoint.id)?.parentId)
            assertNull(store.site(outside.id)?.parentId)
            activity.openSite(parent.id)
            assertTrue(root.icon(FieldIcon.PARENT_AREA).isShown)
            assertTrue(linkedCard(inner.title).descendants().any { it is SymbolView })
            assertTrue(linkedCard(existing.title).descendants().any { it is SymbolView })
            activity.closeEditor()

            val point = Coordinate(57.0035, 40.0035)
            root.icon(FieldIcon.POINT).performClick()
            map.onTap?.invoke(point, null)
            root.icon(FieldIcon.SET_POINT).performClick()
            chooseFirstType()
            root.text("После сохранения в составе «${inner.title}»")
            assertTrue(root.descendants().none { it.contentDescription == FieldIcon.PARENT_AREA.description })
            confirmSave(activity)
            assertEquals(inner.id, store.sites().single { it.anchor == point && !it.area }.parentId)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun coordinatesCopyTheCenterThenThePointSelectedByTap() {
        MapTiles.get(org.robolectric.RuntimeEnvironment.getApplication()).coordinatesAtDevice = false
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val savedIds = activity.store.sites().map(Site::id).toSet()
            val root = activity.window.decorView
            val map = root.descendants().filterIsInstance<FieldMap>().first()
            val clipboard = activity.getSystemService(ClipboardManager::class.java)
            root.icon(FieldIcon.COORDINATES).performClick()
            val centerDialog = ShadowAlertDialog.getLatestAlertDialog()
            val prompt = requireNotNull(centerDialog.window).decorView
            layout(prompt)
            prompt.text(map.center.display())
            assertTrue(prompt.text("Отмена").left > prompt.text("Ввести").left)
            prompt.text("Скопировать").performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(map.center.display(), clipboard.primaryClip?.getItemAt(0)?.text.toString())

            root.icon(FieldIcon.POINT).performClick()
            layout(root)
            tap(map, 85f, 130f)
            val selected = requireNotNull(map.pointPreview)
            assertNotEquals(map.center, selected)
            root.icon(FieldIcon.COORDINATES).performClick()
            val pointPrompt = ShadowAlertDialog.getLatestAlertDialog().window!!.decorView
            layout(pointPrompt)
            assertTrue(pointPrompt.text("Отмена").left > pointPrompt.text("Скопировать").left)
            pointPrompt.text("Скопировать").performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(selected.display(), clipboard.primaryClip?.getItemAt(0)?.text.toString())
            root.icon(FieldIcon.SET_POINT).performClick()
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
            root.icon(FieldIcon.LAYERS).performClick()
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

    @Test fun movingTheMapDoesNotChangeDeviceCoordinatesOrTheGpsPointMeasurement() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val root = activity.window.decorView
            val map = root.descendants().filterIsInstance<FieldMap>().single()
            val savedIds = activity.store.sites().map(Site::id).toSet()
            val point = Coordinate(59.0, 33.0)
            val measuredAt = 1_700_000_000_000L
            val manager = shadowOf(activity.getSystemService(LocationManager::class.java))
            shadowOf(activity.application).grantPermissions(
                android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION
            )
            manager.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            manager.setLastKnownLocation(LocationManager.GPS_PROVIDER, Location(LocationManager.GPS_PROVIDER).apply {
                latitude = point.latitude
                longitude = point.longitude
                altitude = 123.0
                accuracy = 8f
                time = measuredAt
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            })
            root.icon(FieldIcon.LOCATION).performClick()
            val other = Coordinate(60.0, 34.0)
            map.moveTo(other)
            assertTrue(root.text("${point.display()} ±8 м · Выс. 123 м").isShown)
            val clipboard = activity.getSystemService(ClipboardManager::class.java)
            root.icon(FieldIcon.COORDINATES).performClick()
            ShadowAlertDialog.getLatestAlertDialog().window!!.decorView.text("Скопировать").performClick()
            assertEquals(point.display(), clipboard.primaryClip?.getItemAt(0)?.text.toString())

            root.icon(FieldIcon.COORDINATES).performClick()
            ShadowAlertDialog.getLatestAlertDialog().window!!.decorView.text("Центр карты").performClick()
            assertTrue(root.text("${other.display()} · Выс. —").isShown)
            ShadowAlertDialog.getLatestAlertDialog().window!!.decorView.text("Скопировать").performClick()
            assertEquals(other.display(), clipboard.primaryClip?.getItemAt(0)?.text.toString())

            root.icon(FieldIcon.POINT).performClick()
            root.icon(FieldIcon.GPS_POINT).performClick()
            assertEquals(point, map.pointPreview)
            root.icon(FieldIcon.SET_POINT).performClick()
            chooseFirstType()
            confirmSave(activity)
            val saved = activity.store.sites().single { it.id !in savedIds }
            assertEquals(point, saved.anchor)
            assertEquals(CoordinateOrigin.DEVICE, saved.coordinateOrigin)
            assertEquals(measuredAt, saved.measuredAt)
            assertEquals(123.0, saved.altitude)
            assertEquals(8.0, saved.accuracy)
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
            root.icon(FieldIcon.POINT).performClick()
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
            root.icon(FieldIcon.AREA).performClick()
            layout(root)
            root.icon(FieldIcon.ADD_VERTEX).performClick()
            tap(map, 70f, 200f)
            assertEquals(2, map.draft.size)
            assertTrue(root.text("Дневник").isShown)
            root.text("Дневник").performClick()
            assertFalse(map.editingGeometry)
            assertTrue(map.draft.isEmpty())
            root.text("Карта").performClick()
            assertTrue(root.icon(FieldIcon.POINT).isShown)
            assertEquals(savedIds, activity.store.sites().map(Site::id))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun fullScreenMapKeepsAnUnboxedSingleLineReadoutAndReceivesTouches() {
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
            root.icon(FieldIcon.LOCATION).performClick()
            layout(root)
            val point = Coordinate(59.0, 33.0)
            layout(root)
            val readout = root.text("${point.display()} ±8 м · Выс. 123 м")
            val mapPosition = IntArray(2).also(map::getLocationInWindow)
            val readoutPosition = IntArray(2).also(readout::getLocationInWindow)
            assertEquals(root.width, map.width)
            assertEquals(root.height, map.height)
            assertTrue(readoutPosition[1] - mapPosition[1] in 0..50)
            assertTrue(readoutPosition[0] + readout.paddingLeft - mapPosition[0] in 0..22)
            assertEquals(1, readout.lineCount)
            assertNull(readout.background)
            assertTrue(readout.contentDescription.toString().contains("±8 м"))
            assertTrue(map.footerInset > 0)
            assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                activity.packageManager.getActivityInfo(activity.componentName, 0).screenOrientation)
            assertTrue(root.icon(FieldIcon.LAYERS).elevation > 0f)
            assertTrue(android.graphics.Color.alpha(Palette.floatingSurface) in 1..254)

            val areaAction = root.icon(FieldIcon.AREA)
            val pointAction = root.icon(FieldIcon.POINT)
            val layers = root.icon(FieldIcon.LAYERS)
            val nav = root.text("Карта")
            fun onScreen(view: View) = IntArray(2).also(view::getLocationOnScreen)
            assertTrue(onScreen(pointAction)[1] > onScreen(layers)[1])
            assertTrue(onScreen(areaAction)[1] + areaAction.height <= onScreen(nav)[1])
            assertTrue(onScreen(areaAction)[0] + areaAction.width < onScreen(root.icon(FieldIcon.LOCATION))[0])
            assertTrue(pointAction.width >= 48 && areaAction.width >= 48)

            root.icon(FieldIcon.POINT).performClick()
            layout(root)
            val confirm = root.icon(FieldIcon.SET_POINT)
            assertTrue(root.icon(FieldIcon.CANCEL).left > confirm.left)
            tap(root, 70f, 200f)
            val preview = requireNotNull(map.pointPreview)
            assertTrue(root.text("${preview.display()} · Выс. —").isShown)
            root.icon(FieldIcon.CANCEL).performClick()

            root.icon(FieldIcon.AREA).performClick()
            root.icon(FieldIcon.ADD_VERTEX).performClick()
            assertTrue(root.text("${point.display()} ±8 м · Выс. 123 м").isShown)
            assertTrue(root.text("1 из 3 вершин").isShown)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun unlocatedCardCanDrawAnAreaAndUsesTheSameLayersPositionAsPoint() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val root = activity.window.decorView
            fun layersPosition(): List<Int> {
                layout(root, 320)
                return IntArray(2).also(root.icon(FieldIcon.LAYERS)::getLocationOnScreen).toList()
            }
            root.icon(FieldIcon.POINT).performClick()
            val pointPosition = layersPosition()
            root.icon(FieldIcon.CANCEL).performClick()

            val site = Site(title = "Монастырская ограда", vertices = emptyList())
            activity.store.save(site)
            activity.openSite(site.id)
            root.text("Установить на карте").performClick()
            val options = ShadowAlertDialog.getLatestAlertDialog().listView
            val area = (0 until options.count).first { options.adapter.getItem(it) == "Область" }
            options.performItemClick(options.adapter.getView(area, null, options), area, options.adapter.getItemId(area))
            assertEquals(pointPosition, layersPosition())

            val map = root.descendants().filterIsInstance<FieldMap>().single()
            val points = listOf(Coordinate(59.0, 33.0), Coordinate(59.0, 33.01), Coordinate(59.01, 33.01))
            points.forEach { map.onTap?.invoke(it, null) }
            root.icon(FieldIcon.DONE_AREA).performClick()
            assertTrue(activity.store.site(site.id)!!.vertices.isEmpty())
            confirmSave(activity)
            assertEquals(points, activity.store.site(site.id)!!.vertices)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Config(qualifiers = "w320dp-h640dp-mdpi")
    @Test fun narrowAreaToolbarKeepsCancelVisibleAndUndoesRemovalAndMove() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val root = controller.get().window.decorView
            root.icon(FieldIcon.AREA).performClick()
            layout(root, 320)
            val finish = root.icon(FieldIcon.DONE_AREA)
            val cancel = root.icon(FieldIcon.CANCEL)
            val undo = root.icon(FieldIcon.UNDO_VERTEX)
            assertFalse(finish.isEnabled)
            assertFalse(undo.isEnabled)
            assertTrue(cancel.width >= 48)
            assertTrue(IntArray(2).also(cancel::getLocationOnScreen)[0] + cancel.width <= root.width)

            val map = root.descendants().filterIsInstance<FieldMap>().single()
            val points = listOf(Coordinate(59.0, 33.0), Coordinate(59.0, 33.01), Coordinate(59.01, 33.01))
            points.forEach { map.onTap?.invoke(it, null) }
            assertTrue(finish.isEnabled)
            map.onVertexTap?.invoke(1)
            assertEquals(listOf(points[0], points[2]), map.draft)
            assertFalse(finish.isEnabled)
            root.text("Вернуть").performClick()
            assertEquals(points, map.draft)
            assertTrue(finish.isEnabled)
            map.onVertexMove?.invoke(1, Coordinate(59.0, 33.02))
            assertNotEquals(points, map.draft)
            undo.performClick()
            assertEquals(points, map.draft)
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

    private fun layout(view: View, width: Int = 360) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, 700)
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

    private fun View.icon(icon: FieldIcon) =
        descendants().filterIsInstance<android.widget.ImageButton>().first { it.contentDescription == icon.description }

    private fun View.text(value: String) = descendants().filterIsInstance<TextView>().first { it.text == value }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) for (index in 0 until childCount) yieldAll(getChildAt(index).descendants())
    }
}
