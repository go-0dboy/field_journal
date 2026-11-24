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
    private enum class DiaryPage(val title: String) {
        RECORDS("Записи"), TRIPS("Выезды"), DRAFTS("Черновики")
    }
    private var diaryPage = DiaryPage.RECORDS
    private var diaryTripId: String? = null
    private data class NewTrip(var title: String, var profile: String)
    private var newTrip: NewTrip? = null
    private val tripProfiles = listOf("Разведка", "Мониторинг", "Раскоп", "Историческое обследование")
    private var filterTripNotes: String? = null
    private var drawingParentId: String? = null
    private var hiddenTripId: String? = null
    private enum class DrawMode { NONE, POINT, AREA, MOVE_POINT, MOVE_AREA }
    private enum class LocationAction { CENTER_MAP, SELECT_POINT, ADD_VERTEX, READOUT }

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
    private lateinit var notice: LinearLayout
    private lateinit var noticeText: TextView
    private lateinit var noticeAction: TextView
    private var undoVertexButton: View? = null
    private var finishAreaButton: View? = null
    private var displayedCoordinateSource: CoordinateSource? = null
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
    private val vertexMeasurements = mutableMapOf<Coordinate, LocationFix>()
    private val boundaryHistory = BoundaryHistory()
    private var keyboardVisible = false
    private var catalogIndex = 0
    private var catalogOffset = 0
    private var catalogView: ListView? = null
    private var editorScroll = 0
    private var search = ""
    private var filter: String? = null
    private var tracking = false
    private var locationAction: LocationAction? = null
    private val samples = mutableListOf<LocationFix>()
    private var latestCapture: Long = -1
    private var fix: LocationFix? = null
    private var pendingOwner: String? = null
    private var pendingIsNote = false
    private var pendingCamera: String? = null
    private var pendingMap: MapLayer? = null
    private sealed interface RasterSelection
    private data class OziSelection(val slot: MapPackage, val first: Uri, val isImage: Boolean) : RasterSelection
    private data class ReadyRaster(val raster: PreparedRaster) : RasterSelection
    private var pendingOzi: OziSelection? = null
    private var foreground = false
    private var ioTask: IoTask? = null
    private var progressDialog: AlertDialog? = null
    private var preparedRaster: PreparedRaster? = null
    private var rasterDialog: AlertDialog? = null
    private var trackTripId: String? = null
    private var pendingTripRecording: String? = null
    private var pendingTripExport: String? = null
    private var pendingTripEdition: Int? = null
    private var publicationDecimals: Int = 2
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
            it.getString("vertexData")?.let { data ->
                runCatching {
                    org.json.JSONArray(data).objects().forEach { row ->
                        val point = Coordinate.read(row.getJSONArray("point"))
                        vertices.add(point)
                        row.nullableDouble("accuracy")?.let { uncertainty ->
                            vertexMeasurements[point] = LocationFix(point, uncertainty, row.nullableDouble("altitude"),
                                0, row.getLong("time"), row.optString("provider").ifBlank { null },
                                row.nullableDouble("vertical_accuracy"))
                        }
                    }
                }
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
            val oziSlot = it.getString("pendingOziSlot")?.let(MapPackage::valueOf)
            val oziUri = it.getString("pendingOziUri")?.let(Uri::parse)
            if (oziSlot != null && oziUri != null) pendingOzi = OziSelection(oziSlot, oziUri, it.getBoolean("pendingOziImage"))
            trackTripId = it.getString("trackTripId")
            diaryPage = DiaryPage.valueOf(it.getString("diaryPage", DiaryPage.RECORDS.name))
            diaryTripId = it.getString("diaryTripId")
            val tripProfile = it.getString("newTripProfile", tripProfiles.first())
            newTrip = it.getString("newTripTitle")?.let { title -> NewTrip(title, tripProfile) }
            filterTripNotes = it.getString("filterTripNotes")
            drawingParentId = it.getString("drawingParentId")
            hiddenTripId = it.getString("hiddenTripId")
            pendingTripRecording = it.getString("pendingTripRecording")
            pendingTripExport = it.getString("pendingTripExport")
            pendingTripEdition = it.getInt("pendingTripEdition", -1).takeIf { number -> number > 0 }
            publicationDecimals = it.getInt("publicationDecimals", 2)
        }

        root = vertical().apply {
            background = panel(Palette.paper, 0)
            isFocusableInTouchMode = true
        }
        header = vertical()
        noticeText = label("", 14f, Color.WHITE).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        noticeAction = action("Вернуть", primary = true) { }.apply { visibility = View.GONE }
        notice = horizontal().apply {
            visibility = View.GONE
            background = panel(Palette.notice, 18)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(noticeText, LinearLayout.LayoutParams(0, -2, 1f))
            addView(noticeAction, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
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
        out.putString("vertexData", org.json.JSONArray(vertices.map { point ->
            org.json.JSONObject().put("point", point.json()).apply {
                vertexMeasurements[point]?.let {
                    put("accuracy", it.accuracy); put("altitude", it.altitude); put("time", it.measuredAt)
                    put("provider", it.provider); put("vertical_accuracy", it.verticalAccuracy)
                }
            }
        }).toString())
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
        pendingOzi?.let {
            out.putString("pendingOziSlot", it.slot.name)
            out.putString("pendingOziUri", it.first.toString())
            out.putBoolean("pendingOziImage", it.isImage)
        }
        out.putString("trackTripId", trackTripId)
        out.putString("diaryPage", diaryPage.name)
        out.putString("diaryTripId", diaryTripId)
        newTrip?.let {
            out.putString("newTripTitle", it.title)
            out.putString("newTripProfile", it.profile)
        }
        out.putString("filterTripNotes", filterTripNotes)
        out.putString("drawingParentId", drawingParentId)
        out.putString("hiddenTripId", hiddenTripId)
        out.putString("pendingTripRecording", pendingTripRecording)
        out.putString("pendingTripExport", pendingTripExport)
        out.putInt("pendingTripEdition", pendingTripEdition ?: -1)
        out.putInt("publicationDecimals", publicationDecimals)
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
            newTrip != null -> closeNewTrip()
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
        newTrip?.let { draft ->
            updateNavigationVisibility()
            showNewTrip(draft)
            return
        }
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
        nav.visibility = if (editorId != null || newTrip != null || keyboardVisible) View.GONE else View.VISIBLE
    }

    private fun setEditorHeader(title: String, back: () -> Unit = ::closeEditor, done: () -> Unit = ::closeEditor) {
        val row = horizontal().apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(Palette.white)
        }
        row.addView(editorAction(done = false, click = back), dp(48), dp(48))
        val name = label(title, 19f, Palette.heading, true)
        if (android.os.Build.VERSION.SDK_INT >= 28) name.isAccessibilityHeading = true
        row.addView(name, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        row.addView(editorAction(done = true, click = done), LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginStart = dp(8)
        })
        header.addView(row)
        header.addView(View(this).apply { setBackgroundColor(Palette.border) }, -1, dp(1))
    }

    internal fun openNewTrip() {
        newTrip = NewTrip("Выезд · ${java.time.LocalDate.now()}", tripProfiles.first())
        render()
    }

    private fun closeNewTrip() {
        newTrip = null
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(root.windowToken, 0)
        render()
    }

    private fun showNewTrip(draft: NewTrip) {
        val name = edit("Название выезда", draft.title)
        name.id = View.generateViewId()
        name.watch { draft.title = it }
        setEditorHeader("Новый выезд", back = ::closeNewTrip, done = {
            val title = draft.title.trim()
            if (title.isEmpty()) {
                name.error = "Укажите название выезда"
                name.requestFocus()
            } else {
                runCatching { store.trips.start(title.take(120), profile = draft.profile) }.onSuccess { trip ->
                    newTrip = null
                    showTripDetails(trip.id)
                }.onFailure { message(it.message ?: "Не удалось начать выезд") }
            }
        })
        content.addView(ScrollView(this).apply {
            addView(vertical().apply {
                setPadding(dp(16), dp(14), dp(16), dp(20))
                addView(label("Название выезда", 13f, Palette.muted, true).apply { labelFor = name.id })
                addSpace(4)
                addView(name, -1, -2)
                addSpace(14)
                val profile = valueRow("Тип выезда", draft.profile) { button ->
                    chooseOption("Тип выезда", tripProfiles, draft.profile) {
                        draft.profile = it
                        button.text = it
                    }
                }
                addView(profile, -1, -2)
            })
        }, -1, -1)
        root.requestFocus()
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
        map.onOverlappingSites = { point, sites ->
            if (drawMode == DrawMode.NONE) fieldworkSheet("Объекты в этой точке") { dialog ->
                for (site in sites) card(this, site.title, site.inventory.ifBlank { site.symbol.title }) {
                    dialog.dismiss()
                    showSitePreview(site)
                }
            } else {
                map.onTap?.invoke(point, null)
            }
        }
        map.onVertexTap = ::removeVertex
        map.onVertexMove = { index, point ->
            if (index in vertices.indices && vertices[index] != point) {
                dismissNotice()
                boundaryHistory.remember(vertices, vertexMeasurements)
                vertexMeasurements.remove(vertices[index])
                vertices[index] = point
                updateMapDraft()
            }
        }
        fun mapCaption(size: Float) = label("", size, Palette.heading, true).apply {
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), dp(3), dp(12), dp(3))
            setShadowLayer(dp(3).toFloat(), 0f, 0f, Color.WHITE)
        }
        mapCoordinates = mapCaption(12f).apply {
            if (android.os.Build.VERSION.SDK_INT >= 26) setAutoSizeTextTypeUniformWithConfiguration(10, 18, 1,
                android.util.TypedValue.COMPLEX_UNIT_SP)
            typeface = Typeface.create("monospace", Typeface.BOLD)
        }
        mapArea = mapCaption(12f)
        mapComparisonStatus = mapCaption(12f).apply { text = "Старая слева · современная справа" }
        mapTripStatus = mapCaption(12f).apply { text = "Записывается трек выезда" }
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
        map.sites = store.locatedSites().filter { filter == null || it.type == filter }
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
        val source = coordinateSource()
        if (displayedCoordinateSource != source) {
            mapCoordinates.setCompoundDrawablesRelativeWithIntrinsicBounds(
                fieldGlyph(source.icon, 16, Palette.heading, outline = true), null, null, null)
            mapCoordinates.compoundDrawablePadding = dp(5)
            displayedCoordinateSource = source
        }
        val point = when (drawMode) {
            DrawMode.NONE -> if (tiles.coordinatesAtDevice) measured?.point else map.center
            DrawMode.POINT, DrawMode.MOVE_POINT -> selectedPoint ?: map.center
            DrawMode.AREA, DrawMode.MOVE_AREA -> map.center
        }
        if (point == null) {
            mapCoordinates.text = if (tracking) "Моя позиция · определяю…" else "Моя позиция · нет замера"
            mapCoordinates.contentDescription = mapCoordinates.text
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
                append("${source.title}, ${point.display()}, высота $height")
                accuracy?.let { append(", точность координат $it") }
            }
        }
        val drawingArea = drawMode == DrawMode.AREA || drawMode == DrawMode.MOVE_AREA
        mapArea.visibility = if (drawingArea) View.VISIBLE else View.GONE
        if (drawingArea) mapArea.text = if (vertices.size < 3) "${vertices.size} из 3 вершин"
            else "Вершин ${vertices.size} · ${areaLabel(areaSquareMeters(vertices))}"
        finishAreaButton?.isEnabled = vertices.size >= 3
        undoVertexButton?.isEnabled = boundaryHistory.canUndo
        mapComparisonStatus.visibility = if (map.historicalDivider != null && tiles.historicalEnabled) View.VISIBLE else View.GONE
        mapTripStatus.visibility = if (TripRecording.tripId != null) View.VISIBLE else View.GONE
    }

    private fun addVertex(point: Coordinate) {
        if (vertices.size >= 2000) return message("Достигнут предел: 2000 вершин")
        if (vertices.any { it.latitude == point.latitude && it.longitude == point.longitude })
            return message("Такая вершина уже добавлена")
        dismissNotice()
        boundaryHistory.remember(vertices, vertexMeasurements)
        vertices.add(point)
        updateMapDraft()
    }

    private fun removeVertex(index: Int) {
        if (index !in vertices.indices) return
        boundaryHistory.remember(vertices, vertexMeasurements)
        vertexMeasurements.remove(vertices.removeAt(index))
        updateMapDraft()
        message("Вершина удалена", ::undoBoundaryChange)
    }

    private fun undoBoundaryChange() {
        val previous = boundaryHistory.undo() ?: return
        dismissNotice()
        vertices.clear()
        vertices.addAll(previous.vertices)
        vertexMeasurements.clear()
        vertexMeasurements.putAll(previous.measurements)
        updateMapDraft()
    }

    private fun updateControls() {
        undoVertexButton = null
        finishAreaButton = null
        (mapArea.parent as? android.view.ViewGroup)?.removeView(mapArea)
        header.removeAllViews()
        header.addView(mapCoordinates, -1, -2)
        header.addView(horizontal().apply {
            setPadding(dp(12), dp(5), dp(12), dp(6))
            clipChildren = false
            clipToPadding = false
            fun control(icon: FieldIcon, primary: Boolean = false, click: () -> Unit) =
                mapAction(icon, primary, click).also { button ->
                    addView(button, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                        if (childCount > 0) marginStart = dp(4)
                    })
                }
            fun spacer() = addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 0, 1f))
            when (drawMode) {
                DrawMode.NONE -> {
                    control(FieldIcon.POINT, true) { beginDraw(DrawMode.POINT) }
                    control(FieldIcon.AREA) { beginDraw(DrawMode.AREA) }
                    control(FieldIcon.COORDINATES) { showMapCoordinates() }
                    control(FieldIcon.VISIBLE_OBJECTS) { showVisibleObjects() }
                    spacer()
                    control(FieldIcon.LAYERS) { chooseLayers() }
                }
                DrawMode.POINT, DrawMode.MOVE_POINT -> {
                    control(FieldIcon.LAYERS) { chooseLayers() }
                    control(FieldIcon.COORDINATES) { showPointCoordinates() }
                    control(FieldIcon.GPS_POINT) {
                        captureLocation(LocationAction.SELECT_POINT)
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
                    control(FieldIcon.GPS_VERTEX) {
                        captureLocation(LocationAction.ADD_VERTEX)
                    }
                    spacer()
                    finishAreaButton = control(FieldIcon.DONE_AREA, true) {
                        val error = polygonError(vertices)
                        if (error == null) saveGeometry(vertices.toList()) else message(error.message)
                    }
                    control(FieldIcon.CANCEL) { cancelDraw() }
                }
            }
        }, -1, -2)
        val drawingArea = drawMode == DrawMode.AREA || drawMode == DrawMode.MOVE_AREA
        if (drawingArea) header.addView(horizontal().apply {
            setPadding(dp(12), 0, dp(12), 0)
            mapArea.setPadding(0, dp(3), 0, dp(3))
            addView(mapArea, LinearLayout.LayoutParams(0, -2, 1f))
            undoVertexButton = mapAction(FieldIcon.UNDO_VERTEX) { undoBoundaryChange() }.also {
                addView(it, dp(48), dp(48))
            }
        }, -1, -2)
        header.addView(mapComparisonStatus, -1, -2)
        header.addView(mapTripStatus, -1, -2)
        updateMapStatus()
    }

    private fun showVisibleObjects() {
        val visible = map.visibleSites(header.bottom)
        fieldworkSheet("Объекты в видимой области", scrollBody = false) { dialog ->
            if (visible.isEmpty()) {
                empty(this, "Объектов нет", "Переместите карту или измените масштаб.")
            } else {
                val results = ListView(this@MainActivity).apply {
                    adapter = object : android.widget.ArrayAdapter<Site>(
                        this@MainActivity, android.R.layout.simple_list_item_2, android.R.id.text1, visible
                    ) {
                        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                            val row = super.getView(position, convertView, parent)
                            row.minimumHeight = dp(48)
                            val site = visible[position]
                            row.findViewById<TextView>(android.R.id.text1).text = site.title
                            row.findViewById<TextView>(android.R.id.text2).text = site.inventory.ifBlank { site.symbol.title }
                            return row
                        }
                    }
                    setOnItemClickListener { _, _, index, _ ->
                        dialog.dismiss()
                        showSitePreview(visible[index])
                    }
                }
                addView(results, -1, (screen.height * 0.55f).toInt())
            }
        }
    }

    private enum class CoordinateSource(val title: String, val icon: FieldIcon) {
        CENTER("Центр карты", FieldIcon.COORDINATES),
        DEVICE("Моя позиция", FieldIcon.POSITION_ARROW),
        POINT("Выбранная точка", FieldIcon.POINT)
    }

    private fun coordinateSource() = when (drawMode) {
        DrawMode.NONE -> if (tiles.coordinatesAtDevice) CoordinateSource.DEVICE else CoordinateSource.CENTER
        DrawMode.POINT, DrawMode.MOVE_POINT -> if (selectedPoint == null) CoordinateSource.CENTER else CoordinateSource.POINT
        DrawMode.AREA, DrawMode.MOVE_AREA -> CoordinateSource.CENTER
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
            title = coordinateSource().title,
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
        fieldPrompt(coordinateSource().title, copyCoordinates(point), coordinates = point.display())
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
        boundaryHistory.clear()
        vertices.clear()
        vertexMeasurements.clear()
        geometrySite = null
        geometryDraft = null
        drawingParentId = null
        selectedPoint = null
        selectedMeasurement = null
        if (locationAction == LocationAction.SELECT_POINT || locationAction == LocationAction.ADD_VERTEX) locationAction = null
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
        val recordedMeasurements = points.mapIndexedNotNull { index, point ->
            (if (points.size == 1) fix else vertexMeasurements[point])?.let { measurement ->
                ResearchEntry(kind = "survey", fields = buildMap {
                    put("number", "Вершина ${index + 1}")
                    put("method", measurement.provider ?: "Геолокация Android")
                    put("instrument", "Телефон")
                    put("measured_at", dateLabel(measurement.measuredAt))
                    put("crs", "WGS 84")
                    put("xyz", point.display() + (measurement.altitude?.let { " · $it м" } ?: ""))
                    if (measurement.altitude != null) put("height_system", "Эллипсоид WGS 84")
                    put("accuracy", "${measurement.accuracy} м" +
                        (measurement.verticalAccuracy?.let { " · по высоте $it м" } ?: ""))
                })
            }
        }
        val original = geometryDraft ?: geometrySite?.let(store::site)?.let { EditorDraft(site = it) }
        if (original != null) {
            val site = requireNotNull(original.site)
            val updated = if (site.vertices == points && recordedMeasurements.isEmpty()) site else site.copy(
                vertices = points, holes = emptyList(), accuracy = fix?.accuracy, altitude = fix?.altitude,
                coordinateOrigin = origin, measuredAt = fix?.measuredAt,
                research = site.research.copy(fields = site.research.fields + ("location_status" to
                    if (fix != null || recordedMeasurements.isNotEmpty()) "Проверен в поле" else "Ориентир по источнику"),
                    entries = site.research.entries + recordedMeasurements)
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
                accuracy = fix?.accuracy, altitude = fix?.altitude, coordinateOrigin = origin, measuredAt = fix?.measuredAt,
                research = ResearchCard(fields = if (recordedMeasurements.isNotEmpty()) mapOf("location_status" to "Проверен в поле")
                    else emptyMap(), entries = recordedMeasurements))
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

    internal fun editGeometry(site: Site, area: Boolean = site.area) {
        val draft = editView?.snapshot() ?: EditorDraft(site = site)
        persistEditorDraft()
        detachEditor()
        tab = Tab.MAP
        render()
        map.show(site)
        geometryDraft = draft
        geometrySite = site.id
        if (area && site.area) vertices.addAll(site.vertices)
        if (!area) selectedPoint = site.takeIf(Site::located)?.anchor
        beginDraw(if (area) DrawMode.MOVE_AREA else DrawMode.MOVE_POINT)
    }

    internal fun centerForPicker(): Coordinate = recentFix()?.point ?: map.center

    internal fun showOnMap(site: Site) {
        if (!site.located) return
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

    private fun captureLocation(action: LocationAction) {
        samples.clear()
        latestCapture = -1
        val sampling = store.setting("survey_preferences")?.optInt("samples", 1) ?: 1
        val cached = recentFix()
        if (sampling == 1 && cached != null) {
            if (meetsAccuracy(cached)) if (action == LocationAction.ADD_VERTEX) addMeasuredVertex(cached) else selectGpsPoint(cached)
        } else requestLocation(action)
    }

    private fun meetsAccuracy(value: LocationFix): Boolean {
        val maximum = store.setting("survey_preferences")?.optDouble("maximum", 0.0) ?: 0.0
        if (maximum <= 0 || value.accuracy <= maximum) return true
        message("Точность ±${value.accuracy.roundToInt()} м. Требуется не хуже ${maximum.roundToInt()} м")
        return false
    }

    private fun processSurveyFix(action: LocationAction, value: LocationFix): Boolean {
        if (!meetsAccuracy(value)) return false
        if (value.elapsedMs <= latestCapture) return false
        latestCapture = value.elapsedMs
        val count = (store.setting("survey_preferences")?.optInt("samples", 1) ?: 1).coerceIn(1, 8)
        samples += value
        if (samples.size < count) {
            message("Замеры · ${samples.size} из $count")
            return false
        }
        val longitude = value.point.longitude + samples.map { longitudeDelta(it.point.longitude, value.point.longitude) }.average()
        val location = Coordinate(samples.map { it.point.latitude }.average(), ((longitude + 540) % 360) - 180)
        val heights = samples.mapNotNull(LocationFix::altitude)
        val captured = if (count == 1) value else value.copy(point = location,
            altitude = heights.takeIf { it.size == count }?.average(),
            accuracy = samples.maxOf(LocationFix::accuracy), provider = "Усреднение $count замеров Android",
            verticalAccuracy = samples.mapNotNull(LocationFix::verticalAccuracy).takeIf { it.size == count }?.maxOrNull())
        samples.clear()
        if (action == LocationAction.ADD_VERTEX) addMeasuredVertex(captured) else selectGpsPoint(captured)
        return true
    }

    private fun addMeasuredVertex(value: LocationFix) {
        if (drawMode != DrawMode.AREA && drawMode != DrawMode.MOVE_AREA) return
        val point = value.point.copy(elevation = value.altitude)
        val count = vertices.size
        addVertex(point)
        if (vertices.size != count) {
            vertexMeasurements[point] = value
            message("Вершина по GPS · ±${value.accuracy.roundToInt()} м")
        }
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
            LocationAction.SELECT_POINT, LocationAction.ADD_VERTEX -> {
                if (!processSurveyFix(requireNotNull(locationAction), value)) {
                    updateMapStatus()
                    return
                }
            }
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
        val uri = webUri(value) ?: return message("Нужна ссылка HTTP или HTTPS")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure { message("На телефоне нет приложения для открытия ссылки") }
    }

    private fun chooseLayers() {
        fieldworkSheet("Слои карты") { dialog ->
            val controls = vertical()
            addView(controls, -1, -2)

            fun redrawControls() {
                controls.removeAllViews()
                controls.addView(horizontal().apply {
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
                            if (layer != MapLayer.entries.last()) marginEnd = dp(8)
                        })
                    }
                }, -1, -2)
                val routeId = trackTripId ?: hiddenTripId ?: TripRecording.tripId
                val route = routeId?.let(store.trips::get)
                controls.addSpace(12)
                controls.addView(layerSwitch("Маршрут выезда", trackTripId != null, enabled = route != null) { shown ->
                    if (route != null) {
                        if (shown) {
                            selectTripTrack(route.id)
                            redrawControls()
                        } else {
                            dialog.dismiss()
                            hideTripTrack(route.id)
                        }
                    }
                }, -1, -2)
                if (route != null) controls.addView(label(route.title, 13f, Palette.muted), -1, -2)
                if (trackTripId == null && hiddenTripId != null) {
                    controls.addView(action("Показать маршрут на карте") {
                        dialog.dismiss()
                        showTripOnMap(hiddenTripId ?: return@action)
                    }, -1, -2)
                }
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
                    group.addView(label("MBTiles, SQLiteDB, GeoPackage, OZF2 + .map", 13f, Palette.muted))
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
                        group.addSpace(8)
                        group.addView(choiceAction("Подписи · ${source.title}") {
                            chooseOption("Источник подписей", ReferenceSource.entries.map(ReferenceSource::title), source.title) { title ->
                                tiles.onlineReferenceSource = ReferenceSource.entries.first { it.title == title }
                                map.refresh()
                                redrawControls()
                            }
                        }, -1, -2)
                    }
                    group.addSpace(8)
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
                controls.addSpace(8)
                controls.addView(action("Проверить покрытие офлайн") {
                    dialog.dismiss()
                    checkOfflineCoverage()
                }, -1, -2)
                controls.addSpace(8)
                controls.addView(action("Настройки карт") {
                    dialog.dismiss()
                    switch(Tab.SETTINGS)
                }, -1, -2)
            }

            redrawControls()
        }
    }

    private fun checkOfflineCoverage() {
        val points = map.coverageSamples()
        val zoom = map.zoom.roundToInt().coerceIn(0, 22)
        val zooms = listOf((zoom - 2).coerceAtLeast(0), zoom, (zoom + 2).coerceAtMost(22)).distinct()
        val slots = listOfNotNull(tiles.baseArchive(tiles.layer),
            MapPackage.HISTORICAL.takeIf { tiles.historicalEnabled },
            MapPackage.REFERENCES.takeIf { tiles.layer == MapLayer.HYBRID && tiles.info(it) != null })
        withIo("Проверка локальных карт", { tiles.coverage(points, zooms, slots) }) { checks ->
            fieldworkSheet("Покрытие видимой области") { dialog ->
                addView(label("Выборочная проверка по сетке 5 × 5. Увеличенные фрагменты показываются отдельно.", 14f, Palette.muted))
                for (check in checks) {
                    val name = tiles.info(check.slot)?.name ?: "Слой без пакета"
                    addSpace(8)
                    addView(label("$name · масштаб ${check.zoom}", 15f, Palette.heading, true))
                    addView(label("Свои фрагменты · ${check.native}   Увеличенные · ${check.enlarged}   Пробелы · ${check.missing.size}",
                        14f, Palette.ink))
                    if (check.missing.isNotEmpty()) addView(action("Показать ближайший пробел") {
                        dialog.dismiss()
                        map.moveTo(check.missing.minBy { kotlin.math.abs(it.latitude - map.center.latitude) +
                            kotlin.math.abs(longitudeDelta(it.longitude, map.center.longitude)) }, check.zoom.toDouble())
                    }, -1, -2)
                }
            }
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

    private var catalogResearchFilter = ""
    private var diarySearch = ""

    private fun showSites() {
        val panel = vertical().apply { setPadding(dp(14), dp(5), dp(14), dp(8)) }
        val query = searchField("Поиск объектов", search).apply {
            contentDescription = "Поиск по названию, номеру, месту или источнику"
        }
        val controls = horizontal()
        controls.addView(query, LinearLayout.LayoutParams(0, -2, 1f))
        fun filterButton(icon: FieldIcon, value: String?, open: () -> Unit) {
            val description = listOfNotNull(icon.description, value).joinToString(" · ")
            val button = iconAction(icon, description, primary = value != null, click = open).apply {
                isSelected = value != null
            }
            controls.addView(button, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        }
        val selectedType = filter?.let { objectType(it).title }
        filterButton(FieldIcon.FILTER_TYPES, selectedType, ::chooseFilter)
        filterButton(FieldIcon.FILTER_LOCATION, catalogResearchFilter.ifBlank { null }) {
            val statuses = listOf("Все привязки", "Без координат", "Ориентир по источнику", "Проверен в поле")
            chooseOption("Фильтр по привязке", statuses, catalogResearchFilter.ifBlank { statuses.first() }) { status ->
                catalogResearchFilter = status.takeUnless { it == statuses.first() }.orEmpty()
                resetCatalogPosition()
                render()
            }
        }
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
        var pageSize = 160
        var matches = emptyList<CatalogEntry>()
        val more = action("Показать ещё") { }
        val adapter = object : BaseAdapter() {
            override fun getCount() = matches.size
            override fun getItem(position: Int) = matches[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                ((convertView as? CatalogRow) ?: CatalogRow(this@MainActivity)).apply { bind(getItem(position)) }
        }
        results.adapter = adapter
        results.setOnItemClickListener { _, _, position, _ -> openSite(adapter.getItem(position).id) }
        fun showMatches() {
            val withoutCoordinates = catalogResearchFilter == "Без координат"
            val status = catalogResearchFilter.takeIf { it.isNotBlank() && !withoutCoordinates }
            val page = store.catalogPage(search, filter, status, pageSize + 1, withoutCoordinates)
            matches = page.take(pageSize)
            adapter.notifyDataSetChanged()
            count.text = if (page.size > pageSize) "Показаны первые ${matches.size}" else "Найдено · ${matches.size}"
            more.visibility = if (page.size > pageSize) View.VISIBLE else View.GONE
            emptyState.removeAllViews()
            if (matches.isEmpty()) empty(emptyState, "Объекты не найдены",
                if (search.isBlank() && filter == null) "Создайте объект на карте или отдельно в каталоге."
                else "Измените поиск или фильтры.")
        }
        more.setOnClickListener { pageSize += 160; showMatches() }
        panel.addView(addAction("Объект без координат", FieldIcon.POINT) {
            chooseType("Условный знак объекта") { type ->
                previousTab = tab
                openDraft(EditorDraft(site = Site(title = type.title, type = type.id, vertices = emptyList()), isNew = true))
            }
        }, -1, -2)
        query.watch {
            search = it
            pageSize = 160
            showMatches()
            results.setSelection(0)
        }
        panel.addSpace(8)
        panel.addView(controls, -1, -2)
        val selectedFilters = listOfNotNull(selectedType, catalogResearchFilter.ifBlank { null })
        if (selectedFilters.isNotEmpty()) {
            panel.addSpace(6)
            panel.addView(label(selectedFilters.joinToString(" · ", "Фильтры: "), 13f, Palette.heading), -1, -2)
        }
        panel.addView(count)
        panel.addView(resultsPanel, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(more, -1, -2)
        content.addView(panel)
        root.requestFocus()
        showMatches()
        results.setSelectionFromTop(catalogIndex, catalogOffset)
    }

    private fun chooseFilter() {
        val names = listOf("Все типы") + objectTypes.map(ObjectType::title)
        chooseOption("Фильтр по типу", names, filter?.let { objectType(it).title } ?: names.first()) { name ->
            filter = objectTypes.firstOrNull { it.title == name }?.id
            resetCatalogPosition()
            render()
        }
    }

    private fun showNotes() {
        val loading = vertical().apply { setPadding(dp(14), dp(5), dp(14), dp(8)) }
        loading.addView(horizontal().apply {
            dividerDrawable = panel(android.graphics.Color.TRANSPARENT).apply { setSize(dp(6), 1) }
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            for (page in DiaryPage.entries) {
                val count = if (page == DiaryPage.DRAFTS) store.draftCount() else 0
                val title = if (count > 0) "${page.title} · $count" else page.title
                addView(diaryPageAction(title, page == diaryPage) {
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
        if (trip != null) loading.addView(label("Выезд · ${trip.title}", 14f, Palette.heading, true)
            .apply { setPadding(dp(2), 0, dp(2), dp(5)) }, -1, -2)
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
                card(loading, trip.title, "Текущий выезд") { showTripDetails(trip.id) }
            }
        }
        val query = searchField("Поиск в записях", diarySearch)
        loading.addView(query, -1, -2)
        loading.addSpace(8)
        val records = vertical()
        val status = label("Загрузка записей…", 14f, Palette.muted)
        records.addView(status)
        loading.addView(records, LinearLayout.LayoutParams(-1, 0, 1f))
        val journal = store
        fun loadRecords() {
            val term = diarySearch
            diaryReads.execute {
            val rows = runCatching { journal.noteSummaries(tripId = filter?.id, query = term.trim()) }
            handler.post {
                if (isDestroyed || loading.parent !== content || term != diarySearch) return@post
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
        query.watch { diarySearch = it; loadRecords() }
        loadRecords()
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
        if (tab == Tab.NOTES) refreshDiary()
        message("Маршрут скрыт") {
            if (hiddenTripId == id && store.trips.get(id) != null) {
                selectTripTrack(id)
                if (tab == Tab.NOTES) refreshDiary()
            }
        }
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
        val points = route.ifEmpty { contents.sites.filter(Site::located).map(Site::anchor) }
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

    internal fun exportTrip(id: String, forQgis: Boolean, edition: Int? = null) {
        val trip = store.trips.get(id) ?: return
        val name = trip.title.replace(Regex("""[^\p{L}\p{N}._ -]"""), "_").trim().take(80).ifBlank { "Выезд" }
        pendingTripExport = id
        pendingTripEdition = edition
        if (forQgis) exportDocument(TRIP_KIT, "application/zip", "$name${edition?.let { "-редакция-$it" }.orEmpty()}.zip")
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
            list.addView(label(title, 14f, Palette.muted, true).apply {
                setPaddingRelative(dp(14), dp(6), 0, 0)
                if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            })
            list.addSpace(8)
            return vertical().apply {
                background = panel(Palette.white, 20)
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
        maps.addView(layerSwitch("Офлайн-режим", tiles.offline) { toggleOffline() }.apply {
            setPaddingRelative(dp(14), dp(8), dp(14), dp(8))
        }, -1, -2)
        for (layer in MapLayer.entries) {
            val info = tiles.info(layer.archive)
            val detail = when {
                info != null -> "${info.name} · ${info.format.title} · масштаб ${info.minZoom}–${info.maxZoom}"
                layer == MapLayer.HYBRID && tiles.info(MapPackage.SATELLITE) != null -> "Используется офлайн-спутник · можно подключить свою карту"
                else -> "MBTiles, SQLiteDB, GeoPackage, OZF2 + .map"
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
            val formats = if (slot == MapPackage.HISTORICAL) "MBTiles, SQLiteDB, GeoPackage, OZF2 + .map"
                else "MBTiles, SQLiteDB или GeoPackage"
            row(maps, title, archive?.let { "${it.name} · ${it.format.title}" } ?: formats) {
                pickDocument(request, "*/*")
            }
        }
        row(maps, "Точность полевых замеров", "Допуск и усреднение GPS для точки или вершины") {
            val old = store.setting("survey_preferences")
            editStructuredFields("Точность полевых замеров",
                linkedMapOf("maximum" to "Допустимая погрешность в метрах (0 — без ограничения)", "samples" to "Число замеров (1–8)"),
                mapOf("maximum" to (old?.optDouble("maximum", 0.0) ?: 0.0).toString(),
                    "samples" to (old?.optInt("samples", 1) ?: 1).toString())) {
                val accuracy = it["maximum"]?.replace(',', '.')?.toDoubleOrNull()
                val count = it["samples"]?.toIntOrNull()
                if (accuracy == null || !accuracy.isFinite() || accuracy < 0 || count == null || count !in 1..8) {
                    message("Укажите допустимую погрешность от 0 и число замеров от 1 до 8")
                } else store.saveSetting("survey_preferences", org.json.JSONObject().put("maximum", accuracy).put("samples", count))
            }
        }
        row(maps, "Реквизиты исторической карты", "Год, лист, масштаб, архив и геопривязка") {
            val old = store.setting("historical_map") ?: org.json.JSONObject()
            editStructuredFields("Историческая карта", linkedMapOf("title" to "Название", "sheet" to "Лист",
                "surveyed" to "Год съёмки", "published" to "Год издания", "scale" to "Масштаб оригинала",
                "archive" to "Архивный шифр", "method" to "Метод геопривязки", "error" to "Точность привязки",
                "url" to "Постоянный адрес"), old.keys().asSequence().associateWith(old::getString)) {
                store.saveSetting("historical_map", org.json.JSONObject(it))
            }
        }
        row(maps, "Свой онлайн-источник XYZ",
            if (tiles.customUrl.isBlank()) "Карта по умолчанию · OpenStreetMap" else tiles.customCredit) { customSource() }
        val backup = section("Резервные копии")
        row(backup, "Сохранить полную копию", "ZIP · черновики, журнал, редакции и вложения") {
            withIo("Проверка состава копии", { store.backupInfo() }) { info ->
                val bytes = android.text.format.Formatter.formatShortFileSize(this, info.bytes)
                val free = android.text.format.Formatter.formatShortFileSize(this, info.free)
                fieldPrompt("Полная полевая копия", PromptAction("Выбрать место") {
                    exportDocument(BACKUP, "application/zip", "field-journal-${shortDate()}.zip")
                }, detail = "Объекты · ${info.sites}\nЗаписи · ${info.notes}\nЧерновики · ${info.drafts}\n" +
                    "Вложения · ${info.files} ($bytes)\nСвободно на устройстве · $free")
            }
        }
        row(backup, "Сохранить копию по частям", "Полная копия в отдельной папке · части до 512 МиБ") {
            withIo("Проверка состава копии", { store.backupInfo() }) { info ->
                val size = android.text.format.Formatter.formatShortFileSize(this, info.bytes)
                val free = android.text.format.Formatter.formatShortFileSize(this, info.free)
                fieldPrompt("Копия по частям", PromptAction("Выбрать папку") {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), BACKUP_PARTS_WRITE)
                }, detail = "Вложения · $size · свободно · $free. Для временной сборки нужна свободная память на телефоне.")
            }
        }
        val transfer = section("Перенос и импорт")
        row(transfer, "Открыть ZIP или GeoJSON", "Восстановление копии или импорт объектов") { pickDocument(IMPORT, "*/*") }
        row(transfer, "Импорт регионального CSV", "Карточки с координатами и без них") { pickDocument(CATALOG_CSV, "*/*") }
        row(transfer, "Разобрать таблицы из дневника", "Создать карточки из вложений археологического каталога") {
            fieldPrompt("Создать карточки из таблиц?", PromptAction("Импортировать") {
                withIo("Чтение региональных таблиц", { store.expandAttachedCatalog() }) { showImportResult(it) }
            }, detail = "Будут добавлены отсутствующие ID. Региональные CSV сохранятся в записях.")
        }
        row(transfer, "Восстановить копию из частей", "Выберите папку с индексом и всеми частями") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), BACKUP_PARTS_READ)
        }
        row(transfer, "Сравнить импортированные правки", "Посмотреть поля и выбрать редакцию") { showImportConflicts() }
        val gis = section("QGIS и другие ГИС")
        row(gis, "Экспорт объектов GeoJSON", "WGS 84 · точки, контуры и поля карточек") { exportDocument(GEOJSON, "application/geo+json", "field-journal-${shortDate()}.geojson") }
        row(gis, "Экспорт выезда", "Маршрут GPX или комплект для QGIS с объектами и записями") { chooseTripExport() }
        val publication = section("Публикация")
        row(publication, "Публичная выгрузка", "Упрощённые координаты и выбранные копии фотографий") { choosePublicExport() }
        val project = section("Проект")
        row(project, "Источники проекта", "Архивы, публикации, карты и интернет-ресурсы") { chooseSource() }
        row(project, "Описание проекта", "Исполнители, методы, словарь полей и версия") {
            val current = store.setting("project") ?: org.json.JSONObject()
            editStructuredFields("Описание проекта", linkedMapOf("title" to "Название", "authors" to "Исполнители",
                "methods" to "Методы", "schema" to "Словарь полей", "version" to "Версия данных"),
                current.keys().asSequence().associateWith(current::getString)) {
                store.saveSetting("project", org.json.JSONObject(it))
            }
        }
        list.addSpace(10)
        list.addView(label("Подтверждённые изменения хранятся в локальной базе. Файлы карт хранятся отдельно от резервных копий.", 13f, Palette.muted))
    }

    private fun showImportResult(result: ImportResult) {
        render()
        AlertDialog.Builder(this).setTitle("Импорт завершён").setMessage(listOf(
            "Объекты · ${result.sites}", "Записи · ${result.notes}", "Выезды · ${result.trips}",
            "Черновики · ${result.drafts}", "Существующие и пропущенные · ${result.skipped}",
            "Редакции для проверки · ${result.conflicts}"
        ).joinToString("\n")).setPositiveButton("Готово", null).show()
    }

    private fun previewGeoImport(plan: GeoImportPlan) {
        fieldworkSheet("Проверка импорта GeoJSON") { dialog ->
            val rows = ListView(this@MainActivity).apply {
                adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1,
                    plan.rows.map { "${it.name} · ${it.type} · ${it.status}" })
            }
            addView(label("Всего · ${plan.rows.size} · можно обработать · ${plan.rows.count(GeoImportRow::accepted)}",
                14f, Palette.muted))
            addView(rows, -1, dp(260))
            addView(action("Импортировать", true) {
                dialog.dismiss()
                withIo("Импорт GeoJSON", { store.applyGeoImport(plan) }) { showImportResult(it) }
            }.apply { isEnabled = plan.rows.any(GeoImportRow::accepted) }, -1, -2)
        }
    }

    private fun showImportConflicts() {
        fieldworkSheet("Редакции карточек") { dialog ->
            val conflicts = store.conflicts()
            if (conflicts.isEmpty()) addView(label("Различающихся редакций пока нет", 14f, Palette.muted))
            for (conflict in conflicts) {
                val incoming = recordFields(conflict.kind, conflict.incoming)
                val local = recordFields(conflict.kind, conflict.original)
                val fields = (local.keys + incoming.keys).filter { local[it] != incoming[it] }
                val title = incoming["title"]?.trim('"') ?: conflict.recordId
                val status = when (conflict.status) {
                    "local" -> "Оставлена локальная"
                    "incoming" -> "Принята импортированная"
                    else -> "Ожидает выбора"
                }
                card(this, title, "$status · ${fields.size} полей") {
                    dialog.dismiss()
                    fieldworkSheet(title) { detail ->
                        fun version(value: org.json.JSONObject) =
                            if (conflict.kind == "sites") value.getJSONObject("properties") else value
                        for ((name, value) in listOf("Локальная" to version(conflict.original),
                            "Импортированная" to version(conflict.incoming))) {
                            val time = value.optLong("updated").takeIf { it > 0 }?.let(::dateLabel).orEmpty()
                            addView(label("$name · ${value.optString("author")} · $time", 14f, Palette.ink, true))
                        }
                        for (key in fields) {
                            addSpace(7)
                            addView(label(key, 14f, Palette.heading, true))
                            addView(label("На телефоне · ${local[key].orEmpty().take(2000)}", 14f, Palette.ink))
                            addView(label("Из импорта · ${incoming[key].orEmpty().take(2000)}", 14f, Palette.ink))
                        }
                        if (conflict.status == "pending") for ((name, take) in listOf(
                            "Принять импортированную" to true, "Оставить локальную" to false
                        )) {
                            addSpace(6)
                            addView(action(name, take) {
                                runCatching { store.resolveConflict(conflict.id, take) }
                                    .onSuccess { detail.dismiss(); showImportConflicts() }
                                    .onFailure { message(it.message ?: "Не удалось выбрать редакцию") }
                            }, -1, -2)
                        }
                    }
                }
            }
        }
    }

    private fun choosePublicExport() {
        val options = arrayOf("Без координат", "Округление до 0,1°", "Округление до 0,01°")
        AlertDialog.Builder(this).setTitle("Точность публичной выгрузки").setItems(options) { _, index ->
            publicationDecimals = index - 1
            exportDocument(PUBLIC_ZIP, "application/zip", "field-journal-public-${shortDate()}.zip")
        }.setPositiveButton("Отмена", null).show()
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

    private fun pickOziCompanion(selection: OziSelection) {
        val detail = if (selection.isImage) "Выберите файл привязки .map, который относится к этому изображению."
            else "Выберите файл изображения .ozf2, указанный в файле привязки."
        fieldPrompt("Подключить карту OziExplorer", PromptAction("Выбрать файл") {
            pendingOzi = selection
            pickDocument(OZI_COMPANION, "*/*")
        }, detail = detail)
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
        val saveContext = editor.saveContext()
        fieldPrompt(
            title = question,
            detail = listOfNotNull(editor.adoptionNotice(), saveContext).joinToString("\n").ifBlank { null },
            primary = PromptAction("Сохранить") {
                if (editor.saveConfirmed()) {
                    after()
                    message(saveContext?.let { "Сохранено · $it" } ?: "Сохранено")
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
                withIo<RasterSelection>("Чтение файла карты", abandoned = { (it as? ReadyRaster)?.raster?.close() }, work = {
                    val name = runCatching { documentName(resolver, uri) }.getOrDefault("")
                    val extension = name.substringAfterLast('.', "").lowercase()
                    if (extension == "ozf2" || extension == "map") {
                        require(slot != MapPackage.REFERENCES) { "Для слоя подписей нужен пакет с прозрачными тайлами" }
                        OziSelection(slot, uri, extension == "ozf2")
                    } else {
                        requireNotNull(resolver.openInputStream(uri)) { "Не удалось открыть файл карты" }.use {
                            ReadyRaster(mapTiles.prepare(slot, it, name))
                        }
                    }
                }) { selection ->
                    when (selection) {
                        is OziSelection -> pickOziCompanion(selection)
                        is ReadyRaster -> {
                            if (selection.raster.options.size == 1) finishRasterImport(selection.raster)
                            else {
                                preparedRaster = selection.raster
                                showRasterChoice()
                            }
                        }
                    }
                }
            }
            OZI_COMPANION -> {
                val selection = pendingOzi
                pendingOzi = null
                if (resultCode != RESULT_OK || uri == null || selection == null) return
                val image = if (selection.isImage) selection.first else uri
                val sidecar = if (selection.isImage) uri else selection.first
                val mapTiles = tiles
                withIo("Подключение OZF2", abandoned = PreparedRaster::close, work = {
                    val name = documentName(resolver, image)
                    requireNotNull(resolver.openInputStream(sidecar)) { "Не удалось открыть файл привязки .map" }.use { map ->
                        requireNotNull(resolver.openInputStream(image)) { "Не удалось открыть изображение OZF2" }.use { raster ->
                            mapTiles.prepareOzf2(selection.slot, raster, map, name)
                        }
                    }
                }) { finishRasterImport(it) }
            }
            IMPORT -> {
                if (resultCode != RESULT_OK || uri == null) return
                withIo("Чтение файла", {
                    requireNotNull(resolver.openInputStream(uri)).buffered().use { input ->
                        input.mark(4)
                        val zip = input.read() == 'P'.code && input.read() == 'K'.code
                        input.reset()
                        if (zip) journal.importBackup(input) to null else null to journal.inspectGeoJson(input)
                    }
                }) { (result, plan) ->
                    result?.let(::showImportResult)
                    plan?.let(::previewGeoImport)
                }
            }
            CATALOG_CSV -> {
                if (resultCode != RESULT_OK || uri == null) return
                withIo("Импорт регионального CSV", {
                    requireNotNull(resolver.openInputStream(uri)).use(journal::importCatalog)
                }) { showImportResult(it) }
            }
            BACKUP_PARTS_WRITE, BACKUP_PARTS_READ -> {
                if (resultCode != RESULT_OK || uri == null) return
                val folder = BackupFolder(resolver, uri)
                if (requestCode == BACKUP_PARTS_WRITE) withIo("Сохранение полевой копии по частям", {
                    journal.exportBackupParts(folder::write)
                }) { message("Копия по частям сохранена") }
                else withIo("Проверка частей и восстановление", {
                    folder.read().use { journal.importBackup(it, multipart = true) }
                }) { showImportResult(it) }
            }
            TRIP_GPX, TRIP_KIT -> {
                val id = pendingTripExport
                pendingTripExport = null
                val edition = pendingTripEdition
                pendingTripEdition = null
                if (resultCode != RESULT_OK || uri == null || id == null) return
                withIo("Экспорт выезда", {
                    val openOutput = { requireNotNull(resolver.openOutputStream(uri, "wt")) }
                    if (requestCode == TRIP_GPX) journal.exportTripGpx(id, openOutput)
                    else journal.exportTripKit(id, edition, openOutput)
                }) { message("Выезд экспортирован") }
            }
            BACKUP, GEOJSON, PUBLIC_ZIP -> {
                if (resultCode != RESULT_OK || uri == null) return
                withIo("Экспорт данных", {
                    val openOutput = { requireNotNull(resolver.openOutputStream(uri, "wt")) }
                    when (requestCode) {
                        BACKUP -> journal.exportBackup(full = true, openOutput = openOutput)
                        PUBLIC_ZIP -> journal.exportPublic(publicationDecimals, openOutput)
                        else -> journal.exportGeoJson(openOutput)
                    }
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

    internal fun message(text: String, undo: (() -> Unit)? = null) {
        handler.removeCallbacks(hideNotice)
        noticeText.text = text
        noticeAction.visibility = if (undo == null) View.GONE else View.VISIBLE
        noticeAction.setOnClickListener {
            dismissNotice()
            undo?.invoke()
        }
        notice.visibility = View.VISIBLE
        positionNotice()
        val duration = if (undo == null) 3000 else 6000
        val accessibility = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        val timeout = if (android.os.Build.VERSION.SDK_INT >= 29 && undo != null) {
            accessibility.getRecommendedTimeoutMillis(duration,
                android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT or
                    android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS)
        } else duration
        handler.postDelayed(hideNotice, timeout.toLong())
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
        private const val CATALOG_CSV = 23
        private const val PUBLIC_ZIP = 24
        private const val BACKUP_PARTS_WRITE = 25
        private const val BACKUP_PARTS_READ = 26
        private const val OZI_COMPANION = 27
        private val io = Executors.newSingleThreadExecutor()
        private val diaryReads = Executors.newSingleThreadExecutor()

        private fun documentName(resolver: ContentResolver, uri: Uri) =
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty()
    }
}
