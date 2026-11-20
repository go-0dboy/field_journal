package ru.fieldjournal

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.http.HttpResponseCache
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private enum class Tab(val title: String, val icon: FieldIcon) {
        MAP("Карта", FieldIcon.MAP), SITES("Объекты", FieldIcon.POINT), NOTES("Дневник", FieldIcon.NOTE), SETTINGS("Данные", FieldIcon.DATA)
    }
    private enum class DiaryPage(val title: String, val icon: FieldIcon) {
        RECORDS("Записи", FieldIcon.NOTE), TRIPS("Выезды", FieldIcon.TRIP), DRAFTS("Черновики", FieldIcon.DRAFT)
    }
    private var diaryPage = DiaryPage.RECORDS
    private var diaryTripId: String? = null
    private var filterTripNotes: String? = null
    private var drawingParentId: String? = null
    private var hiddenTripId: String? = null
    private enum class DrawMode { NONE, POINT, AREA, MOVE_POINT, MOVE_AREA }
    private enum class LocationAction { CENTER_MAP, SELECT_POINT, READOUT }

    private class IoTask(val title: String) {
        var onReady: (() -> Unit)? = null
        var deliver: ((MainActivity) -> Unit)? = null
        private var discard: (() -> Unit)? = null
        private var abandoned = false

        fun complete(deliver: (MainActivity) -> Unit, discard: () -> Unit) {
            if (abandoned) {
                discard()
            } else {
                this.deliver = deliver
                this.discard = discard
                onReady?.invoke()
            }
        }

        fun abandon() {
            abandoned = true
            discard?.invoke()
        }
    }

    private data class AttachmentTarget(val draftToken: String, val owner: String, val note: Boolean)
    private data class RetainedState(val draft: EditorDraft?, val task: IoTask?, val raster: PreparedRaster?)

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
    private lateinit var mapPanel: FrameLayout
    private lateinit var mapCoordinates: TextView
    private lateinit var mapArea: TextView
    private lateinit var mapComparisonStatus: TextView
    private lateinit var mapTripStatus: TextView
    private lateinit var mapZoomButtons: View
    private lateinit var mapLocationButton: View
    private lateinit var statusBarScrim: View
    private lateinit var navigationBarScrim: View
    private val handler = Handler(Looper.getMainLooper())
    private val hideNotice = Runnable { notice.visibility = View.GONE }
    private val saveDraftTask = Runnable { persistEditorDraft() }
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
    private var selectedMeasurement: LocationFix? = null
    private val vertices = mutableListOf<Coordinate>()
    private var keyboardVisible = false
    private var catalogIndex = 0
    private var catalogOffset = 0
    private var catalogView: ListView? = null
    private var editorScroll = 0
    private var search = ""
    private var filter: String? = null
    private var tracking = false
    private var locationAction: LocationAction? = null
    private var fix: LocationFix? = null
    private var pendingOwner: String? = null
    private var pendingIsNote = false
    private var pendingCamera: String? = null
    private var pendingMap: MapLayer? = null
    private var foreground = false
    private var ioTask: IoTask? = null
    private var progressDialog: AlertDialog? = null
    private var preparedRaster: PreparedRaster? = null
    private var rasterDialog: AlertDialog? = null
    private var trackTripId: String? = null
    private var pendingTripRecording: String? = null
    private var pendingTripExport: String? = null
    private var refreshTripDetails: (() -> Unit)? = null
    private val trackChanged: (String?) -> Unit = { problem ->
        refreshTripTrack()
        refreshTripDetails?.invoke()
        problem?.let(::message)
    }

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
            selectedPoint?.takeIf { _ -> it.containsKey("selectedAccuracy") }?.let { point ->
                selectedMeasurement = LocationFix(point, it.getDouble("selectedAccuracy"),
                    it.getDouble("selectedAltitude", Double.NaN).takeIf(Double::isFinite),
                    0L, it.getLong("selectedTime"))
            }
            it.getDoubleArray("vertices")?.toList()?.chunked(2)?.forEach { vertex ->
                vertices.add(Coordinate(vertex[0], vertex[1]))
            }
            search = it.getString("search").orEmpty()
            catalogIndex = it.getInt("catalogIndex")
            catalogOffset = it.getInt("catalogOffset")
            editorScroll = it.getInt("editorScroll")
            filter = it.getString("filter")
            tracking = it.getBoolean("tracking")
            locationAction = it.getString("locationAction")?.let(LocationAction::valueOf)
            if (geometryDraft == null) geometryDraft = geometrySite?.let(store::draft)
            pendingOwner = it.getString("pendingOwner")
            pendingIsNote = it.getBoolean("pendingIsNote")
            pendingCamera = it.getString("pendingCamera")
            pendingMap = it.getString("pendingMap")?.let(MapLayer::valueOf)
            trackTripId = it.getString("trackTripId")
            diaryPage = DiaryPage.valueOf(it.getString("diaryPage", DiaryPage.RECORDS.name))
            diaryTripId = it.getString("diaryTripId")
            filterTripNotes = it.getString("filterTripNotes")
            drawingParentId = it.getString("drawingParentId")
            hiddenTripId = it.getString("hiddenTripId")
            pendingTripRecording = it.getString("pendingTripRecording")
            pendingTripExport = it.getString("pendingTripExport")
        }

        root = vertical().apply {
            background = panel(Palette.paper, 0)
            isFocusableInTouchMode = true
        }
        header = vertical()
        notice = label("", 14f, android.graphics.Color.WHITE).apply {
            visibility = View.GONE
            background = panel(Palette.notice, 18)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content = FrameLayout(this)
        nav = horizontal().apply {
            setPadding(dp(5), dp(4), dp(5), dp(4))
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(header, -1, -2)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(nav, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(10), dp(5), dp(10), dp(6))
        })
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
            viewTreeObserver.addOnGlobalLayoutListener { positionOverlays() }
        }
        drawBehindSystemBars()
        setContentView(screen)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }
        root.requestApplyInsets()
        createMap()
        if (savedInstanceState == null) {
            val preferences = getPreferences(MODE_PRIVATE)
            hiddenTripId = preferences.getString("hiddenTrip", null)?.takeIf { store.trips.get(it) != null }
            trackTripId = preferences.getString("shownTrip", null)?.takeIf { store.trips.get(it) != null }
                ?: store.trips.active()?.id.takeUnless { hiddenTripId != null }
        }
        savedInstanceState?.getFloat("historicalDivider", -1f)?.takeIf { it >= 0f }?.let { map.historicalDivider = it }
        (lastNonConfigurationInstance as? RetainedState)?.let { retained ->
            ioTask = retained.task
            preparedRaster = retained.raster
            retained.draft?.let { draft ->
                if (editorId == draft.id) draftToOpen = draft
                else if (drawMode == DrawMode.MOVE_POINT || drawMode == DrawMode.MOVE_AREA) geometryDraft = draft
            }
        }
        render()
    }

    @Deprecated("Retains unsaved editor input across configuration changes")
    override fun onRetainNonConfigurationInstance(): Any =
        RetainedState(editView?.snapshot() ?: geometryDraft, ioTask, preparedRaster)

    override fun onSaveInstanceState(out: Bundle) {
        persistEditorDraft()
        out.putString("draftToken", draftToken)
        out.putString("tab", tab.name)
        out.putString("previous", previousTab.name)
        out.putString("editor", editorId)
        out.putBoolean("isNote", editorIsNote)
        out.putString("noteOriginId", noteOriginId)
        out.putInt("noteOriginScroll", noteOriginScroll)
        out.putString("draw", drawMode.name)
        out.putString("geometrySite", geometrySite)
        selectedPoint?.let { out.putDoubleArray("selectedPoint", doubleArrayOf(it.latitude, it.longitude)) }
        selectedMeasurement?.let {
            out.putDouble("selectedAccuracy", it.accuracy)
            out.putDouble("selectedAltitude", it.altitude ?: Double.NaN)
            out.putLong("selectedTime", it.measuredAt)
        }
        out.putDoubleArray("vertices", vertices.flatMap { listOf(it.latitude, it.longitude) }.toDoubleArray())
        out.putString("search", search)
        rememberCatalogPosition()
        out.putInt("catalogIndex", catalogIndex)
        out.putInt("catalogOffset", catalogOffset)
        out.putInt("editorScroll", editView?.scrollPosition() ?: 0)
        out.putString("filter", filter)
        out.putBoolean("tracking", tracking)
        out.putString("locationAction", locationAction?.name)
        out.putString("pendingOwner", pendingOwner)
        out.putBoolean("pendingIsNote", pendingIsNote)
        out.putString("pendingCamera", pendingCamera)
        out.putString("pendingMap", pendingMap?.name)
        out.putString("trackTripId", trackTripId)
        out.putString("diaryPage", diaryPage.name)
        out.putString("diaryTripId", diaryTripId)
        out.putString("filterTripNotes", filterTripNotes)
        out.putString("drawingParentId", drawingParentId)
        out.putString("hiddenTripId", hiddenTripId)
        out.putString("pendingTripRecording", pendingTripRecording)
        out.putString("pendingTripExport", pendingTripExport)
        map.historicalDivider?.let { out.putFloat("historicalDivider", it) }
        super.onSaveInstanceState(out)
    }

    internal fun scheduleDraftSave() {
        handler.removeCallbacks(saveDraftTask)
        handler.postDelayed(saveDraftTask, 350L)
    }

    internal fun persistEditorDraft(): Boolean {
        handler.removeCallbacks(saveDraftTask)
        val draft = editView?.takeIf(JournalEditor::hasUnsavedChanges)?.snapshot()
            ?: geometryDraft?.takeIf(EditorDraft::dirty) ?: return true
        return runCatching { store.saveDraft(draft) }.onFailure {
            android.util.Log.w("FieldJournal", "Could not keep editor draft", it)
            if (foreground) message("Не удалось сохранить черновик")
        }.isSuccess
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        TripRecording.observe(trackChanged)
        refreshTripTrack()
        refreshTripDetails?.invoke()
        connectIo()
        showRasterChoice()
    }

    override fun onStop() {
        foreground = false
        TripRecording.forget(trackChanged)
        ioTask?.onReady = null
        progressDialog?.dismiss()
        progressDialog = null
        rasterDialog?.dismiss()
        rasterDialog = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if ((tracking || tiles.coordinatesAtDevice) && this::gps.isInitialized && gps.permitted()) tracking = gps.start()
    }

    override fun onPause() {
        persistEditorDraft()
        gps.stop()
        super.onPause()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            ioTask?.abandon()
            preparedRaster?.close()
        }
        handler.removeCallbacksAndMessages(null)
        map.release()
        super.onDestroy()
    }

    @Deprecated("Uses the activity back stack for a map editor with a persistent draft")
    override fun onBackPressed() {
        when {
            editorId != null -> closeEditor()
            drawMode != DrawMode.NONE -> cancelDraw()
            tab == Tab.NOTES && diaryPage == DiaryPage.TRIPS && diaryTripId != null -> {
                diaryTripId = null
                render()
            }
            tab == Tab.NOTES && filterTripNotes != null -> {
                filterTripNotes = null
                render()
            }
            tab != Tab.MAP -> switch(Tab.MAP)
            else -> super.onBackPressed()
        }
    }

    @Suppress("DEPRECATION")
    private fun drawBehindSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
        if (android.os.Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
    }

    private fun render() {
        rememberCatalogPosition()
        catalogView = null
        refreshTripDetails = null
        editView = null
        content.removeAllViews()
        header.removeAllViews()
        nav.removeAllViews()
        screen.removeView(mapPanel)
        root.setBackgroundColor(Palette.paper)
        if (editorId != null) {
            val id = requireNotNull(editorId)
            val draft = draftToOpen?.takeIf { it.id == id } ?: store.draft(id) ?: if (editorIsNote) {
                store.note(id)?.let { EditorDraft(note = it) }
            } else {
                store.site(id)?.let { EditorDraft(site = it) }
            }
            draftToOpen = null
            if (draft != null) {
                setEditorHeader(if (draft.note != null) "Запись дневника" else "Карточка объекта")
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
        val onMap = tab == Tab.MAP
        nav.background = panel(if (onMap) Palette.floatingSurface else Palette.white, 20, Palette.border)
        nav.elevation = dp(if (onMap) 4 else 2).toFloat()
        for (item in Tab.entries) {
            val button = navigationAction(item.title, item.icon, item == tab) { switch(item) }
            nav.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
        }
        when (tab) {
            Tab.MAP -> {
                root.setBackgroundColor(Color.TRANSPARENT)
                screen.addView(mapPanel, 0, FrameLayout.LayoutParams(-1, -1))
                refreshMap()
                updateControls()
            }
            Tab.SITES -> showSites()
            Tab.NOTES -> showNotes()
            Tab.SETTINGS -> showSettings()
        }
    }

    private fun updateNavigationVisibility() {
        nav.visibility = if (editorId != null || keyboardVisible) View.GONE else View.VISIBLE
    }

    private fun setEditorHeader(title: String) {
        val row = horizontal().apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(Palette.white)
        }
        row.addView(editorAction(done = false) { closeEditor() }, dp(48), dp(48))
        val name = label(title, 19f, Palette.heading, true)
        if (android.os.Build.VERSION.SDK_INT >= 28) name.isAccessibilityHeading = true
        row.addView(name, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        row.addView(editorAction(done = true) { closeEditor() }, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginStart = dp(8)
        })
        header.addView(row)
        header.addView(View(this).apply { setBackgroundColor(Palette.border) }, -1, dp(1))
    }

    private fun switch(next: Tab) {
        if (next == tab) return
        if (drawMode != DrawMode.NONE && geometryDraft?.dirty == true) {
            cancelDraw()
            confirmBeforeLeaving {
                draftFile.delete()
                detachEditor()
                switch(next)
            }
            return
        }
        if (drawMode != DrawMode.NONE) {
            resetDraw()
            draftFile.delete()
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
                    selectedMeasurement = null
                    if (locationAction == LocationAction.SELECT_POINT) locationAction = null
                    updateMapDraft()
                }
                DrawMode.NONE -> site?.let { showSitePreview(it) }
            }
        }
        map.onVertexTap = { index -> removeVertex(index) }
        map.onVertexMove = { index, point ->
            if (index in vertices.indices) {
                vertices[index] = point
                updateMapDraft()
            }
        }
        fun mapCaption(size: Float) = label("", size, Palette.heading, true).apply {
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), dp(3), dp(12), dp(3))
            setShadowLayer(dp(2).toFloat(), 0f, 0f, Color.WHITE)
        }
        mapCoordinates = mapCaption(12f).apply {
            typeface = Typeface.create("monospace", Typeface.BOLD)
        }
        mapArea = mapCaption(12f)
        mapComparisonStatus = mapCaption(12f).apply { text = "Старая слева · современная справа" }
        mapTripStatus = mapCaption(12f).apply {
            setOnClickListener {
                if (drawMode == DrawMode.NONE) {
                    val hidden = hiddenTripId
                    if (hidden != null) showTripOnMap(hidden)
                    else TripRecording.tripId?.let(::showTripDetails)
                }
            }
        }
        mapPanel = FrameLayout(this).apply {
            addView(map, FrameLayout.LayoutParams(-1, -1))
            statusBarScrim = View(this@MainActivity).apply { setBackgroundColor(Palette.floatingSurface) }
            addView(statusBarScrim, FrameLayout.LayoutParams(-1, 0, Gravity.TOP))
            navigationBarScrim = View(this@MainActivity).apply { setBackgroundColor(Palette.floatingSurface) }
            addView(navigationBarScrim, FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM))

            mapZoomButtons = vertical().apply {
                clipChildren = false
                clipToPadding = false
                addView(mapAction(FieldIcon.ZOOM_IN) { map.zoomBy(1.0) }, dp(48), dp(48))
                addView(mapAction(FieldIcon.ZOOM_OUT) { map.zoomBy(-1.0) },
                    LinearLayout.LayoutParams(dp(48), dp(48)).apply { topMargin = dp(8) })
            }
            addView(mapZoomButtons, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END))
            mapLocationButton = mapAction(FieldIcon.LOCATION) { requestLocation() }
            addView(mapLocationButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.END))
        }
    }

    private fun positionOverlays() {
        if (this::mapPanel.isInitialized) positionMapOverlays()
        positionNotice()
    }

    private fun positionMapOverlays() {
        if (mapPanel.parent !== screen || screen.height == 0) return
        val bottom = if (nav.isShown && nav.height > 0) screen.height - nav.top else root.paddingBottom
        map.footerInset = bottom
        fun position(view: View, height: Int? = null, end: Int? = null, bottom: Int? = null) {
            val params = view.layoutParams as FrameLayout.LayoutParams
            if ((height == null || height == params.height) &&
                (end == null || end == params.marginEnd) &&
                (bottom == null || bottom == params.bottomMargin)) return
            height?.let { params.height = it }
            end?.let { params.marginEnd = it }
            bottom?.let { params.bottomMargin = it }
            view.layoutParams = params
        }
        val right = (if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL) root.paddingLeft else root.paddingRight) + dp(12)
        position(statusBarScrim, height = root.paddingTop)
        position(navigationBarScrim, height = root.paddingBottom)
        position(mapZoomButtons, end = right)
        position(mapLocationButton, end = right, bottom = bottom + dp(12))
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
        if (!this::mapCoordinates.isInitialized) return
        val measured = recentFix()
        val point = when (drawMode) {
            DrawMode.NONE -> if (tiles.coordinatesAtDevice) measured?.point else map.center
            DrawMode.POINT, DrawMode.MOVE_POINT -> selectedPoint ?: map.center
            DrawMode.AREA, DrawMode.MOVE_AREA -> map.center
        }
        if (point == null) {
            mapCoordinates.text = if (tracking) "Моя позиция · определяю…" else "Моя позиция · нет замера"
            mapCoordinates.contentDescription = null
        } else {
            val pointFix = selectedMeasurement ?: measured?.takeIf { it.point == point }
            val savedPoint = geometryDraft?.site?.takeIf { it.vertices.singleOrNull() == point }
            val height = (pointFix?.altitude ?: savedPoint?.altitude)?.let { "${it.roundToInt()} м" } ?: "—"
            val accuracy = (pointFix?.accuracy ?: savedPoint?.accuracy)?.let { "±${it.roundToInt()} м" }
            mapCoordinates.text = buildString {
                append(point.display())
                accuracy?.let { append(" $it") }
                append(" · Выс. $height")
            }
            mapCoordinates.contentDescription = buildString {
                val source = when {
                    pointFix != null && drawMode == DrawMode.NONE && tiles.coordinatesAtDevice -> "Моя позиция"
                    selectedMeasurement != null -> "Точка по GPS"
                    selectedPoint != null -> "Выбранная точка"
                    else -> "Центр карты"
                }
                append("$source, ${point.display()}, высота $height")
                accuracy?.let { append(", точность координат $it") }
            }
        }
        val drawingArea = drawMode == DrawMode.AREA || drawMode == DrawMode.MOVE_AREA
        mapArea.visibility = if (drawingArea) View.VISIBLE else View.GONE
        if (drawingArea) mapArea.text = "Вершин ${vertices.size} · ${areaLabel(areaSquareMeters(vertices))}"
        mapComparisonStatus.visibility = if (map.historicalDivider != null && tiles.historicalEnabled) View.VISIBLE else View.GONE
        mapTripStatus.text = if (hiddenTripId != null) "Показать скрытый маршрут" else "Записывается трек выезда"
        mapTripStatus.visibility = if (TripRecording.tripId != null || hiddenTripId != null) View.VISIBLE else View.GONE
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
        updateMapStatus()
        header.removeAllViews()
        header.addView(mapCoordinates, -1, -2)
        header.addView(horizontal().apply {
            setPadding(dp(12), dp(5), dp(12), dp(6))
            clipChildren = false
            clipToPadding = false
            fun control(icon: FieldIcon, primary: Boolean = false, click: () -> Unit) {
                addView(mapAction(icon, primary, click),
                    LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                        if (childCount > 0) marginStart = dp(4)
                    })
            }
            fun spacer() = addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 0, 1f))
            when (drawMode) {
                DrawMode.NONE -> {
                    control(FieldIcon.POINT, true) { beginDraw(DrawMode.POINT) }
                    control(FieldIcon.AREA) { beginDraw(DrawMode.AREA) }
                    control(FieldIcon.COORDINATES) { showMapCoordinates() }
                    spacer()
                    control(FieldIcon.LAYERS) { chooseLayers() }
                }
                DrawMode.POINT, DrawMode.MOVE_POINT -> {
                    control(FieldIcon.LAYERS) { chooseLayers() }
                    control(FieldIcon.COORDINATES) { showPointCoordinates() }
                    control(FieldIcon.GPS_POINT) {
                        recentFix()?.let(::selectGpsPoint) ?: requestLocation(LocationAction.SELECT_POINT)
                    }
                    spacer()
                    control(FieldIcon.SET_POINT, true) {
                        val point = selectedPoint ?: map.center
                        saveGeometry(listOf(point), selectedMeasurement)
                    }
                    control(FieldIcon.CANCEL) { cancelDraw() }
                }
                DrawMode.AREA, DrawMode.MOVE_AREA -> {
                    control(FieldIcon.LAYERS) { chooseLayers() }
                    control(FieldIcon.ADD_VERTEX) { addVertex(map.center) }
                    control(FieldIcon.UNDO_VERTEX) { removeVertex(vertices.lastIndex) }
                    spacer()
                    control(FieldIcon.DONE_AREA, true) {
                        val error = polygonError(vertices)
                        if (error == null) saveGeometry(vertices.toList()) else message(error.message)
                    }
                    control(FieldIcon.CANCEL) { cancelDraw() }
                }
            }
        }, -1, -2)
        header.addView(mapArea, -1, -2)
        header.addView(mapComparisonStatus, -1, -2)
        header.addView(mapTripStatus, -1, -2)
    }

    private fun beginDraw(mode: DrawMode) {
        drawMode = mode
        refreshMap()
        updateControls()
        message(if (mode == DrawMode.AREA || mode == DrawMode.MOVE_AREA)
            "Касание добавляет или удаляет вершину. Удерживайте её для перетаскивания"
        else "Коснитесь карты или выберите GPS, затем нажмите галочку")
    }

    private fun copyCoordinates(point: Coordinate) = PromptAction("Скопировать") {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText("Координаты WGS 84", point.display())
        )
        if (android.os.Build.VERSION.SDK_INT < 33) message("Координаты скопированы")
    }

    private fun showMapCoordinates() {
        val device = tiles.coordinatesAtDevice
        val point = if (device) recentFix()?.point else map.center
        fieldPrompt(
            title = if (device) "Координаты телефона" else "Координаты центра карты",
            primary = point?.let(::copyCoordinates) ?: PromptAction("Определить") {
                requestLocation(LocationAction.READOUT)
            },
            secondary = PromptAction("Ввести", ::manualPoint),
            coordinates = point?.display(),
            choices = listOf(
                PromptAction("Моя позиция") {
                    tiles.coordinatesAtDevice = true
                    if (recentFix() == null) requestLocation(LocationAction.READOUT)
                    updateMapStatus()
                    if (recentFix() != null) showMapCoordinates()
                },
                PromptAction("Центр карты") {
                    tiles.coordinatesAtDevice = false
                    updateMapStatus()
                    showMapCoordinates()
                }
            )
        )
    }

    private fun showPointCoordinates() {
        val point = selectedPoint ?: map.center
        fieldPrompt("Координаты точки", copyCoordinates(point), coordinates = point.display())
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
        saveGeometry(listOf(point), origin = CoordinateOrigin.MANUAL)
    }

    private fun resetDraw() {
        dismissNotice()
        drawMode = DrawMode.NONE
        vertices.clear()
        geometrySite = null
        geometryDraft = null
        drawingParentId = null
        selectedPoint = null
        selectedMeasurement = null
        if (locationAction == LocationAction.SELECT_POINT) locationAction = null
        updateMapDraft()
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

    private fun saveGeometry(
        points: List<Coordinate>, fix: LocationFix? = null,
        origin: CoordinateOrigin = if (fix == null) CoordinateOrigin.MAP else CoordinateOrigin.DEVICE
    ) {
        val original = geometryDraft ?: geometrySite?.let(store::site)?.let { EditorDraft(site = it) }
        if (original != null) {
            val site = requireNotNull(original.site)
            val updated = if (site.vertices == points && fix == null) site else site.copy(
                vertices = points, holes = emptyList(), accuracy = fix?.accuracy, altitude = fix?.altitude,
                coordinateOrigin = origin, measuredAt = fix?.measuredAt
            )
            resetDraw()
            openDraft(original.copy(site = updated, dirty = original.dirty || updated != site))
            return
        }
        val parent = drawingParentId?.let(store::site)
        if (parent != null && !parent.containsGeometry(points)) {
            message("Разместите объект целиком внутри «${parent.title}»")
            return
        }
        chooseType("Условный знак объекта") { type ->
            val geometry = Site(title = type.title, type = type.id, vertices = points,
                accuracy = fix?.accuracy, altitude = fix?.altitude, coordinateOrigin = origin, measuredAt = fix?.measuredAt)
            val site = geometry.copy(parentId = parent?.id ?: store.groupCandidates(geometry).firstOrNull()?.id)
            resetDraw()
            previousTab = tab
            openDraft(EditorDraft(site = site, isNew = true))
        }
    }

    internal fun createInside(parent: Site, area: Boolean) {
        confirmBeforeLeaving {
            resetDraw()
            detachEditor()
            tab = Tab.MAP
            filter = null
            render()
            drawingParentId = parent.id
            map.show(parent)
            beginDraw(if (area) DrawMode.AREA else DrawMode.POINT)
            message("Новый объект внутри «${parent.title}»")
        }
    }

    internal fun openGroupSite(id: String) = confirmBeforeLeaving { openSite(id) }

    internal fun editGeometry(site: Site) {
        val draft = editView?.snapshot() ?: EditorDraft(site = site)
        persistEditorDraft()
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
        fieldworkSheet(title) { dialog ->
            for (types in objectTypes.chunked(2)) {
                addView(horizontal().apply {
                    gravity = Gravity.TOP
                    weightSum = 2f
                    for ((index, type) in types.withIndex()) {
                        val tile = vertical().apply {
                            gravity = Gravity.CENTER
                            minimumHeight = dp(82)
                            setPadding(dp(8), dp(7), dp(8), dp(7))
                            background = android.graphics.drawable.RippleDrawable(Palette.ripple,
                                panel(Palette.control, 15), null)
                            addView(SymbolView(this@MainActivity, type), dp(32), dp(32))
                            addSpace(4)
                            addView(label(type.title, 14f, Palette.ink, true).apply {
                                gravity = Gravity.CENTER
                            }, -1, -2)
                            isFocusable = true
                            setOnClickListener {
                                dialog.dismiss()
                                selection(type)
                            }
                        }
                        addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply {
                            if (index > 0) marginStart = dp(6)
                        })
                    }
                }, -1, -2)
                addSpace(6)
            }
        }
    }

    private fun requestLocation(action: LocationAction = LocationAction.CENTER_MAP) {
        locationAction = action
        if (!gps.permitted()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION)
            return
        }
        if (action == LocationAction.CENTER_MAP) tiles.coordinatesAtDevice = true
        tracking = true
        if (!gps.start()) {
            tracking = false
            locationAction = null
            AlertDialog.Builder(this).setTitle("Геолокация выключена")
                .setMessage("Включите определение местоположения в настройках телефона.")
                .setPositiveButton("Закрыть", null).setNegativeButton("Настройки") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }.show()
        } else if (locationAction != null) {
            message("Определяю местоположение…")
        }
        updateMapStatus()
    }

    private fun selectGpsPoint(value: LocationFix) {
        if (drawMode != DrawMode.POINT && drawMode != DrawMode.MOVE_POINT) return
        selectedPoint = value.point
        selectedMeasurement = value
        map.moveTo(value.point, maxOf(map.zoom, 16.0))
        updateMapDraft()
        val accuracy = value.accuracy.roundToInt()
        message(if (accuracy > 30) "Низкая точность ±$accuracy м. Проверьте перед сохранением"
            else "Точка по GPS · ±$accuracy м")
    }

    private fun recentFix(): LocationFix? =
        fix?.takeIf { android.os.SystemClock.elapsedRealtime() - it.elapsedMs in 0..45_000 }

    private fun receivedFix(value: LocationFix) {
        fix = value
        map.locate = value
        when (locationAction) {
            LocationAction.CENTER_MAP -> map.moveTo(value.point, maxOf(map.zoom, if (value.accuracy < 200) 16.0 else 12.0))
            LocationAction.SELECT_POINT -> selectGpsPoint(value)
            LocationAction.READOUT, null -> Unit
        }
        locationAction = null
        updateMapStatus()
        handler.postDelayed({
            if (fix === value) {
                fix = null
                map.locate = null
                updateMapStatus()
            }
        }, (45000 - (android.os.SystemClock.elapsedRealtime() - value.elapsedMs)).coerceAtLeast(0))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        when (requestCode) {
            LOCATION -> {
                if (gps.permitted()) requestLocation(locationAction ?: LocationAction.CENTER_MAP)
                else {
                    locationAction = null
                    message("Разрешите геолокацию в настройках приложения")
                }
            }
            TRIP_LOCATION -> {
                val tripId = pendingTripRecording
                pendingTripRecording = null
                if (tripId != null && gps.permitted()) startTripRecording(tripId)
                else message("Разрешите геолокацию для записи трека")
            }
        }
    }

    internal fun openLink(value: String) {
        val uri = Uri.parse(value)
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) return message("Нужна ссылка HTTP или HTTPS")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure { message("На телефоне нет приложения для открытия ссылки") }
    }

    private fun chooseLayers() {
        fieldworkSheet("Слои карты") { dialog ->
            val controls = vertical()
            addView(controls, -1, -2)

            fun redrawControls() {
                controls.removeAllViews()
                controls.addView(horizontal().apply {
                    background = panel(Palette.paper, 18)
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                    for (layer in MapLayer.entries) {
                        addView(action(layer.title, tiles.layer == layer) {
                            if (tiles.layer != layer) {
                                tiles.layer = layer
                                map.refresh()
                                updateMapStatus()
                                redrawControls()
                            }
                        }.apply {
                            isSelected = tiles.layer == layer
                            setPadding(dp(3), 0, dp(3), 0)
                        }, LinearLayout.LayoutParams(0, -2, 1f).apply {
                            if (layer != MapLayer.entries.last()) marginEnd = dp(3)
                        })
                    }
                }, -1, -2)
                controls.addSpace(12)
                val history = tiles.info(MapPackage.HISTORICAL)
                val references = tiles.info(MapPackage.REFERENCES)
                val group = vertical().apply {
                    background = panel(Palette.white, 18, Palette.border)
                    setPadding(dp(12), dp(6), dp(12), dp(8))
                }
                controls.addView(group, -1, -2)
                if (history == null) {
                    group.addView(label("Историческая карта", 17f, Palette.heading, true))
                    group.addSpace(4)
                    group.addView(label("MBTiles, SQLiteDB, GeoPackage", 13f, Palette.muted))
                    group.addSpace(6)
                    group.addView(addAction("Подключить карту", FieldIcon.MAP, primary = false) {
                        dialog.dismiss()
                        pickDocument(HISTORICAL, "*/*")
                    }, -1, -2)
                } else {
                    group.addView(layerSwitch("Историческая карта", tiles.historicalEnabled) {
                        tiles.historicalEnabled = it
                        if (!it) map.historicalDivider = null
                        map.refresh()
                        updateMapStatus()
                        redrawControls()
                    }, -1, -2)
                    group.addView(label(history.name, 14f, Palette.muted))
                    if (tiles.historicalEnabled && map.historicalDivider == null) {
                        group.addSpace(8)
                        val opacityLabel = label("Непрозрачность · ${tiles.historicalOpacity}%", 14f, Palette.ink, true)
                        group.addView(opacityLabel)
                        val slider = SeekBar(this@MainActivity).apply {
                            contentDescription = "Непрозрачность исторической карты"
                            max = 90
                            progress = tiles.historicalOpacity - 10
                            minimumHeight = dp(48)
                            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                                    if (!fromUser) return
                                    tiles.historicalOpacity = value + 10
                                    opacityLabel.text = "Непрозрачность · ${tiles.historicalOpacity}%"
                                    map.invalidate()
                                }
                                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                                override fun onStopTrackingTouch(bar: SeekBar) = Unit
                            })
                        }
                        group.addView(slider, -1, -2)
                    }
                    group.addSpace(6)
                    val comparison = map.historicalDivider
                    group.addView(action(if (comparison == null) "Сравнить карты" else "Закончить сравнение") {
                        tiles.historicalEnabled = true
                        map.historicalDivider = if (comparison == null) 0.5f else null
                        map.refresh()
                        updateMapStatus()
                        dialog.dismiss()
                        if (map.historicalDivider != null) message("Потяните разделитель карт")
                    }, -1, -2)
                    if (comparison != null) {
                        group.addView(SeekBar(this@MainActivity).apply {
                            contentDescription = "Граница сравнения карт"
                            max = 92
                            progress = (comparison * 100).roundToInt() - 4
                            minimumHeight = dp(48)
                            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                                    if (fromUser) map.historicalDivider = (value + 4) / 100f
                                }
                                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                                override fun onStopTrackingTouch(bar: SeekBar) = Unit
                            })
                        }, -1, -2)
                    }
                    group.addSpace(6)
                    group.addView(horizontal().apply {
                        addView(action("К участку") {
                            tiles.historicalEnabled = true
                            map.refresh()
                            moveToPackage(history)
                            dialog.dismiss()
                        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
                        addView(action("Заменить") {
                            dialog.dismiss()
                            pickDocument(HISTORICAL, "*/*")
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                    }, -1, -2)
                }
                if (tiles.layer == MapLayer.HYBRID || tiles.historicalEnabled) {
                    group.addSpace(8)
                    val source = tiles.referenceSource()
                    group.addView(layerSwitch("Современные дороги и подписи", tiles.referencesEnabled,
                        enabled = source == ReferenceSource.ESRI || references != null) {
                        tiles.referencesEnabled = it
                        map.refresh()
                    }, -1, -2)
                    if (!tiles.offline) {
                        group.addView(action("Подписи · ${source.title}") {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Источник подписей")
                                .setSingleChoiceItems(ReferenceSource.entries.map(ReferenceSource::title).toTypedArray(), source.ordinal) { picker, index ->
                                    tiles.onlineReferenceSource = ReferenceSource.entries[index]
                                    map.refresh()
                                    picker.dismiss()
                                    redrawControls()
                                }.setPositiveButton("Отмена", null).show()
                        }, -1, -2)
                    }
                    group.addView(label(references?.name ?: "Подключите прозрачный пакет для локальных подписей", 13f, Palette.muted))
                    group.addSpace(6)
                    val connect = {
                        dialog.dismiss()
                        pickDocument(REFERENCES, "*/*")
                    }
                    group.addView(if (references == null) addAction("Подключить подписи", FieldIcon.LAYERS, primary = false, click = connect)
                        else action("Заменить подписи", click = connect), -1, -2)
                }
                controls.addSpace(10)
                controls.addView(layerSwitch("Офлайн-режим", tiles.offline) {
                    toggleOffline()
                    redrawControls()
                }, -1, -2)
                controls.addView(action("Настройки карт") {
                    dialog.dismiss()
                    switch(Tab.SETTINGS)
                }, -1, -2)
            }

            redrawControls()
        }
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun layerSwitch(title: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) =
        Switch(this).apply {
            text = title
            textSize = 15f
            minimumHeight = dp(48)
            switchPadding = dp(8)
            setTextColor(if (enabled) Palette.ink else Palette.muted)
            isChecked = checked && enabled
            isEnabled = enabled
            setOnCheckedChangeListener { _, value -> change(value) }
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
        val sites = store.sites()
        val titles = sites.associate { it.id to it.title }
        val catalog = sites.filter { filter == null || it.type == filter }
        var matches = catalog
        val adapter = object : BaseAdapter() {
            override fun getCount() = matches.size
            override fun getItem(position: Int) = matches[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return ((convertView as? CatalogRow) ?: CatalogRow(this@MainActivity)).apply {
                    val site = getItem(position)
                    bind(site, site.parentId?.let(titles::get))
                }
            }
        }
        results.adapter = adapter
        results.setOnItemClickListener { _, _, position, _ -> openSite(adapter.getItem(position).id) }
        fun showMatches() {
            val input = search.trim()
            matches = if (input.isEmpty()) catalog else catalog.filter { site ->
                listOf(site.title, objectType(site.type).title, site.period, site.culture,
                    site.description, site.inventory, site.sources, titles[site.parentId].orEmpty()).any { it.contains(input, true) }
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
        val loading = vertical().apply { setPadding(dp(14), dp(5), dp(14), dp(8)) }
        loading.addView(horizontal().apply {
            background = panel(Palette.control, 18)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            for (page in DiaryPage.entries) {
                val count = if (page == DiaryPage.DRAFTS) store.draftCount() else 0
                val title = if (count > 0) "${page.title} · $count" else page.title
                addView(navigationAction(title, page.icon, page == diaryPage) {
                    diaryPage = page
                    diaryTripId = null
                    filterTripNotes = null
                    render()
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }, -1, -2)
        loading.addSpace(8)
        content.addView(loading, -1, -1)
        if (diaryPage != DiaryPage.RECORDS) {
            val list = vertical()
            loading.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
            if (diaryPage == DiaryPage.DRAFTS) showDrafts(list)
            else if (diaryTripId != null) showTripPage(list, requireNotNull(diaryTripId))
            else showTrips(list)
            return
        }
        val filter = filterTripNotes?.let(store.trips::get)
        val trip = filter ?: store.trips.active()
        loading.addView(addAction("Новая запись", FieldIcon.NOTE) { createNote(null, tripId = trip?.id) }, -1, -2)
        loading.addSpace(8)
        if (filter != null) {
            loading.addView(action("${filter.title}  ×") {
                filterTripNotes = null
                render()
            }, -1, -2)
            loading.addSpace(8)
        } else {
            store.trips.active()?.let { trip ->
                card(loading, trip.title, "Текущий выезд · новые записи относятся к нему") { showTripDetails(trip.id) }
            }
        }
        val records = vertical()
        val status = label("Загрузка записей…", 14f, Palette.muted)
        records.addView(status)
        loading.addView(records, LinearLayout.LayoutParams(-1, 0, 1f))
        val journal = store
        diaryReads.execute {
            val rows = runCatching { journal.noteSummaries(tripId = filter?.id) }
            handler.post {
                if (isDestroyed || loading.parent !== content) return@post
                rows.onSuccess { notes ->
                    records.removeAllViews()
                    if (notes.isEmpty()) {
                        empty(records, "Дневник пока пуст", "Создайте запись о выезде, наблюдении или работе с источниками.")
                    } else {
                        val results = ListView(this).apply {
                            divider = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                            dividerHeight = dp(8)
                            adapter = object : BaseAdapter() {
                                override fun getCount() = notes.size
                                override fun getItem(position: Int) = notes[position]
                                override fun getItemId(position: Int) = position.toLong()
                                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                                    ((convertView as? DiaryRow) ?: DiaryRow(this@MainActivity)).apply { bind(getItem(position)) }
                            }
                            setOnItemClickListener { _, _, position, _ -> openNote(notes[position].id) }
                        }
                        records.addView(results, -1, -1)
                    }
                }.onFailure { status.text = "Не удалось загрузить дневник" }
            }
        }
    }

    internal fun refreshDiary() {
        if (tab == Tab.NOTES && editorId == null) render()
    }

    internal fun resumeDraft(draft: EditorDraft) {
        previousTab = tab
        noteOriginId = null
        noteOriginScroll = 0
        openDraft(draft)
    }

    internal fun showTripDetails(id: String) = confirmBeforeLeaving {
        detachEditor()
        tab = Tab.NOTES
        diaryPage = DiaryPage.TRIPS
        diaryTripId = id
        filterTripNotes = null
        render()
    }

    internal fun showTripRecords(id: String) {
        diaryPage = DiaryPage.RECORDS
        filterTripNotes = id
        render()
    }

    internal fun closeTripDetails() {
        diaryTripId = null
        refreshDiary()
    }

    internal fun watchTripPage(refresh: () -> Unit) {
        refreshTripDetails = refresh
    }

    internal fun selectTripTrack(id: String?) {
        if (id != null) hiddenTripId = null
        trackTripId = id
        getPreferences(MODE_PRIVATE).edit().putString("shownTrip", id).putString("hiddenTrip", hiddenTripId).apply()
        refreshTripTrack()
    }

    internal fun hideTripTrack(id: String) {
        if (trackTripId != id) return
        hiddenTripId = id
        selectTripTrack(null)
        refreshDiary()
    }

    internal fun deleteTrip(id: String) {
        store.deleteTrip(id)
        if (TripRecording.tripId == id) TripRecordingService.pause(this)
        if (hiddenTripId == id) hiddenTripId = null
        if (trackTripId == id || trackTripId == null) selectTripTrack(null)
        else refreshTripTrack()
        closeTripDetails()
    }

    private fun refreshTripTrack() {
        updateMapStatus()
        val id = trackTripId
        if (id == null) {
            map.trackSegments = emptyList()
            return
        }
        val trips = store.trips
        diaryReads.execute {
            val segments = runCatching {
                trips.points(id).groupBy(TrackPoint::segment).values.map { group -> group.map(TrackPoint::point) }
            }
            handler.post {
                if (isDestroyed || trackTripId != id) return@post
                segments.onSuccess { map.trackSegments = it }
            }
        }
    }

    internal fun showTripOnMap(id: String) {
        if (store.trips.get(id) == null) return message("Выезд не найден")
        val contents = store.trips.contents(id)
        val route = contents.points.map(TrackPoint::point)
        val points = route.ifEmpty { contents.sites.map(Site::anchor) }
        if (points.isEmpty()) {
            message("В этом выезде пока нет точек маршрута и объектов")
            return
        }
        if (route.isNotEmpty()) selectTripTrack(id)
        tab = Tab.MAP
        render()
        map.post {
            if (!isDestroyed && tab == Tab.MAP && (route.isEmpty() || trackTripId == id)) map.showPoints(points)
        }
    }

    internal fun showingTripTrack(id: String) = trackTripId == id

    internal fun startTripRecording(id: String) {
        if (store.trips.active()?.id != id) return
        if (!gps.permitted()) {
            pendingTripRecording = id
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), TRIP_LOCATION)
            return
        }
        runCatching { TripRecordingService.start(this, id) }.onSuccess {
            if (hiddenTripId != id) selectTripTrack(id)
            val notifications = getPreferences(MODE_PRIVATE)
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                !notifications.getBoolean("askedTrackNotifications", false)) {
                notifications.edit().putBoolean("askedTrackNotifications", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), TRIP_NOTIFICATIONS)
            }
        }.onFailure { message("Не удалось запустить запись трека") }
    }

    internal fun finishTrip(id: String) {
        store.trips.finish(id)
        if (TripRecording.tripId == id) TripRecordingService.pause(this)
    }

    internal fun exportTrip(id: String, forQgis: Boolean) {
        val trip = store.trips.get(id) ?: return
        val name = trip.title.replace(Regex("""[^\p{L}\p{N}._ -]"""), "_").trim().take(80).ifBlank { "Выезд" }
        pendingTripExport = id
        if (forQgis) exportDocument(TRIP_KIT, "application/zip", "$name.zip")
        else exportDocument(TRIP_GPX, "application/gpx+xml", "$name.gpx")
    }

    internal fun empty(list: LinearLayout, title: String, detail: String) {
        val box = vertical().apply {
            background = panel(Palette.pale)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            addView(label(title, 18f, Palette.heading, true))
            addSpace(5)
            addView(label(detail, 14f, Palette.muted))
        }
        list.addView(box, -1, -2)
    }

    internal fun card(
        list: LinearLayout, title: String, detail: String, icon: View? = null,
        grouped: Boolean = false, click: () -> Unit
    ) {
        val row = horizontal().apply {
            background = android.graphics.drawable.RippleDrawable(Palette.ripple,
                panel(Palette.white, if (grouped) 0 else 18, if (grouped) 0 else Palette.border), null)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minimumHeight = dp(64)
            val description = vertical()
            description.addView(label(title, 16f, Palette.ink, true))
            description.addView(label(detail, 13f, Palette.muted))
            if (icon != null) {
                addView(icon, dp(39), dp(39))
                description.setPadding(dp(10), 0, dp(6), 0)
            }
            addView(description, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("›", 22f, Palette.muted).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            isFocusable = true
            setOnClickListener { click() }
        }
        list.addView(row, -1, -2)
        if (!grouped) list.addSpace(8)
    }

    private fun showSettings() {
        val list = listBody()
        fun section(title: String): LinearLayout {
            list.addSpace(10)
            list.addView(label(title, 19f, Palette.ink, true).apply {
                if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            })
            list.addSpace(8)
            return vertical().apply {
                background = panel(Palette.white, 20, Palette.border)
                clipToOutline = true
                list.addView(this, -1, -2)
            }
        }
        fun row(group: LinearLayout, title: String, detail: String, click: () -> Unit) {
            if (group.childCount > 0) group.addView(View(this).apply {
                setBackgroundColor(Palette.border)
            }, LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(14) })
            card(group, title, detail, grouped = true, click = click)
        }

        val maps = section("Картография")
        row(maps, if (tiles.offline) "Режим · офлайн" else "Режим · онлайн",
            if (tiles.offline) "Нажмите, чтобы подключить онлайн-подложки" else "Нажмите для работы с локальными подложками") { toggleOffline() }
        for (layer in MapLayer.entries) {
            val info = tiles.info(layer.archive)
            val detail = when {
                info != null -> "${info.name} · ${info.format.title} · масштаб ${info.minZoom}–${info.maxZoom}"
                layer == MapLayer.HYBRID && tiles.info(MapPackage.SATELLITE) != null -> "Используется офлайн-спутник · можно подключить свою карту"
                else -> "MBTiles, SQLiteDB или GeoPackage"
            }
            row(maps, "Офлайн · ${layer.title}", detail) {
                if (info == null) pickMap(layer) else {
                    AlertDialog.Builder(this).setTitle(info.name)
                        .setPositiveButton("Отмена", null)
                        .setNeutralButton("Заменить файл") { _, _ -> pickMap(layer) }
                        .setNegativeButton("Открыть карту") { _, _ -> activateMap(layer, info) }.show()
                }
            }
        }
        for ((slot, title, request) in listOf(
            Triple(MapPackage.HISTORICAL, "Историческая карта", HISTORICAL),
            Triple(MapPackage.REFERENCES, "Локальные дороги и подписи", REFERENCES)
        )) {
            val archive = tiles.info(slot)
            row(maps, title, archive?.let { "${it.name} · ${it.format.title}" } ?: "MBTiles, SQLiteDB или GeoPackage") {
                pickDocument(request, "*/*")
            }
        }
        row(maps, "Свой онлайн-источник XYZ",
            if (tiles.customUrl.isBlank()) "Карта по умолчанию · OpenStreetMap" else tiles.customCredit) { customSource() }
        val exchange = section("Обмен и резервные копии")
        row(exchange, "Импорт ZIP или GeoJSON", "Существующие записи с теми же ID сохраняются") { pickDocument(IMPORT, "*/*") }
        row(exchange, "Экспорт резервной копии", "ZIP · карточки, записи, выезды и вложения") { exportDocument(BACKUP, "application/zip", "field-journal-${shortDate()}.zip") }
        row(exchange, "Экспорт объектов GeoJSON", "WGS 84 · точки, контуры и поля карточек") { exportDocument(GEOJSON, "application/geo+json", "field-journal-${shortDate()}.geojson") }
        row(exchange, "Экспорт выезда", "Маршрут GPX или комплект для QGIS с объектами и записями") { chooseTripExport() }
        list.addSpace(10)
        list.addView(label("Подтверждённые изменения хранятся в локальной базе. Файлы карт хранятся отдельно от резервных копий.", 13f, Palette.muted))
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
        pickDocument(RASTER_MAP, "*/*")
    }

    private fun activateMap(layer: MapLayer, info: OfflineInfo) {
        tiles.offline = true
        tiles.layer = layer
        map.refresh()
        tab = Tab.MAP
        render()
        moveToPackage(info)
        updateMapStatus()
    }

    private fun moveToPackage(info: OfflineInfo) {
        info.center?.let { map.moveTo(it, (info.minZoom + 1.0).coerceAtMost(info.maxZoom.toDouble())) }
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
        persistEditorDraft()
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

    internal fun createNote(siteId: String?, compact: Boolean = false, tripId: String? = store.trips.active()?.id) {
        confirmBeforeLeaving {
            val note = Note(title = "Полевое наблюдение", siteId = siteId, tripId = tripId)
            openNoteFromCurrentScreen(note.id, EditorDraft(note = note, isNew = true, compact = compact))
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
        fieldPrompt(
            title = question,
            detail = editor.adoptionNotice(),
            primary = PromptAction("Сохранить") {
                if (editor.saveConfirmed()) {
                    after()
                    message("Сохранено")
                }
            },
            secondary = PromptAction("Не сохранять") {
                if (editor.discardDraft()) after()
            },
            destructive = true,
            choices = listOf(PromptAction("В черновики") {
                if (persistEditorDraft()) after()
            })
        )
    }

    private fun detachEditor() {
        editView = null
        editorId = null
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(root.windowToken, 0)
    }

    internal fun closeEditor() = confirmBeforeLeaving { finishEditor() }

    internal fun closeDeletedEditor() {
        if (editView?.discardDraft() != false) finishEditor()
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
        if (attachment.mime.startsWith("image/")) openPhotoGallery(listOf(attachment), 0)
        else openExternalAttachment(attachment)
    }

    internal fun openExternalAttachment(attachment: Attachment) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(attachmentUri(this, attachment), attachment.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(intent) }.onFailure { message("На телефоне нет приложения для этого файла") }
    }

    @Deprecated("Receives results for Android system document and camera intents")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        val resolver = applicationContext.contentResolver
        val journal = store
        when (requestCode) {
            CAMERA -> handleCamera(resultCode)
            ATTACH -> {
                val owner = pendingOwner
                pendingOwner = null
                if (resultCode != RESULT_OK || uri == null || owner == null) return
                val target = AttachmentTarget(draftToken, owner, pendingIsNote)
                withIo("Копирование вложения", abandoned = { record: Attachment ->
                    journal.attachmentFile(record.id).delete()
                }, work = {
                    requireNotNull(resolver.openInputStream(uri)).use {
                        journal.addAttachment(it, documentName(resolver, uri), resolver.getType(uri) ?: "application/octet-stream")
                    }
                }) { record ->
                    if (target.draftToken == draftToken) stageMedia(target.owner, target.note, record)
                    else store.attachmentFile(record.id).delete()
                }
            }
            RASTER_MAP, HISTORICAL, REFERENCES -> {
                val slot = when (requestCode) {
                    HISTORICAL -> MapPackage.HISTORICAL
                    REFERENCES -> MapPackage.REFERENCES
                    else -> pendingMap?.archive
                }
                pendingMap = null
                if (resultCode != RESULT_OK || uri == null || slot == null) return
                val mapTiles = tiles
                withIo("Чтение файла карты", abandoned = PreparedRaster::close, work = {
                    val name = runCatching { documentName(resolver, uri) }.getOrDefault("")
                    requireNotNull(resolver.openInputStream(uri)) { "Не удалось открыть файл карты" }.use {
                        mapTiles.prepare(slot, it, name)
                    }
                }) {
                    if (it.options.size == 1) finishRasterImport(it)
                    else {
                        preparedRaster = it
                        showRasterChoice()
                    }
                }
            }
            IMPORT -> {
                if (resultCode != RESULT_OK || uri == null) return
                withIo("Импорт данных", {
                    requireNotNull(resolver.openInputStream(uri)).buffered().use { input ->
                        input.mark(4)
                        val zip = input.read() == 'P'.code && input.read() == 'K'.code
                        input.reset()
                        if (zip) journal.importBackup(input) else journal.importGeoJson(input)
                    }
                }) { result ->
                    render()
                    AlertDialog.Builder(this).setTitle("Импорт завершён")
                        .setMessage(buildString {
                            appendLine("Объектов добавлено: ${result.sites}")
                            appendLine("Записей дневника: ${result.notes}")
                            if (result.trips > 0) appendLine("Выездов: ${result.trips}")
                            append("Совпадений пропущено: ${result.skipped}")
                        })
                        .setPositiveButton("Готово", null).show()
                }
            }
            TRIP_GPX, TRIP_KIT -> {
                val id = pendingTripExport
                pendingTripExport = null
                if (resultCode != RESULT_OK || uri == null || id == null) return
                withIo("Экспорт выезда", {
                    val openOutput = { requireNotNull(resolver.openOutputStream(uri, "wt")) }
                    if (requestCode == TRIP_GPX) journal.exportTripGpx(id, openOutput)
                    else journal.exportTripKit(id, openOutput)
                }) { message("Выезд экспортирован") }
            }
            BACKUP, GEOJSON -> {
                if (resultCode != RESULT_OK || uri == null) return
                withIo("Экспорт данных", {
                    val openOutput = { requireNotNull(resolver.openOutputStream(uri, "wt")) }
                    if (requestCode == BACKUP) journal.exportBackup(openOutput)
                    else journal.exportGeoJson(openOutput)
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

    private fun showRasterChoice() {
        val prepared = preparedRaster ?: return
        if (!foreground || rasterDialog != null) return
        rasterDialog = fieldworkSheet("Схема SQLiteDB", onCancel = {
            prepared.close()
            preparedRaster = null
            rasterDialog = null
        }) { dialog ->
            addView(label("В файле схема не указана. Выберите вариант, где координаты соответствуют нужному участку.", 14f, Palette.muted))
            prepared.preview?.let { bitmap ->
                addSpace(8)
                addView(ImageView(this@MainActivity).apply {
                    setImageBitmap(bitmap)
                    contentDescription = "Образец тайла"
                }, -1, dp(110))
            }
            addSpace(8)
            for (option in prepared.options) {
                card(this, requireNotNull(option.numbering).title,
                    "${option.center?.display().orEmpty()} · масштаб ${option.minZoom}–${option.maxZoom}") {
                    preparedRaster = null
                    rasterDialog = null
                    dialog.dismiss()
                    finishRasterImport(prepared, option.numbering)
                }
            }
        }
    }

    private fun finishRasterImport(prepared: PreparedRaster, numbering: SqliteNumbering? = null) {
        runCatching { tiles.install(prepared, numbering) }.onSuccess { info ->
            when (prepared.slot) {
                MapPackage.HISTORICAL -> {
                    tiles.historicalEnabled = true
                    map.refresh()
                    tab = Tab.MAP
                    render()
                    moveToPackage(info)
                    message("Историческая карта подключена")
                }
                MapPackage.REFERENCES -> {
                    tiles.referencesEnabled = true
                    if (!tiles.offline) tiles.onlineReferenceSource = ReferenceSource.LOCAL
                    map.refresh()
                    if (tab == Tab.SETTINGS) render()
                    message("Слой подписей подключён")
                }
                else -> {
                    activateMap(MapLayer.entries.first { it.archive == prepared.slot }, info)
                    message("Офлайн-карта установлена")
                }
            }
        }.onFailure(::showIoError)
    }

    private fun stageMedia(id: String, note: Boolean, record: Attachment) {
        val editor = editView
        if (editor != null && editorId == id && editorIsNote == note) {
            editor.stageAttachment(record)
            message("Вложение добавлено в черновик")
        } else {
            store.attachmentFile(record.id).delete()
        }
    }

    private fun <T> withIo(
        title: String, work: () -> T, abandoned: (T) -> Unit = {}, success: MainActivity.(T) -> Unit
    ) {
        val task = IoTask(title)
        ioTask = task
        io.execute {
            val result = runCatching(work)
            Handler(Looper.getMainLooper()).post {
                task.complete(
                    deliver = { activity -> result.fold({ activity.success(it) }, activity::showIoError) },
                    discard = { result.onSuccess(abandoned) }
                )
            }
        }
        connectIo()
    }

    private fun connectIo() {
        val task = ioTask ?: return
        if (!foreground) return
        val deliver = task.deliver
        if (deliver == null) {
            task.onReady = ::connectIo
            if (progressDialog == null) progressDialog = AlertDialog.Builder(this)
                .setTitle(task.title).setMessage("Подождите…").setCancelable(false).show()
        } else {
            task.onReady = null
            ioTask = null
            progressDialog?.dismiss()
            progressDialog = null
            deliver(this)
        }
    }

    private fun showIoError(error: Throwable) {
        AlertDialog.Builder(this).setTitle("Операция не завершена")
            .setMessage(error.message ?: "Не удалось прочитать или сохранить данные")
            .setPositiveButton("Закрыть", null).show()
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
            editorId == null && tab == Tab.MAP && mapLocationButton.isShown -> mapLocationButton
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
        private const val RASTER_MAP = 13
        private const val IMPORT = 14
        private const val BACKUP = 15
        private const val GEOJSON = 16
        private const val HISTORICAL = 17
        private const val REFERENCES = 18
        private const val TRIP_GPX = 19
        private const val TRIP_KIT = 20
        private const val TRIP_LOCATION = 21
        private const val TRIP_NOTIFICATIONS = 22
        private val io = Executors.newSingleThreadExecutor()
        private val diaryReads = Executors.newSingleThreadExecutor()

        private fun documentName(resolver: ContentResolver, uri: Uri) =
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty()
    }
}
