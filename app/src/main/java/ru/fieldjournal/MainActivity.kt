package ru.fieldjournal

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.HttpResponseCache
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ListView
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private enum class Tab(val title: String, val icon: NavIcon) {
        MAP("Карта", NavIcon.MAP), SITES("Объекты", NavIcon.PIN), NOTES("Дневник", NavIcon.NOTE), SETTINGS("Данные", NavIcon.DATA)
    }
    private enum class DrawMode { NONE, POINT, AREA, MOVE_POINT, MOVE_AREA }

    internal lateinit var store: JournalStore
        private set
    private lateinit var tiles: MapTiles
    private lateinit var gps: FieldLocation
    private lateinit var root: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var notice: TextView
    private lateinit var screen: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private lateinit var map: FieldMap
    private lateinit var mapPanel: LinearLayout
    private lateinit var mapStatus: TextView
    private lateinit var mapActions: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val hideNotice = Runnable { notice.visibility = View.GONE }
    private var tab = Tab.MAP
    private var previousTab = Tab.MAP
    private var editorId: String? = null
    private var editorIsNote = false
    private var noteOriginId: String? = null
    private var noteOriginScroll = 0
    private var editView: JournalEditor? = null
    private var draftToOpen: EditorDraft? = null
    private var geometryDraft: EditorDraft? = null
    private var draftToken = newId()
    private val draftFile get() = android.util.AtomicFile(File(cacheDir, "editor-$draftToken.json"))
    private var drawMode = DrawMode.NONE
    private var geometrySite: String? = null
    private var selectedPoint: Coordinate? = null
    private val vertices = mutableListOf<Coordinate>()
    private var keyboardVisible = false
    private var catalogIndex = 0
    private var catalogOffset = 0
    private var catalogView: ListView? = null
    private var editorScroll = 0
    private var search = ""
    private var filter: String? = null
    private var tracking = false
    private var centerOnFix = false
    private var fix: LocationFix? = null
    private var pendingOwner: String? = null
    private var pendingIsNote = false
    private var pendingCamera: String? = null
    private var pendingMap: MapLayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = JournalStore.get(this)
        tiles = MapTiles.get(this)
        if (HttpResponseCache.getInstalled() == null) runCatching {
            HttpResponseCache.install(File(cacheDir, "online-tiles"), 64L * 1024 * 1024)
        }
        gps = FieldLocation(this, ::receivedFix)
        savedInstanceState?.let {
            tab = Tab.valueOf(it.getString("tab", Tab.MAP.name))
            previousTab = Tab.valueOf(it.getString("previous", tab.name))
            editorId = it.getString("editor")
            editorIsNote = it.getBoolean("isNote")
            noteOriginId = it.getString("noteOriginId")
            noteOriginScroll = it.getInt("noteOriginScroll")
            drawMode = DrawMode.valueOf(it.getString("draw", DrawMode.NONE.name))
            it.getString("draftToken")?.takeIf(attachmentId::matches)?.let { token ->
                draftToken = token
                runCatching { EditorDraft.read(org.json.JSONObject(draftFile.readFully().toString(Charsets.UTF_8))) }
                    .onSuccess { draft ->
                        if (editorId == draft.id) draftToOpen = draft
                        else if (drawMode == DrawMode.MOVE_POINT || drawMode == DrawMode.MOVE_AREA) geometryDraft = draft
                    }
            }
            geometrySite = it.getString("geometrySite")
            it.getDoubleArray("selectedPoint")?.let { point -> selectedPoint = Coordinate(point[0], point[1]) }
            it.getDoubleArray("vertices")?.toList()?.chunked(2)?.forEach { vertex ->
                vertices.add(Coordinate(vertex[0], vertex[1]))
            }
            search = it.getString("search").orEmpty()
            catalogIndex = it.getInt("catalogIndex")
            catalogOffset = it.getInt("catalogOffset")
            editorScroll = it.getInt("editorScroll")
            filter = it.getString("filter")
            tracking = it.getBoolean("tracking")
            pendingOwner = it.getString("pendingOwner")
            pendingIsNote = it.getBoolean("pendingIsNote")
            pendingCamera = it.getString("pendingCamera")
            pendingMap = it.getString("pendingMap")?.let(MapLayer::valueOf)
        }

        root = vertical().apply {
            background = panel(Palette.paper, 0)
            isFocusableInTouchMode = true
        }
        header = vertical()
        notice = label("", 14f, android.graphics.Color.WHITE).apply {
            visibility = View.GONE
            background = panel(0xee27312e.toInt(), 20)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content = FrameLayout(this)
        nav = horizontal().apply { setPadding(dp(9), dp(7), dp(9), dp(7)); background = panel(Palette.white, 0, Palette.border) }
        root.addView(header, -1, -2)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(nav, -1, -2)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val edges = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                keyboardVisible = insets.isVisible(WindowInsets.Type.ime())
                updateNavigationVisibility()
                view.setPadding(edges.left, edges.top, edges.right, maxOf(edges.bottom, keyboard.bottom))
            } else {
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        screen = FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(-1, -1))
            addView(notice, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                bottomMargin = dp(24)
            })
            viewTreeObserver.addOnGlobalLayoutListener { positionNotice() }
        }
        setContentView(screen)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }
        root.requestApplyInsets()
        createMap()
        (lastNonConfigurationInstance as? EditorDraft)?.let { draft ->
            if (editorId == draft.id) draftToOpen = draft
            else if (drawMode == DrawMode.MOVE_POINT || drawMode == DrawMode.MOVE_AREA) geometryDraft = draft
        }
        render()
    }

    @Deprecated("Retains unsaved editor input across configuration changes")
    override fun onRetainNonConfigurationInstance(): Any? = editView?.snapshot() ?: geometryDraft

    override fun onSaveInstanceState(out: Bundle) {
        persistDraftForRestoration(out)
        out.putString("tab", tab.name)
        out.putString("previous", previousTab.name)
        out.putString("editor", editorId)
        out.putBoolean("isNote", editorIsNote)
        out.putString("noteOriginId", noteOriginId)
        out.putInt("noteOriginScroll", noteOriginScroll)
        out.putString("draw", drawMode.name)
        out.putString("geometrySite", geometrySite)
        selectedPoint?.let { out.putDoubleArray("selectedPoint", doubleArrayOf(it.latitude, it.longitude)) }
        out.putDoubleArray("vertices", vertices.flatMap { listOf(it.latitude, it.longitude) }.toDoubleArray())
        out.putString("search", search)
        rememberCatalogPosition()
        out.putInt("catalogIndex", catalogIndex)
        out.putInt("catalogOffset", catalogOffset)
        out.putInt("editorScroll", editView?.scrollPosition() ?: 0)
        out.putString("filter", filter)
        out.putBoolean("tracking", tracking)
        out.putString("pendingOwner", pendingOwner)
        out.putBoolean("pendingIsNote", pendingIsNote)
        out.putString("pendingCamera", pendingCamera)
        out.putString("pendingMap", pendingMap?.name)
        super.onSaveInstanceState(out)
    }

    private fun persistDraftForRestoration(out: Bundle) {
        val draft = editView?.takeIf(JournalEditor::hasUnsavedChanges)?.snapshot() ?: geometryDraft
        if (draft == null) return
        runCatching {
            val file = draftFile
            val output = file.startWrite()
            try {
                output.write(draft.json().toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
                out.putString("draftToken", draftToken)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
        }.onFailure { android.util.Log.w("FieldJournal", "Could not keep editor draft for recreation", it) }
    }

    override fun onResume() {
        super.onResume()
        if (tracking && this::gps.isInitialized) gps.start()
    }

    override fun onPause() {
        gps.stop()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        map.release()
        super.onDestroy()
    }

    @Deprecated("Uses the activity back stack for a map editor with a persistent draft")
    override fun onBackPressed() {
        when {
            editorId != null -> closeEditor()
            drawMode != DrawMode.NONE -> cancelDraw()
            tab != Tab.MAP -> switch(Tab.MAP)
            else -> super.onBackPressed()
        }
    }

    private fun render() {
        rememberCatalogPosition()
        catalogView = null
        editView = null
        content.removeAllViews()
        header.removeAllViews()
        nav.removeAllViews()
        if (editorId != null) {
            val id = requireNotNull(editorId)
            val draft = draftToOpen?.takeIf { it.id == id } ?: if (editorIsNote) {
                store.note(id)?.let { EditorDraft(note = it) }
            } else {
                store.site(id)?.let { EditorDraft(site = it) }
            }
            draftToOpen = null
            if (draft != null) {
                setHeader(if (draft.note != null) "Запись дневника" else "Карточка объекта",
                    action("✓", primary = true, description = "Сохранить и закрыть") {
                        closeEditor()
                    }.apply { textSize = 14f; minWidth = dp(48) }, true)
                updateNavigationVisibility()
                editView = JournalEditor(this, draft).also { editor ->
                    content.addView(editor)
                    editor.restoreScroll(editorScroll)
                }
                editorScroll = 0
                return
            }
            restoreEditorOrigin()
            if (editorId != null) {
                render()
                return
            }
        }
        updateNavigationVisibility()
        for (item in Tab.entries) {
            val button = navigationAction(item.title, item.icon, item == tab) { switch(item) }
            nav.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
        }
        when (tab) {
            Tab.MAP -> {
                setHeader("Полевой дневник", action("Слои") { chooseLayers() })
                content.addView(mapPanel)
                refreshMap()
                updateControls()
            }
            Tab.SITES -> {
                setHeader("Объекты", action("На карте") { switch(Tab.MAP) })
                showSites()
            }
            Tab.NOTES -> {
                setHeader("Дневник", action("+ Запись", true) { createNote(null) })
                showNotes()
            }
            Tab.SETTINGS -> {
                setHeader("Данные и карты", null)
                showSettings()
            }
        }
    }

    private fun updateNavigationVisibility() {
        nav.visibility = if (editorId != null || drawMode != DrawMode.NONE || keyboardVisible) View.GONE else View.VISIBLE
    }

    private fun setHeader(title: String, action: View?, back: Boolean = false) {
        val row = horizontal().apply { setPadding(dp(16), dp(8), dp(16), dp(8)) }
        if (back) row.addView(action("‹", description = "Назад") { closeEditor() }, dp(48), dp(48))
        val name = label(title, if (back) 19f else 24f, Palette.pine, true)
        if (android.os.Build.VERSION.SDK_INT >= 28) name.isAccessibilityHeading = true
        row.addView(name, LinearLayout.LayoutParams(0, -2, 1f).apply { if (back) leftMargin = dp(10) })
        if (action != null) row.addView(action)
        header.addView(row)
    }

    private fun switch(next: Tab) {
        if (drawMode != DrawMode.NONE && next != Tab.MAP) {
            confirm("Закрыть рисование?", "Добавленные вершины будут сброшены.") {
                if (geometryDraft != null) cancelDraw()
                else {
                    resetDraw()
                    tab = next
                    render()
                }
            }
            return
        }
        tab = next
        render()
    }

    private fun createMap() {
        map = FieldMap(this, tiles)
        map.onMove = { center, zoom ->
            tiles.setViewport(center, zoom)
            updateMapStatus()
        }
        map.onTap = { point, site ->
            when (drawMode) {
                DrawMode.AREA, DrawMode.MOVE_AREA -> addVertex(point)
                DrawMode.POINT, DrawMode.MOVE_POINT -> {
                    selectedPoint = point
                    updateMapDraft()
                }
                DrawMode.NONE -> site?.let { openSite(it.id) }
            }
        }
        map.onVertexTap = { index -> removeVertex(index) }
        map.onVertexMove = { index, point ->
            if (index in vertices.indices) {
                vertices[index] = point
                updateMapDraft()
            }
        }
        mapStatus = label("", 12f, Palette.pine, true).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(13), 0, dp(10), 0)
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(34)
        }
        mapActions = horizontal().apply { setPadding(dp(8), dp(4), dp(8), dp(8)) }
        val frame = FrameLayout(this).apply {
            addView(map, FrameLayout.LayoutParams(-1, -1))
            val zoom = vertical()
            for ((caption, delta) in listOf("+" to 1.0, "−" to -1.0)) {
                zoom.addView(action(caption, description = if (delta > 0) "Приблизить" else "Отдалить") {
                    map.zoomBy(delta)
                }.apply { textSize = 22f }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { bottomMargin = dp(4) })
            }
            addView(zoom, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(10)
                marginEnd = dp(10)
            })
            addView(locationAction { requestLocation() }, FrameLayout.LayoutParams(dp(48), dp(48),
                Gravity.BOTTOM or Gravity.END).apply {
                bottomMargin = dp(32)
                marginEnd = dp(10)
            })
        }
        mapPanel = vertical().apply {
            addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
            val bottom = vertical().apply {
                background = panel(Palette.paper, 0)
                addView(mapStatus)
                addView(mapActions)
            }
            addView(bottom, -1, -2)
        }
    }

    private fun refreshMap() {
        map.sites = store.sites().filter { filter == null || it.type == filter }
        map.locate = fix
        updateMapDraft()
    }

    private fun updateMapDraft() {
        map.editingGeometry = drawMode != DrawMode.NONE
        map.draft = vertices.toList()
        map.pointPreview = selectedPoint
        map.crosshair = drawMode != DrawMode.NONE && selectedPoint == null
        updateMapStatus()
    }

    private fun updateMapStatus() {
        if (!this::mapStatus.isInitialized) return
        val status = when (drawMode) {
            DrawMode.NONE -> fix?.let {
                "${it.point.display()}  ±${it.accuracy.roundToInt()} м · ${it.source}"
            } ?: "${map.center.display()}  •  ${if (tiles.offline) "Офлайн" else tiles.layer.title}"
            DrawMode.POINT, DrawMode.MOVE_POINT -> "Точка  ${(selectedPoint ?: map.center).display()}"
            DrawMode.AREA, DrawMode.MOVE_AREA -> "Граница  •  ${vertices.size} вершин  •  ${areaLabel(areaSquareMeters(vertices))}"
        }
        mapStatus.text = status
    }

    private fun addVertex(point: Coordinate) {
        if (vertices.size >= 2000) return message("Достигнут предел: 2000 вершин")
        if (point in vertices) return message("Такая вершина уже добавлена")
        vertices.add(point)
        updateMapDraft()
    }

    private fun removeVertex(index: Int) {
        if (index !in vertices.indices) return
        vertices.removeAt(index)
        updateMapDraft()
    }

    private fun updateControls() {
        updateNavigationVisibility()
        updateMapStatus()
        mapActions.removeAllViews()
        fun control(text: String, primary: Boolean = false, description: String? = null, click: () -> Unit) {
            val button = action(text, primary, description, click).apply {
                textSize = 13f
                setPadding(dp(3), 0, dp(3), 0)
            }
            mapActions.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        when (drawMode) {
            DrawMode.NONE -> {
                control("+ Точка", true) { beginDraw(DrawMode.POINT) }
                control("+ Область") { beginDraw(DrawMode.AREA) }
                control("Координаты") { showCoordinates(map.center, allowEntry = true) }
            }
            DrawMode.POINT, DrawMode.MOVE_POINT -> {
                control("Координаты") { showCoordinates(selectedPoint ?: map.center) }
                control("Установить", true) {
                    val point = selectedPoint ?: map.center
                    saveGeometry(listOf(point), recentFix()?.takeIf { it.point == point })
                }
                control("Отмена") { cancelDraw() }
            }
            DrawMode.AREA, DrawMode.MOVE_AREA -> {
                control("Добавить", description = "Добавить вершину в центр карты") { addVertex(map.center) }
                control("Удалить", description = "Удалить последнюю вершину") { removeVertex(vertices.lastIndex) }
                control("Готово", true) {
                    val error = polygonError(vertices)
                    if (error == null) saveGeometry(vertices.toList()) else message(error.message)
                }
                control("Отмена") { cancelDraw() }
            }
        }
    }

    private fun beginDraw(mode: DrawMode) {
        drawMode = mode
        refreshMap()
        updateControls()
        message(if (mode == DrawMode.AREA || mode == DrawMode.MOVE_AREA)
            "Касание добавляет или удаляет вершину. Удерживайте её для перетаскивания"
        else "Коснитесь места и нажмите «Установить»")
    }

    private fun showCoordinates(point: Coordinate, allowEntry: Boolean = false) {
        val coordinates = point.display()
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (allowEntry) "Координаты центра карты" else "Координаты точки")
            .setMessage("$coordinates\nWGS 84 · широта, долгота")
            .setNegativeButton("Скопировать") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Координаты WGS 84", coordinates))
                if (android.os.Build.VERSION.SDK_INT < 33) message("Координаты скопированы")
            }
            .setPositiveButton("Отмена", null)
        if (allowEntry) dialog.setNeutralButton("Ввести") { _, _ -> manualPoint() }
        dialog.show()
    }

    private fun manualPoint() {
        val layout = vertical().apply { setPadding(dp(20), dp(12), dp(20), 0) }
        val lat = edit("Широта", map.center.latitude.toString())
        val lon = edit("Долгота", map.center.longitude.toString())
        for (input in listOf(lat, lon)) {
            // Text input also accepts decimal commas on Russian keyboards.
            layout.addView(input, -1, -2)
            layout.addSpace(8)
        }
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Координаты WGS 84").setView(layout)
        builder.setNegativeButton("Создать точку", null).setPositiveButton("Отмена", null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                submitManualPoint(dialog, lat.text.toString(), lon.text.toString())
            }
        }
        dialog.show()
    }

    private fun submitManualPoint(dialog: AlertDialog, latitude: String, longitude: String) {
        val point = runCatching {
            Coordinate(latitude.trim().replace(',', '.').toDouble(), longitude.trim().replace(',', '.').toDouble())
        }.getOrElse {
            message("Широта от −90 до 90°, долгота от −180 до 180°")
            return
        }
        dialog.dismiss()
        map.moveTo(point, maxOf(15.0, map.zoom))
        saveGeometry(listOf(point))
    }

    private fun resetDraw() {
        dismissNotice()
        drawMode = DrawMode.NONE
        vertices.clear()
        geometrySite = null
        geometryDraft = null
        selectedPoint = null
    }

    private fun cancelDraw() {
        val original = geometryDraft
        val editing = geometrySite
        resetDraw()
        if (original != null) {
            openDraft(original)
        } else if (editing != null) {
            openSite(editing)
        } else {
            refreshMap()
            updateControls()
        }
    }

    private fun saveGeometry(points: List<Coordinate>, fix: LocationFix? = null) {
        val original = geometryDraft ?: geometrySite?.let(store::site)?.let { EditorDraft(site = it) }
        if (original != null) {
            val site = requireNotNull(original.site)
            val updated = if (site.vertices == points) site else site.copy(
                vertices = points, holes = emptyList(), accuracy = fix?.accuracy, altitude = fix?.altitude
            )
            resetDraw()
            openDraft(original.copy(site = updated, dirty = original.dirty || updated != site))
            return
        }
        chooseType("Условный знак объекта") { type ->
            val site = Site(title = type.title, type = type.id, vertices = points, accuracy = fix?.accuracy, altitude = fix?.altitude)
            resetDraw()
            previousTab = tab
            openDraft(EditorDraft(site = site, isNew = true))
        }
    }

    internal fun editGeometry(site: Site) {
        val draft = editView?.snapshot() ?: EditorDraft(site = site)
        detachEditor()
        tab = Tab.MAP
        render()
        map.show(site)
        geometryDraft = draft
        geometrySite = site.id
        if (site.area) vertices.addAll(site.vertices) else selectedPoint = site.anchor
        beginDraw(if (site.area) DrawMode.MOVE_AREA else DrawMode.MOVE_POINT)
    }

    internal fun showOnMap(site: Site) {
        confirmBeforeLeaving {
            draftFile.delete()
            detachEditor()
            tab = Tab.MAP
            filter = null
            render()
            map.post { map.show(site) }
        }
    }

    internal fun chooseType(title: String, selection: (ObjectType) -> Unit) {
        val list = vertical().apply { setPadding(dp(12), dp(4), dp(12), dp(6)) }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(list) })
            .setPositiveButton("Отмена", null).create()
        for (type in objectTypes) {
            val row = horizontal().apply {
                minimumHeight = dp(49)
                setPadding(dp(8), 0, dp(8), 0)
                addView(SymbolView(this@MainActivity, type), dp(35), dp(35))
                addView(label(type.title, 16f).apply { setPadding(dp(12), 0, 0, 0) })
                isFocusable = true
                setOnClickListener { dialog.dismiss(); selection(type) }
            }
            list.addView(row, -1, -2)
        }
        dialog.show()
    }

    private fun requestLocation() {
        if (!gps.permitted()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION)
            return
        }
        centerOnFix = true
        tracking = true
        if (!gps.start()) {
            tracking = false
            centerOnFix = false
            AlertDialog.Builder(this).setTitle("Геолокация выключена")
                .setMessage("Включите определение местоположения в настройках телефона.")
                .setPositiveButton("Закрыть", null).setNegativeButton("Настройки") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }.show()
        } else if (centerOnFix) {
            message("Определяю местоположение…")
        }
    }

    private var fixClock = 0L
    private fun recentFix(): LocationFix? =
        fix?.takeIf { android.os.SystemClock.elapsedRealtime() - fixClock <= 45000 }

    private fun receivedFix(value: LocationFix) {
        fix = value
        fixClock = value.elapsedMs
        map.locate = value
        if (centerOnFix) {
            map.moveTo(value.point, maxOf(map.zoom, if (value.accuracy < 200) 16.0 else 12.0))
            centerOnFix = false
        }
        updateMapStatus()
        val timestamp = fixClock
        handler.postDelayed({
            if (fixClock == timestamp) {
                fix = null
                map.locate = null
                updateMapStatus()
            }
        }, (45000 - (android.os.SystemClock.elapsedRealtime() - timestamp)).coerceAtLeast(0))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == LOCATION) {
            if (gps.permitted()) requestLocation() else message("Разрешите геолокацию в настройках приложения")
        }
    }

    internal fun openLink(value: String) {
        val uri = Uri.parse(value)
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) return message("Нужна ссылка HTTP или HTTPS")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure { message("На телефоне нет приложения для открытия ссылки") }
    }

    private fun chooseLayers() {
        val options = MapLayer.entries.map { it.title }.toTypedArray()
        val mode = if (tiles.offline) "Сейчас офлайн" else "Сейчас онлайн"
        AlertDialog.Builder(this).setTitle("Подложка карты · $mode")
            .setSingleChoiceItems(options, tiles.layer.ordinal) { dialog, selection ->
                tiles.layer = MapLayer.entries[selection]
                dialog.dismiss()
                map.refresh()
                updateMapStatus()
            }.setNeutralButton("Настроить") { _, _ -> switch(Tab.SETTINGS) }
            .setNegativeButton(if (tiles.offline) "В онлайн" else "В офлайн") { _, _ -> toggleOffline() }
            .setPositiveButton("Закрыть", null).show()
    }

    private fun toggleOffline() {
        tiles.offline = !tiles.offline
        map.refresh()
        if (tab == Tab.SETTINGS) render() else updateMapStatus()
    }

    private fun listBody(): LinearLayout = vertical().also { list ->
        list.setPadding(dp(16), dp(8), dp(16), dp(20))
        content.addView(ScrollView(this).apply { isFillViewport = true; addView(list) })
    }

    private fun rememberCatalogPosition() {
        catalogView?.let {
            catalogIndex = it.firstVisiblePosition
            catalogOffset = it.getChildAt(0)?.top ?: 0
        }
    }

    private fun resetCatalogPosition() {
        catalogView = null
        catalogIndex = 0
        catalogOffset = 0
    }

    private fun showSites() {
        val panel = vertical().apply { setPadding(dp(14), dp(5), dp(14), dp(8)) }
        val query = edit("Поиск по названию и описанию", search)
        val controls = horizontal()
        controls.addView(action(filter?.let { objectType(it).title } ?: "Все типы") { chooseFilter() },
            LinearLayout.LayoutParams(0, -2, 1f))
        if (filter != null) controls.addView(action("Сбросить") {
            filter = null
            resetCatalogPosition()
            render()
        })
        val count = label("", 14f, Palette.muted).apply {
            setPadding(dp(3), dp(7), dp(3), dp(7))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val results = ListView(this).apply {
            divider = null
            clipToPadding = false
        }
        catalogView = results
        val emptyState = vertical()
        val resultsPanel = FrameLayout(this).apply {
            addView(results, FrameLayout.LayoutParams(-1, -1))
            addView(emptyState, FrameLayout.LayoutParams(-1, -2))
        }
        val catalog = store.sites().filter { filter == null || it.type == filter }
        var matches = catalog
        val adapter = object : BaseAdapter() {
            override fun getCount() = matches.size
            override fun getItem(position: Int) = matches[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return ((convertView as? CatalogRow) ?: CatalogRow(this@MainActivity)).apply {
                    bind(getItem(position))
                }
            }
        }
        results.adapter = adapter
        results.setOnItemClickListener { _, _, position, _ -> openSite(adapter.getItem(position).id) }
        fun showMatches() {
            val input = search.trim()
            matches = if (input.isEmpty()) catalog else catalog.filter { site ->
                listOf(site.title, objectType(site.type).title, site.period, site.culture,
                    site.description, site.inventory, site.sources).any { it.contains(input, true) }
            }
            adapter.notifyDataSetChanged()
            count.text = "Объекты · ${matches.size}"
            emptyState.removeAllViews()
            if (matches.isEmpty()) empty(emptyState, "Объекты не найдены",
                if (input.isEmpty() && filter == null) "Поставьте точку на карте или обведите границы участка."
                else "Измените поиск или фильтр по типу.")
        }
        query.watch {
            search = it
            showMatches()
            results.setSelection(0)
        }
        panel.addView(query, -1, -2)
        panel.addSpace(6)
        panel.addView(controls, -1, -2)
        panel.addView(count)
        panel.addView(resultsPanel, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(panel)
        root.requestFocus()
        showMatches()
        results.setSelectionFromTop(catalogIndex, catalogOffset)
    }

    private fun chooseFilter() {
        val names = arrayOf("Все типы") + objectTypes.map(ObjectType::title).toTypedArray()
        AlertDialog.Builder(this).setTitle("Фильтр каталога и карты").setItems(names) { _, choice ->
            filter = objectTypes.getOrNull(choice - 1)?.id
            resetCatalogPosition()
            render()
        }.setPositiveButton("Отмена", null).show()
    }

    private fun showNotes() {
        val list = listBody()
        val sites = store.sites().associateBy(Site::id)
        val notes = store.notes().sortedByDescending(Note::created)
        if (notes.isEmpty()) empty(list, "Дневник пока пуст", "Создайте запись о выезде, наблюдении или работе с источниками.")
        for (note in notes) {
            card(list, note.title, listOfNotNull(dateLabel(note.created), sites[note.siteId]?.title).joinToString(" · ")) { openNote(note.id) }
        }
    }

    private fun empty(list: LinearLayout, title: String, detail: String) {
        val box = vertical().apply {
            background = panel(Palette.pale)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            addView(label(title, 18f, Palette.pine, true))
            addSpace(5)
            addView(label(detail, 14f, Palette.muted))
        }
        list.addView(box, -1, -2)
    }

    private fun card(list: LinearLayout, title: String, detail: String, icon: View? = null, click: () -> Unit) {
        val row = horizontal().apply {
            background = panel(Palette.white, 13, Palette.border)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            minimumHeight = dp(64)
            val description = vertical()
            description.addView(label(title, 16f, Palette.ink, true))
            description.addView(label(detail, 13f, Palette.muted))
            if (icon != null) {
                addView(icon, dp(39), dp(39))
                description.setPadding(dp(10), 0, dp(6), 0)
            }
            addView(description, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("›", 22f, Palette.green))
            isFocusable = true
            setOnClickListener { click() }
        }
        list.addView(row, -1, -2)
        list.addSpace(8)
    }

    private fun showSettings() {
        val list = listBody()
        fun heading(title: String) {
            list.addSpace(8)
            list.addView(label(title, 19f, Palette.pine, true).apply { if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true })
            list.addSpace(8)
        }
        heading("Картография")
        card(list, if (tiles.offline) "Режим · офлайн" else "Режим · онлайн",
            if (tiles.offline) "Нажмите, чтобы подключить онлайн-подложки" else "Нажмите для работы с локальными подложками") { toggleOffline() }
        for (layer in MapLayer.entries) {
            val info = tiles.info(layer)
            val detail = info?.let { "${it.name} · масштаб ${it.minZoom}–${it.maxZoom}" } ?: "Подключить растровый файл MBTiles"
            card(list, "Офлайн · ${layer.title}", detail) {
                if (info == null) pickMap(layer) else {
                    AlertDialog.Builder(this).setTitle(info.name)
                        .setPositiveButton("Отмена", null)
                        .setNeutralButton("Заменить файл") { _, _ -> pickMap(layer) }
                        .setNegativeButton("Открыть карту") { _, _ -> activateMap(layer, info) }.show()
                }
            }
        }
        card(list, "Свой онлайн-источник XYZ",
            if (tiles.customUrl.isBlank()) "Карта по умолчанию · OpenStreetMap" else tiles.customCredit) { customSource() }
        heading("Обмен и резервные копии")
        card(list, "Импорт ZIP или GeoJSON", "Существующие записи с теми же ID сохраняются") { pickDocument(IMPORT, "*/*") }
        card(list, "Экспорт резервной копии", "ZIP · карточки, дневник и вложения") { exportDocument(BACKUP, "application/zip", "field-journal-${shortDate()}.zip") }
        card(list, "Экспорт объектов GeoJSON", "WGS 84 · точки, контуры и поля карточек") { exportDocument(GEOJSON, "application/geo+json", "field-journal-${shortDate()}.geojson") }
        list.addView(label("Подтверждённые изменения хранятся в локальной базе. Карты MBTiles хранятся отдельно от резервных копий.", 13f, Palette.muted))
    }

    private fun shortDate() = java.time.LocalDate.now().toString()

    private fun customSource() {
        val box = vertical().apply { setPadding(dp(20), dp(12), dp(20), 0) }
        val url = edit("https://…/{z}/{x}/{y}.png", tiles.customUrl)
        val credit = edit("Атрибуция источника", tiles.customCredit)
        box.addView(url)
        box.addSpace(8)
        box.addView(credit)
        val dialog = AlertDialog.Builder(this).setTitle("Онлайн-карта XYZ")
            .setView(box).setPositiveButton("Отмена", null).setNegativeButton("Применить", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                val template = url.text.toString().trim()
                val attribution = credit.text.toString().trim()
                if (!MapTiles.validTemplate(template) || (template.isNotEmpty() && attribution.isEmpty())) {
                    message("Укажите HTTPS-шаблон с {z}, {x}, {y} и атрибуцию")
                    return@setOnClickListener
                }
                tiles.customUrl = template
                tiles.customCredit = attribution
                map.refresh()
                dialog.dismiss()
                render()
            }
        }
        dialog.show()
    }

    private fun pickMap(layer: MapLayer) {
        pendingMap = layer
        pickDocument(MBTILES, "*/*")
    }

    private fun activateMap(layer: MapLayer, info: OfflineInfo) {
        tiles.offline = true
        tiles.layer = layer
        map.refresh()
        tab = Tab.MAP
        render()
        info.center?.let { map.moveTo(it, (info.minZoom + 1.0).coerceAtMost(info.maxZoom.toDouble())) }
        updateMapStatus()
    }

    private fun pickDocument(request: Int, mime: String) {
        launch(Intent(Intent.ACTION_OPEN_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE), request)
    }

    private fun exportDocument(request: Int, mime: String, name: String) {
        launch(Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE, name), request)
    }

    private fun launch(intent: Intent, request: Int) {
        runCatching { startActivityForResult(intent, request) }.onFailure { message("На устройстве нет подходящего приложения") }
    }

    internal fun openSite(id: String) {
        if (editorId == null) previousTab = tab
        noteOriginId = null
        noteOriginScroll = 0
        editorId = id
        editorIsNote = false
        render()
    }

    private fun openDraft(draft: EditorDraft) {
        draftToOpen = draft
        editorId = draft.id
        editorIsNote = draft.note != null
        render()
    }

    internal fun openRelatedNote(id: String) = openNote(id)

    private fun openNote(id: String) {
        confirmBeforeLeaving {
            openNoteFromCurrentScreen(id)
        }
    }

    private fun openNoteFromCurrentScreen(id: String, draft: EditorDraft? = null) {
        if (editorId == null) previousTab = tab
        noteOriginId = editorId?.takeUnless { editorIsNote }
        noteOriginScroll = if (noteOriginId != null) editView?.scrollPosition() ?: 0 else 0
        draftFile.delete()
        if (draft != null) {
            openDraft(draft)
        } else {
            editorId = id
            editorIsNote = true
            render()
        }
    }

    internal fun createNote(siteId: String?) {
        confirmBeforeLeaving {
            val note = Note(title = "Полевое наблюдение", siteId = siteId)
            openNoteFromCurrentScreen(note.id, EditorDraft(note = note, isNew = true))
        }
    }

    private fun confirmBeforeLeaving(after: () -> Unit) {
        val editor = editView
        if (editor == null || !editor.hasUnsavedChanges) {
            after()
            return
        }
        val question = when {
            !editor.isNew -> "Сохранить изменения?"
            editorIsNote -> "Сохранить новую запись?"
            else -> "Сохранить новый объект?"
        }
        AlertDialog.Builder(this).setTitle(question)
            .setNegativeButton("Сохранить") { _, _ ->
                if (editor.saveConfirmed()) {
                    after()
                    message("Сохранено")
                }
            }
            .setNeutralButton("Не сохранять") { _, _ ->
                editor.discardDraft()
                after()
            }
            .setPositiveButton("Отмена", null)
            .show()
    }

    private fun detachEditor() {
        editView = null
        editorId = null
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(root.windowToken, 0)
    }

    internal fun closeEditor() = confirmBeforeLeaving { finishEditor() }

    internal fun closeDeletedEditor() {
        editView?.discardDraft()
        finishEditor()
    }

    private fun finishEditor() {
        draftFile.delete()
        detachEditor()
        dismissNotice()
        restoreEditorOrigin()
        render()
    }

    private fun restoreEditorOrigin() {
        editorId = noteOriginId?.takeIf { store.site(it) != null }
        editorIsNote = false
        editorScroll = if (editorId != null) noteOriginScroll else 0
        noteOriginId = null
        noteOriginScroll = 0
        tab = previousTab
    }

    internal fun pickAttachment(owner: String, note: Boolean, photoOnly: Boolean) {
        pendingOwner = owner
        pendingIsNote = note
        pickDocument(ATTACH, if (photoOnly) "image/*" else "*/*")
    }

    internal fun capturePhoto(owner: String, note: Boolean) {
        val id = newId()
        val photo = Attachment(id, "Фото ${shortDate()}.jpg", "image/jpeg")
        val uri = attachmentUri(this, photo, true)
        pendingOwner = owner
        pendingIsNote = note
        pendingCamera = id
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = ClipData.newRawUri("photo", uri)
        }
        launch(intent, CAMERA)
    }

    internal fun openAttachment(attachment: Attachment) {
        if (attachment.mime.startsWith("image/")) {
            val file = store.attachmentFile(attachment.id)
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.path, options)
            options.inJustDecodeBounds = false
            options.inSampleSize = 1
            while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2
            android.graphics.BitmapFactory.decodeFile(file.path, options)?.let { bitmap ->
                val image = android.widget.ImageView(this).apply {
                    adjustViewBounds = true
                    maxHeight = dp(530)
                    setImageBitmap(bitmap)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                }
                AlertDialog.Builder(this).setTitle(attachment.name).setView(image).setPositiveButton("Закрыть", null).show()
                return
            }
        }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(attachmentUri(this, attachment), attachment.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(intent) }.onFailure { message("На телефоне нет приложения для этого файла") }
    }

    @Deprecated("Receives results for Android system document and camera intents")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        when (requestCode) {
            CAMERA -> handleCamera(resultCode)
            ATTACH -> {
                val owner = pendingOwner
                val note = pendingIsNote
                pendingOwner = null
                if (resultCode != RESULT_OK || uri == null || owner == null) return
                withIo("Копирование вложения", abandoned = { record: Attachment ->
                    store.attachmentFile(record.id).delete()
                }, work = {
                    requireNotNull(contentResolver.openInputStream(uri)).use {
                        store.addAttachment(it, documentName(uri), contentResolver.getType(uri) ?: "application/octet-stream")
                    }
                }) { record ->
                    stageMedia(owner, note, record)
                }
            }
            MBTILES -> {
                val layer = pendingMap
                pendingMap = null
                if (resultCode != RESULT_OK || uri == null || layer == null) return
                withIo("Установка офлайн-карты", {
                    requireNotNull(contentResolver.openInputStream(uri)).use { tiles.install(layer, it) }
                }) {
                    activateMap(layer, it)
                    message("Офлайн-карта установлена")
                }
            }
            IMPORT -> {
                if (resultCode != RESULT_OK || uri == null) return
                withIo("Импорт данных", {
                    requireNotNull(contentResolver.openInputStream(uri)).buffered().use { input ->
                        input.mark(4)
                        val zip = input.read() == 'P'.code && input.read() == 'K'.code
                        input.reset()
                        if (zip) store.importBackup(input) else store.importGeoJson(input)
                    }
                }) { result ->
                    render()
                    AlertDialog.Builder(this).setTitle("Импорт завершён")
                        .setMessage("Объектов добавлено: ${result.sites}\nЗаписей дневника: ${result.notes}\nСовпадений пропущено: ${result.skipped}")
                        .setPositiveButton("Готово", null).show()
                }
            }
            BACKUP, GEOJSON -> {
                if (resultCode != RESULT_OK || uri == null) return
                withIo("Экспорт данных", {
                    val openOutput = { requireNotNull(contentResolver.openOutputStream(uri, "wt")) }
                    if (requestCode == BACKUP) store.exportBackup(openOutput)
                    else openOutput().use(store::exportGeoJson)
                }) { message("Файл сохранён") }
            }
        }
    }

    private fun handleCamera(resultCode: Int) {
        val id = pendingCamera
        val owner = pendingOwner
        pendingCamera = null
        pendingOwner = null
        if (id == null || owner == null) return
        val file = store.attachmentFile(id)
        val record = Attachment(id, "Фото ${shortDate()}.jpg", "image/jpeg")
        revokeUriPermission(attachmentUri(this, record, true), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (resultCode == RESULT_OK && file.length() in 1..JournalStore.MAX_ATTACHMENT) stageMedia(owner, pendingIsNote, record)
        else {
            file.delete()
            if (resultCode == RESULT_OK) message("Камера вернула пустой снимок или файл слишком велик")
        }
    }

    private fun documentName(uri: Uri) = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty()

    private fun stageMedia(id: String, note: Boolean, record: Attachment) {
        val editor = editView
        if (editor != null && editorId == id && editorIsNote == note) {
            editor.stageAttachment(record)
            message("Вложение добавлено в черновик")
        } else {
            store.attachmentFile(record.id).delete()
        }
    }

    private fun <T> withIo(title: String, work: () -> T, abandoned: (T) -> Unit = {}, success: (T) -> Unit) {
        val dialog = AlertDialog.Builder(this).setTitle(title).setMessage("Подождите…").setCancelable(false).show()
        io.execute {
            val result = runCatching(work)
            Handler(Looper.getMainLooper()).post {
                if (isDestroyed || isFinishing) {
                    result.onSuccess(abandoned)
                    return@post
                }
                dialog.dismiss()
                result.onSuccess(success).onFailure { error ->
                    AlertDialog.Builder(this).setTitle("Операция не завершена")
                        .setMessage(error.message ?: "Не удалось прочитать или сохранить данные")
                        .setPositiveButton("Закрыть", null).show()
                }
            }
        }
    }

    internal fun confirm(title: String, text: String, yes: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("Отмена", null)
            .setNegativeButton("Подтвердить") { _, _ -> yes() }.show()
    }

    internal fun message(text: String) {
        handler.removeCallbacks(hideNotice)
        notice.text = text
        notice.visibility = View.VISIBLE
        positionNotice()
        handler.postDelayed(hideNotice, 3000)
    }

    private fun positionNotice() {
        if (notice.visibility != View.VISIBLE || screen.height == 0) return
        val anchor = when {
            editorId == null && tab == Tab.MAP && mapActions.isShown -> mapActions
            nav.isShown -> nav
            else -> null
        }
        val bottom = if (anchor == null) {
            root.paddingBottom + dp(24)
        } else {
            val location = IntArray(2).also(anchor::getLocationInWindow)
            val host = IntArray(2).also(screen::getLocationInWindow)
            screen.height - (location[1] - host[1]) + dp(12)
        }
        val params = notice.layoutParams as FrameLayout.LayoutParams
        if (params.bottomMargin != bottom) {
            params.bottomMargin = bottom
            notice.layoutParams = params
        }
    }

    private fun dismissNotice() {
        handler.removeCallbacks(hideNotice)
        hideNotice.run()
    }

    companion object {
        private const val LOCATION = 10
        private const val ATTACH = 11
        private const val CAMERA = 12
        private const val MBTILES = 13
        private const val IMPORT = 14
        private const val BACKUP = 15
        private const val GEOJSON = 16
        private val io = Executors.newSingleThreadExecutor()
    }
}
