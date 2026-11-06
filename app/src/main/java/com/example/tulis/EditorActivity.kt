package com.example.tulis

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.tulis.canvas.CanvasListener
import com.example.tulis.canvas.NoteCanvasView
import com.example.tulis.export.ExportJob
import com.example.tulis.export.ExportOptions
import com.example.tulis.export.Exporter
import com.example.tulis.model.NoteMode
import com.example.tulis.model.NotebookMeta
import com.example.tulis.model.PageContent
import com.example.tulis.model.PageOrientation
import com.example.tulis.model.PageStack
import com.example.tulis.model.pageH
import com.example.tulis.model.pageW
import com.example.tulis.model.TextItem
import com.example.tulis.model.Tool
import com.example.tulis.storage.NotebookRepository
import com.example.tulis.ui.ExportProgressDialog
import com.example.tulis.ui.Fonts
import com.example.tulis.ui.Icon
import com.example.tulis.ui.IconView
import com.example.tulis.ui.MinimapView
import com.example.tulis.ui.Palette
import com.example.tulis.ui.SketchDrawable
import com.example.tulis.ui.SketchSlider
import com.example.tulis.ui.UiPrefs
import com.example.tulis.ui.UndoBar
import com.example.tulis.ui.showExportDialog
import com.example.tulis.ui.showPaperDialog

class EditorActivity : Activity() {

    enum class ToolDock { TOP, BOTTOM, LEFT, RIGHT }

    companion object {
        const val EXTRA_ID = "id"
        private const val REQ_EXPORT = 7001
    }

    private lateinit var repo: NotebookRepository
    private var meta: NotebookMeta? = null
    private var notebookId: String = ""

    private lateinit var rootLayout: FrameLayout
    private lateinit var canvasView: NoteCanvasView
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var btnUndo: IconView
    private lateinit var btnRedo: IconView

    private var chipCenter: View? = null
    private var chipZoom: TextView? = null
    private var tabletZoomPill: TextView? = null
    private var tabletPagePill: TextView? = null
    private var minimapView: MinimapView? = null

    private var pageBar: LinearLayout? = null
    private var btnPrevPage: IconView? = null
    private var btnNextPage: IconView? = null
    private var pageIndicator: TextView? = null
    private var btnAddPage: View? = null
    private var btnDeletePage: View? = null
    private var tabletBtnDeletePage: View? = null

    private var bottomToolbar: LinearLayout? = null
    private val toolButtons = mutableMapOf<Tool, View>()
    private val toolDots = mutableMapOf<Tool, View>()

    private var tabletPageRail: View? = null
    private var tabletSidePanel: View? = null
    private var tabletToolPanel: View? = null
    private var tabletBottomRow: LinearLayout? = null
    private var tabletToolDock: ToolDock = ToolDock.TOP
    private var tabletThumbListContainer: LinearLayout? = null
    private var tabletSideColorViews = mutableMapOf<Int, View>()
    private var tabletSideSlider: SketchSlider? = null
    private var tabletSideSwitchBtn: FrameLayout? = null
    private var tabletSideKnob: View? = null
    private var phoneSwitchBtn: FrameLayout? = null
    private var phoneSwitchKnob: View? = null
    private var tabletStrokePreview: View? = null
    private var tabletSideTitle: TextView? = null
    private var tabletSidePanelStrip: View? = null
    private var tabletSidePanelFull: View? = null
    private var isPanelCollapsed: Boolean = false
    private var isRailHidden: Boolean = false
    private var isMetaDirty: Boolean = false
    private val thumbMemoryCache = object : LruCache<String, Bitmap>(32) {}
    private val thumbExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)

    private var activeTool: Tool = Tool.PEN
    private var activeColor: Int = Palette.INK
    private var activeStrokeWidth: Float = 4f
    private var fingerCanDraw: Boolean = true

    private var penWidth = 4f
    private var markerWidth = 8f
    private var highlighterWidth = 20f

    private var penColor = Palette.INK
    private var markerColor = Palette.INK
    private var highlighterColor = Palette.YELLOW

    private var currentPageIndex: Int = 0
    private var pageLoadToken: Int = 0
    private var lastSavedRevision: Int = -1
    private var pageStack: PageStack? = null
    private val loadingPages = mutableSetOf<Int>()
    private var lastZoomPct: Int = -1
    private var pillZoomPct: Int = -1

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isAutosavePending = false
    private val autosaveRunnable = Runnable {
        flushAutosave(immediateThumb = false)
    }

    private var optionsPopup: PopupWindow? = null

    private val isTablet: Boolean
        get() = UiPrefs.physicalWidthDp(this) >= 840f

    private val isPhoneLandscape: Boolean
        get() = !isTablet && UiPrefs.physicalHeightDp(this) < 500f

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiPrefs.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repo = NotebookRepository(this)
        notebookId = intent.getStringExtra(EXTRA_ID) ?: ""
        if (notebookId.isEmpty()) {
            finish()
            return
        }

        meta = repo.loadMeta(notebookId)
        val currentMeta = meta
        if (currentMeta == null) {
            finish()
            return
        }

        val prefs = getSharedPreferences("tulis_prefs", MODE_PRIVATE)
        fingerCanDraw = prefs.getBoolean("finger_draws", true)
        penWidth = prefs.getFloat("width_pen", 4f).coerceIn(1f, 16f)
        markerWidth = prefs.getFloat("width_marker", 8f).coerceIn(2f, 28f)
        highlighterWidth = prefs.getFloat("width_highlighter", 20f).coerceIn(6f, 48f)
        isPanelCollapsed = prefs.getBoolean("panel_collapsed", false)
        isRailHidden = prefs.getBoolean("rail_hidden", false)
        tabletToolDock = try {
            ToolDock.valueOf(prefs.getString("tool_dock", ToolDock.TOP.name) ?: ToolDock.TOP.name)
        } catch (_: Exception) {
            ToolDock.TOP
        }

        currentPageIndex = if (currentMeta.mode == NoteMode.PAGED) {
            currentMeta.lastPage.coerceIn(0, (currentMeta.pageCount - 1).coerceAtLeast(0))
        } else {
            0
        }

        activeStrokeWidth = getWidthForTool(activeTool)

        buildUi(currentMeta)
        applyToolSelection(Tool.PEN)

        if (currentMeta.mode == NoteMode.INFINITE) {
            canvasView.viewport.set(currentMeta.vpScale, currentMeta.vpTx, currentMeta.vpTy)
            updateZoomIndicator()
            loadPage(currentPageIndex)
        } else {
            val pw = currentMeta.orientation.pageW
            val ph = currentMeta.orientation.pageH
            val s = PageStack(currentMeta.pageCount, pw, ph, PageStack.GAP)
            pageStack = s
            canvasView.setPageSize(pw, ph)
            canvasView.setStack(s)
            val windowStart = (currentPageIndex - 1).coerceAtLeast(0)
            val windowEnd = (currentPageIndex + 1).coerceAtMost(currentMeta.pageCount - 1)
            for (idx in windowStart..windowEnd) {
                if (s.get(idx) == null && !loadingPages.contains(idx)) {
                    loadingPages.add(idx)
                    val genAtRequest = s.generation
                    repo.loadPage(notebookId, idx) { loaded ->
                        loadingPages.remove(idx)
                        if (s.generation == genAtRequest && s.get(idx) == null && loaded != null) {
                            s.attach(idx, loaded)
                            ensurePageThumb(idx, loaded)
                            canvasView.invalidatePages()
                        }
                    }
                }
            }
            canvasView.post {
                canvasView.scrollToPage(currentMeta.lastPage)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        optionsPopup?.dismiss()
        val m = meta ?: return
        toolButtons.clear()
        toolDots.clear()
        tabletSideColorViews.clear()
        tabletSideSlider = null
        tabletPageRail = null
        tabletSidePanel = null
        tabletToolPanel = null
        tabletBottomRow = null
        tabletThumbListContainer = null
        tabletBtnDeletePage = null
        tabletSideSwitchBtn = null
        tabletSideKnob = null
        tabletStrokePreview = null
        tabletSideTitle = null
        tabletSidePanelStrip = null
        tabletSidePanelFull = null
        chipCenter = null
        chipZoom = null
        tabletZoomPill = null
        tabletPagePill = null
        minimapView = null
        pageBar = null
        bottomToolbar = null

        buildUi(m)
        applyToolSelection(activeTool)
        updateUndoRedoState()
        if (m.mode == NoteMode.INFINITE) {
            updateZoomIndicator()
        } else {
            canvasView.setStack(pageStack)
            updatePageIndicator()
            if (!canvasView.hasMovedViewport) {
                canvasView.fitPage()
            }
        }
    }

    private fun dp(v: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
    }

    private fun toolLabelSp(baseSp: Float): Float {
        val scale = resources.configuration.fontScale.coerceAtMost(1.3f)
        return (baseSp * scale) / resources.configuration.fontScale
    }

    private fun widthRangeForTool(t: Tool): FloatArray {
        return when (t) {
            Tool.PEN -> floatArrayOf(1f, 16f, 0.5f, 4f)
            Tool.MARKER -> floatArrayOf(2f, 28f, 1f, 8f)
            Tool.HIGHLIGHTER -> floatArrayOf(6f, 48f, 1f, 20f)
            else -> floatArrayOf(1f, 16f, 0.5f, 4f)
        }
    }

    private fun getWidthForTool(t: Tool): Float {
        return when (t) {
            Tool.PEN -> penWidth
            Tool.MARKER -> markerWidth
            Tool.HIGHLIGHTER -> highlighterWidth
            else -> 4f
        }
    }

    private fun setWidthForTool(t: Tool, w: Float) {
        val range = widthRangeForTool(t)
        val clamped = w.coerceIn(range[0], range[1])
        when (t) {
            Tool.PEN -> penWidth = clamped
            Tool.MARKER -> markerWidth = clamped
            Tool.HIGHLIGHTER -> highlighterWidth = clamped
            else -> {}
        }
    }

    private fun getColorForTool(t: Tool): Int {
        return when (t) {
            Tool.PEN -> penColor
            Tool.MARKER -> markerColor
            Tool.HIGHLIGHTER -> highlighterColor
            else -> activeColor
        }
    }

    private fun setColorForTool(t: Tool, c: Int) {
        when (t) {
            Tool.PEN -> penColor = c
            Tool.MARKER -> markerColor = c
            Tool.HIGHLIGHTER -> highlighterColor = c
            else -> {}
        }
    }

    private fun buildUi(m: NotebookMeta) {
        rootLayout = FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(if (m.mode == NoteMode.PAGED) Palette.DESK else Palette.CANVAS)
        }

        canvasView = NoteCanvasView(this).apply {
            mode = m.mode
            tool = activeTool
            colorArgb = activeColor
            widthWorld = activeStrokeWidth
            fingerDraws = fingerCanDraw
            fitWholePage = false
            onStylusDetected = {
                if (fingerCanDraw) {
                    fingerCanDraw = false
                    canvasView.fingerDraws = false
                    getSharedPreferences("tulis_prefs", MODE_PRIVATE).edit().putBoolean("finger_draws", false).apply()
                    updateSwitchUi(false)
                    Toast.makeText(this@EditorActivity, "Stylus terdeteksi, jari untuk geser", Toast.LENGTH_SHORT).show()
                }
            }
            listener = object : CanvasListener {
                override fun onContentChanged() {
                    isMetaDirty = true
                    scheduleAutosave()
                    minimapView?.refresh()
                }

                override fun onRequestTextEdit(item: TextItem?, wx: Float, wy: Float) {
                    showTextEditDialog(item, wx, wy)
                }

                override fun onHistoryChanged() {
                    updateUndoRedoState()
                }

                override fun onViewportChanged() {
                    updateZoomIndicator()
                    minimapView?.refresh()
                    saveMeta()
                }

                override fun onVisiblePageChanged(index: Int) {
                    currentPageIndex = index
                    updatePageIndicator()
                    if (isTablet) {
                        refreshTabletPageRail()
                    }
                }

                override fun onPageWindowChanged(first: Int, last: Int) {
                    val s = pageStack ?: return
                    for (idx in first..last) {
                        if (s.get(idx) == null && !loadingPages.contains(idx)) {
                            loadingPages.add(idx)
                            val genAtRequest = s.generation
                            repo.loadPage(notebookId, idx) { loaded ->
                                loadingPages.remove(idx)
                                if (s.generation == genAtRequest && s.get(idx) == null && loaded != null) {
                                    s.attach(idx, loaded)
                                    ensurePageThumb(idx, loaded)
                                    canvasView.invalidatePages()
                                }
                            }
                        }
                    }
                    val dirtyList = s.dirtyPages().toMap()
                    for (idx in s.residentIndices()) {
                        if (idx < first - 1 || idx > last + 1) {
                            val dirtyPage = dirtyList[idx]
                            if (dirtyPage != null) {
                                repo.savePageAsync(notebookId, idx, dirtyPage.snapshot())
                                val bmp = canvasView.renderPageThumbnail(dirtyPage, 192)
                                if (idx == 0) {
                                    repo.saveThumbAsync(notebookId, bmp)
                                }
                                repo.savePageThumbAsync(notebookId, idx, bmp)
                                thumbMemoryCache.put("${notebookId}_$idx", bmp)
                                s.markSaved(dirtyPage)
                            }
                            s.evict(idx)
                            canvasView.invalidatePages()
                        }
                    }
                }
            }
        }
        canvasView.setPaper(m.paperStyle, m.paperSpacing)

        if (isTablet) {
            buildTabletUi(m)
        } else {
            buildPhoneUi(m)
        }

        setContentView(rootLayout)
        updateUndoRedoState()
    }

    private fun buildPhoneUi(m: NotebookMeta) {
        if (isPhoneLandscape) {
            val canvasLp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                rightMargin = dp(72f)
            }
            rootLayout.addView(canvasView, canvasLp)

            topBar = buildTopBar(m)
            val topBarLp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply {
                setMargins(dp(12f), dp(8f), dp(76f), 0)
            }
            rootLayout.addView(topBar, topBarLp)

            if (m.mode == NoteMode.INFINITE) {
                val (cChip, zChip) = buildInfiniteChips()
                chipCenter = cChip
                chipZoom = zChip
                val cLp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                ).apply {
                    setMargins(dp(12f), dp(62f), 0, 0)
                }
                rootLayout.addView(cChip, cLp)

                val zLp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                ).apply {
                    setMargins(0, dp(62f), dp(84f), 0)
                }
                rootLayout.addView(zChip, zLp)
            }

            val bToolbar = buildBottomToolbar()
            bottomToolbar = bToolbar
            val bottomToolbarLp = FrameLayout.LayoutParams(
                dp(64f),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply {
                setMargins(0, dp(6f), dp(6f), dp(6f))
            }
            rootLayout.addView(bToolbar, bottomToolbarLp)

            if (m.mode == NoteMode.PAGED) {
                updatePageIndicator()
            }
        } else {
            rootLayout.addView(canvasView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))

            topBar = buildTopBar(m)
            val topBarLp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply {
                setMargins(dp(16f), dp(44f), dp(16f), 0)
            }
            rootLayout.addView(topBar, topBarLp)

            if (m.mode == NoteMode.INFINITE) {
                val (cChip, zChip) = buildInfiniteChips()
                chipCenter = cChip
                chipZoom = zChip
                val cLp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                ).apply {
                    setMargins(dp(16f), dp(104f), 0, 0)
                }
                rootLayout.addView(cChip, cLp)

                val zLp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                ).apply {
                    setMargins(0, dp(104f), dp(16f), 0)
                }
                rootLayout.addView(zChip, zLp)
            }

            val bToolbar = buildBottomToolbar()
            bottomToolbar = bToolbar
            val bottomToolbarLp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                setMargins(dp(12f), 0, dp(12f), dp(28f))
            }
            rootLayout.addView(bToolbar, bottomToolbarLp)

            if (m.mode == NoteMode.PAGED) {
                val pBar = buildPageBar(m)
                pageBar = pBar
                val pageBarLp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply {
                    setMargins(dp(16f), 0, dp(16f), dp(112f))
                }
                rootLayout.addView(pBar, pageBarLp)
                updatePageIndicator()
            }
        }
    }

    private fun buildTabletUi(m: NotebookMeta) {
        val panelW = if (isPanelCollapsed) dp(56f) else dp(232f)

        val canvasLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        applyTabletFitInsets(m)
        rootLayout.addView(canvasView, canvasLp)

        topBar = buildTabletTopBar(m)
        val topBarLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(72f),
            Gravity.TOP
        ).apply {
            setMargins(dp(16f), dp(14f), dp(16f), 0)
        }
        rootLayout.addView(topBar, topBarLp)

        if (m.mode == NoteMode.PAGED) {
            val rail = buildTabletPageRail(m)
            tabletPageRail = rail
            rail.visibility = if (isRailHidden) View.GONE else View.VISIBLE
            val railLp = FrameLayout.LayoutParams(
                dp(168f),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START
            ).apply {
                setMargins(if (tabletToolDock == ToolDock.LEFT) dp(108f) else dp(16f), dp(100f), 0, dp(16f))
            }
            rootLayout.addView(rail, railLp)
            refreshTabletPageRail()
        }

        val panel = buildTabletSidePanel(m)
        tabletSidePanel = panel
        val panelLp = FrameLayout.LayoutParams(
            panelW,
            if (isPanelCollapsed) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.END
        ).apply {
            setMargins(0, dp(100f), dp(16f), dp(16f))
        }
        rootLayout.addView(panel, panelLp)

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        tabletBottomRow = bottomRow

        if (m.mode == NoteMode.PAGED) {
            val pagePill = TextView(this).apply {
                background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 4)
                setTextColor(Palette.INK)
                textSize = 18f
                typeface = Fonts.ui(context)
                gravity = Gravity.CENTER
                setPadding(dp(16f), 0, dp(16f), 0)
                minimumHeight = dp(40f)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    canvasView.toggleFitMode()
                }
            }
            tabletPagePill = pagePill
            bottomRow.addView(pagePill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40f)))
        }

        val zoomPill = TextView(this).apply {
            val pct = (canvasView.viewport.scale * 100f).toInt()
            text = "$pct%"
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 5)
            setTextColor(Palette.INK)
            textSize = 18f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            setPadding(dp(14f), 0, dp(14f), 0)
            minimumHeight = dp(40f)
            isClickable = true
            isFocusable = true
            contentDescription = if (m.mode == NoteMode.INFINITE) {
                "Zoom $pct persen, ketuk untuk mengatur ulang"
            } else {
                "Zoom $pct persen, ketuk untuk beralih mode pas"
            }
            setOnClickListener {
                if (m.mode == NoteMode.INFINITE) {
                    canvasView.viewport.zoomAt(1f / canvasView.viewport.scale, canvasView.width / 2f, canvasView.height / 2f)
                    updateZoomIndicator()
                    saveMeta()
                } else {
                    canvasView.toggleFitMode()
                }
            }
        }
        tabletZoomPill = zoomPill
        pillZoomPct = -1
        updateZoomIndicator()
        bottomRow.addView(zoomPill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40f)).apply {
            if (m.mode == NoteMode.PAGED) {
                marginStart = dp(8f)
            }
        })

        val bottomRowLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = if (tabletToolDock == ToolDock.BOTTOM) dp(96f) else dp(24f)
        }
        rootLayout.addView(bottomRow, bottomRowLp)

        val toolPanel = buildTabletToolPanel(tabletToolDock)
        tabletToolPanel = toolPanel
        rootLayout.addView(toolPanel, makeTabletToolPanelLayoutParams(tabletToolDock))

        if (m.mode == NoteMode.PAGED) {
            updatePageIndicator()
        }

        if (m.mode == NoteMode.INFINITE && UiPrefs.minimapVisible(this)) {
            val minimap = MinimapView(this).apply {
                canvasView = this@EditorActivity.canvasView
            }
            minimapView = minimap
            val minimapLp = FrameLayout.LayoutParams(
                dp(160f),
                dp(112f),
                Gravity.BOTTOM or Gravity.START
            ).apply {
                setMargins(dp(16f), 0, 0, dp(16f))
            }
            rootLayout.addView(minimap, minimapLp)
            minimap.refresh()
            updateTabletLayout()
        }
    }

    private fun makeTabletToolPanelLayoutParams(dock: ToolDock): FrameLayout.LayoutParams {
        return when (dock) {
            ToolDock.TOP -> FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = dp(14f)
            }
            ToolDock.BOTTOM -> FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(16f)
            }
            ToolDock.LEFT -> FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL
            ).apply {
                topMargin = dp(94f)
                marginStart = dp(16f)
            }
            ToolDock.RIGHT -> FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply {
                topMargin = dp(94f)
                marginEnd = dp(16f)
            }
        }
    }

    private fun applyTabletFitInsets(m: NotebookMeta) {
        val railOffset = if (tabletToolDock == ToolDock.LEFT) dp(92f) else 0
        val railW = if (m.mode == NoteMode.PAGED && !isRailHidden) dp(168f) + dp(12f) + railOffset else 0
        val panelOffset = if (tabletToolDock == ToolDock.RIGHT) dp(92f) else 0
        val panelW = if (isPanelCollapsed) dp(56f) else dp(232f)

        val inLeft = if (tabletToolDock == ToolDock.LEFT) maxOf(dp(16f) + railW, dp(92f)) else (dp(16f) + railW)
        val inRight = if (tabletToolDock == ToolDock.RIGHT) maxOf(dp(16f) + panelW + dp(12f), dp(92f)) else (dp(16f) + panelW + dp(12f))
        val inBottom = if (tabletToolDock == ToolDock.BOTTOM) dp(88f) else dp(16f)
        canvasView.setFitInsets(inLeft, dp(94f), inRight, inBottom)
    }

    private fun updateTabletLayout() {
        val m = meta ?: return
        val panelW = if (isPanelCollapsed) dp(56f) else dp(232f)

        tabletPageRail?.let { rail ->
            rail.visibility = if (isRailHidden) View.GONE else View.VISIBLE
            val rLp = rail.layoutParams as? FrameLayout.LayoutParams
            if (rLp != null) {
                val startMargin = if (tabletToolDock == ToolDock.LEFT) dp(108f) else dp(16f)
                if (rLp.marginStart != startMargin) {
                    rLp.marginStart = startMargin
                    rail.layoutParams = rLp
                }
            }
        }

        tabletSidePanel?.let { panel ->
            val pLp = panel.layoutParams as? FrameLayout.LayoutParams
            if (pLp != null) {
                pLp.width = panelW
                pLp.height = if (isPanelCollapsed) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
                val endMargin = if (tabletToolDock == ToolDock.RIGHT) dp(108f) else dp(16f)
                pLp.marginEnd = endMargin
                panel.layoutParams = pLp
            }
            tabletSidePanelStrip?.visibility = if (isPanelCollapsed) View.VISIBLE else View.GONE
            tabletSidePanelFull?.visibility = if (isPanelCollapsed) View.GONE else View.VISIBLE
        }

        tabletBottomRow?.let { row ->
            val bLp = row.layoutParams as? FrameLayout.LayoutParams
            if (bLp != null) {
                val bMargin = if (tabletToolDock == ToolDock.BOTTOM) dp(96f) else dp(24f)
                if (bLp.bottomMargin != bMargin) {
                    bLp.bottomMargin = bMargin
                    row.layoutParams = bLp
                }
            }
        }

        minimapView?.let { mm ->
            val mLp = mm.layoutParams as? FrameLayout.LayoutParams
            if (mLp != null) {
                val onLeft = tabletToolDock != ToolDock.LEFT
                val gravity = Gravity.BOTTOM or (if (onLeft) Gravity.START else Gravity.END)
                val bottom = if (tabletToolDock == ToolDock.BOTTOM) dp(96f) else dp(16f)
                val sideMargin = if (onLeft) dp(16f) else panelW + dp(28f)
                mLp.gravity = gravity
                mLp.bottomMargin = bottom
                mLp.marginStart = if (onLeft) sideMargin else 0
                mLp.marginEnd = if (onLeft) 0 else sideMargin
                mm.layoutParams = mLp
            }
        }

        applyTabletFitInsets(m)

        if (m.mode == NoteMode.PAGED && !canvasView.hasMovedViewport) {
            canvasView.post {
                if (!canvasView.hasMovedViewport) {
                    canvasView.fitPage()
                }
            }
        }
    }

    private fun buildTopBar(m: NotebookMeta): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44f)
        }

        val leftGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnBack = FrameLayout(this).apply {
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, round = true)
            isClickable = true
            isFocusable = true
            contentDescription = "Kembali ke beranda"
            setOnClickListener {
                flushAutosave(immediateThumb = true)
                finish()
            }
        }
        val backIcon = IconView(this).apply {
            showSelection = false
            isClickable = false
            isFocusable = false
            icon = Icon.BACK
            tint = Palette.INK
            strokeWidthDp = 2f
            contentDescription = "Kembali ke beranda"
        }
        btnBack.addView(backIcon, FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.CENTER))
        leftGroup.addView(btnBack, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply {
            marginEnd = dp(8f)
        })

        titleView = TextView(this).apply {
            text = m.title
            setTextColor(Palette.INK)
            textSize = 20f
            typeface = Fonts.ui(context)
            setSingleLine(true)
            gravity = Gravity.CENTER_VERTICAL
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 0)
            setPadding(dp(16f), 0, dp(16f), 0)
            minHeight = dp(44f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showRenameSketchDialog()
            }
        }
        leftGroup.addView(titleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        bar.addView(leftGroup, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (isPhoneLandscape && m.mode == NoteMode.PAGED) {
            val pillContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 0)
                setPadding(dp(2f), 0, dp(2f), 0)
                minimumHeight = dp(44f)
            }

            val prevBtn = FrameLayout(this).apply {
                isClickable = true
                isFocusable = true
                contentDescription = "Halaman sebelumnya"
                setOnClickListener {
                    if (currentPageIndex > 0) {
                        switchPage(currentPageIndex - 1)
                    }
                }
            }
            val prevIcon = IconView(this).apply {
                showSelection = false
                isClickable = false
                isFocusable = false
                icon = Icon.PREV
                tint = Palette.INK
                strokeWidthDp = 2.2f
                contentDescription = "Halaman sebelumnya"
            }
            btnPrevPage = prevIcon
            prevBtn.addView(prevIcon, FrameLayout.LayoutParams(dp(22f), dp(22f), Gravity.CENTER))
            pillContainer.addView(prevBtn, LinearLayout.LayoutParams(dp(44f), dp(44f)))

            val indicator = TextView(this).apply {
                text = "1 / ${m.pageCount}"
                setTextColor(Palette.INK)
                textSize = 18f
                typeface = Fonts.ui(context)
                gravity = Gravity.CENTER
                minWidth = dp(56f)
                minHeight = dp(44f)
            }
            pageIndicator = indicator
            pillContainer.addView(indicator, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val nextBtn = FrameLayout(this).apply {
                isClickable = true
                isFocusable = true
                contentDescription = "Halaman berikutnya"
                setOnClickListener {
                    val currentM = meta ?: return@setOnClickListener
                    if (currentPageIndex < currentM.pageCount - 1) {
                        switchPage(currentPageIndex + 1)
                    }
                }
            }
            val nextIcon = IconView(this).apply {
                showSelection = false
                isClickable = false
                isFocusable = false
                icon = Icon.NEXT
                tint = Palette.INK
                strokeWidthDp = 2.2f
                contentDescription = "Halaman berikutnya"
            }
            btnNextPage = nextIcon
            nextBtn.addView(nextIcon, FrameLayout.LayoutParams(dp(22f), dp(22f), Gravity.CENTER))
            pillContainer.addView(nextBtn, LinearLayout.LayoutParams(dp(44f), dp(44f)))

            bar.addView(pillContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6f)
            })

            val addBtn = FrameLayout(this).apply {
                background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, round = true)
                isClickable = true
                isFocusable = true
                contentDescription = "+ Halaman"
                setOnClickListener {
                    addNewPage()
                }
            }
            val addIcon = IconView(this).apply {
                showSelection = false
                isClickable = false
                isFocusable = false
                icon = Icon.ADD
                tint = Palette.INK
                strokeWidthDp = 2.4f
                contentDescription = "+ Halaman"
            }
            addBtn.addView(addIcon, FrameLayout.LayoutParams(dp(22f), dp(22f), Gravity.CENTER))
            btnAddPage = addBtn
            bar.addView(addBtn, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply {
                marginEnd = dp(6f)
            })

            val delBtn = FrameLayout(this).apply {
                background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, round = true)
                isClickable = true
                isFocusable = true
                contentDescription = "Hapus halaman ini"
                setOnClickListener {
                    confirmDeletePage()
                }
            }
            val delIcon = IconView(this).apply {
                showSelection = false
                isClickable = false
                isFocusable = false
                icon = Icon.TRASH
                tint = Palette.RED
                strokeWidthDp = 2f
                contentDescription = "Hapus halaman ini"
            }
            delBtn.addView(delIcon, FrameLayout.LayoutParams(dp(22f), dp(22f), Gravity.CENTER))
            btnDeletePage = delBtn
            bar.addView(delBtn, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply {
                marginEnd = dp(6f)
            })
        }

        val undoRedoPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 1)
            setPadding(dp(4f), 0, dp(4f), 0)
            minimumHeight = dp(44f)
        }

        val btnUndoContainer = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Urungkan"
            setOnClickListener {
                canvasView.undo()
            }
        }
        btnUndo = IconView(this).apply {
            showSelection = false
            isClickable = false
            isFocusable = false
            icon = Icon.UNDO
            tint = Palette.INK
            strokeWidthDp = 2f
            contentDescription = "Urungkan"
        }
        btnUndoContainer.addView(btnUndo, FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.CENTER))
        undoRedoPill.addView(btnUndoContainer, LinearLayout.LayoutParams(dp(44f), dp(44f)))

        val btnRedoContainer = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Ulangi"
            setOnClickListener {
                canvasView.redo()
            }
        }
        btnRedo = IconView(this).apply {
            showSelection = false
            isClickable = false
            isFocusable = false
            icon = Icon.REDO
            tint = Palette.INK
            strokeWidthDp = 2f
            contentDescription = "Ulangi"
        }
        btnRedoContainer.addView(btnRedo, FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.CENTER))
        undoRedoPill.addView(btnRedoContainer, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply {
            marginStart = dp(2f)
        })

        bar.addView(buildPaperButton(44f, 24f, m), LinearLayout.LayoutParams(dp(44f), dp(44f)).apply {
            marginEnd = dp(6f)
        })
        bar.addView(buildExportButton(44f, 24f, m), LinearLayout.LayoutParams(dp(44f), dp(44f)).apply {
            marginEnd = dp(6f)
        })
        bar.addView(undoRedoPill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        return bar
    }

    private fun buildPaperButton(sizeDp: Float, iconDp: Float, m: NotebookMeta): FrameLayout {
        val btn = FrameLayout(this).apply {
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, round = true)
            isClickable = true
            isFocusable = true
            contentDescription = "Kertas"
            setOnClickListener {
                val paged = m.mode == NoteMode.PAGED
                showPaperDialog(
                    this@EditorActivity,
                    m.paperStyle,
                    m.paperSpacing,
                    if (paged) m.orientation else null,
                    if (paged) { o -> confirmOrientationChange(o) } else null
                ) { s, sp ->
                    m.paperStyle = s
                    m.paperSpacing = sp
                    canvasView.setPaper(s, sp)
                    isMetaDirty = true
                    saveMeta()
                }
            }
        }
        val icon = IconView(this).apply {
            showSelection = false
            isClickable = false
            isFocusable = false
            icon = Icon.PAPER
            tint = Palette.INK
            strokeWidthDp = 2f
            contentDescription = "Kertas"
        }
        btn.addView(icon, FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER))
        return btn
    }

    private fun buildExportButton(sizeDp: Float, iconDp: Float, m: NotebookMeta): FrameLayout {
        val btn = FrameLayout(this).apply {
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, round = true)
            isClickable = true
            isFocusable = true
            contentDescription = "Ekspor"
            setOnClickListener { showExportDialog(this@EditorActivity, m.mode == NoteMode.PAGED) { startExport(it) } }
        }
        val icon = IconView(this).apply {
            showSelection = false
            isClickable = false
            isFocusable = false
            icon = Icon.EXPORT
            tint = Palette.INK
            strokeWidthDp = 2f
            contentDescription = "Ekspor"
        }
        btn.addView(icon, FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER))
        return btn
    }

    private fun buildTabletTopBar(m: NotebookMeta): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val leftGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnBack = FrameLayout(this).apply {
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, round = true)
            isClickable = true
            isFocusable = true
            contentDescription = "Kembali ke beranda"
            setOnClickListener {
                flushAutosave(immediateThumb = true)
                finish()
            }
        }
        val backIcon = IconView(this).apply {
            showSelection = false
            isClickable = false
            isFocusable = false
            icon = Icon.BACK
            tint = Palette.INK
            strokeWidthDp = 2f
            contentDescription = "Kembali ke beranda"
        }
        btnBack.addView(backIcon, FrameLayout.LayoutParams(dp(26f), dp(26f), Gravity.CENTER))
        leftGroup.addView(btnBack, LinearLayout.LayoutParams(dp(52f), dp(52f)).apply {
            marginEnd = dp(12f)
        })

        titleView = TextView(this).apply {
            text = m.title
            setTextColor(Palette.INK)
            textSize = 22f
            typeface = Fonts.ui(context)
            setSingleLine(true)
            gravity = Gravity.CENTER_VERTICAL
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 0)
            setPadding(dp(22f), 0, dp(22f), 0)
            minHeight = dp(52f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showRenameSketchDialog()
            }
        }
        leftGroup.addView(titleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        bar.addView(leftGroup, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val rightGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }

        if (m.mode == NoteMode.PAGED) {
            val btnToggleRail = FrameLayout(this).apply {
                background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, round = true)
                isClickable = true
                isFocusable = true
                contentDescription = "Rel halaman"
                setOnClickListener {
                    isRailHidden = !isRailHidden
                    getSharedPreferences("tulis_prefs", MODE_PRIVATE).edit().putBoolean("rail_hidden", isRailHidden).apply()
                    updateTabletLayout()
                }
            }
            val pageIcon = IconView(this).apply {
                showSelection = false
                isClickable = false
                isFocusable = false
                icon = Icon.PAGE
                tint = Palette.INK
                strokeWidthDp = 2f
                contentDescription = "Rel halaman"
            }
            btnToggleRail.addView(pageIcon, FrameLayout.LayoutParams(dp(26f), dp(26f), Gravity.CENTER))
            rightGroup.addView(btnToggleRail, LinearLayout.LayoutParams(dp(52f), dp(52f)).apply {
                marginEnd = dp(12f)
            })
        }

        rightGroup.addView(buildPaperButton(52f, 26f, m), LinearLayout.LayoutParams(dp(52f), dp(52f)).apply {
            marginEnd = dp(12f)
        })
        rightGroup.addView(buildExportButton(52f, 26f, m), LinearLayout.LayoutParams(dp(52f), dp(52f)).apply {
            marginEnd = dp(12f)
        })

        val undoRedoPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 1)
            setPadding(dp(4f), 0, dp(4f), 0)
            minimumHeight = dp(52f)
        }

        val btnUndoContainer = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Urungkan"
            setOnClickListener {
                canvasView.undo()
            }
        }
        btnUndo = IconView(this).apply {
            showSelection = false
            isClickable = false
            isFocusable = false
            icon = Icon.UNDO
            tint = Palette.INK
            strokeWidthDp = 2f
            contentDescription = "Urungkan"
        }
        btnUndoContainer.addView(btnUndo, FrameLayout.LayoutParams(dp(26f), dp(26f), Gravity.CENTER))
        undoRedoPill.addView(btnUndoContainer, LinearLayout.LayoutParams(dp(48f), dp(48f)))

        val btnRedoContainer = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Ulangi"
            setOnClickListener {
                canvasView.redo()
            }
        }
        btnRedo = IconView(this).apply {
            showSelection = false
            isClickable = false
            isFocusable = false
            icon = Icon.REDO
            tint = Palette.INK
            strokeWidthDp = 2f
            contentDescription = "Ulangi"
        }
        btnRedoContainer.addView(btnRedo, FrameLayout.LayoutParams(dp(26f), dp(26f), Gravity.CENTER))
        undoRedoPill.addView(btnRedoContainer, LinearLayout.LayoutParams(dp(48f), dp(48f)).apply {
            marginStart = dp(2f)
        })

        rightGroup.addView(undoRedoPill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(rightGroup, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        return bar
    }

    private class ToolDragHandleView(context: Context) : View(context) {
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.INK
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
        }
        var isVerticalLayout: Boolean = false

        init {
            isClickable = false
            isFocusable = false
            minimumWidth = (44f * resources.displayMetrics.density).toInt()
            minimumHeight = (44f * resources.displayMetrics.density).toInt()
            contentDescription = "Pegangan seret bilah alat"
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val step = 5f * resources.displayMetrics.density
            val lineLen = 16f * resources.displayMetrics.density
            if (isVerticalLayout) {
                val halfL = lineLen / 2f
                for (i in -1..1) {
                    val y = cy + i * step
                    canvas.drawLine(cx - halfL, y, cx + halfL, y, linePaint)
                }
            } else {
                val halfL = lineLen / 2f
                for (i in -1..1) {
                    val x = cx + i * step
                    canvas.drawLine(x, cy - halfL, x, cy + halfL, linePaint)
                }
            }
        }
    }

    private fun buildTabletToolPanel(dock: ToolDock): View {
        val isHoriz = (dock == ToolDock.TOP || dock == ToolDock.BOTTOM)
        val panel = LinearLayout(this).apply {
            orientation = if (isHoriz) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 11)
            if (isHoriz) {
                setPadding(dp(6f), dp(4f), dp(8f), dp(4f))
                minimumHeight = dp(64f)
            } else {
                setPadding(dp(4f), dp(6f), dp(4f), dp(8f))
                minimumWidth = dp(76f)
            }
        }

        val handleContainer = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Pegangan geser panel alat"
        }
        val handleView = ToolDragHandleView(this).apply {
            isVerticalLayout = !isHoriz
        }
        val handleLp = if (isHoriz) {
            FrameLayout.LayoutParams(dp(32f), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        } else {
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32f), Gravity.CENTER)
        }
        handleContainer.addView(handleView, handleLp)

        var dX = 0f
        var dY = 0f
        var isDragging = false

        handleContainer.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    dX = panel.translationX - event.rawX
                    dY = panel.translationY - event.rawY
                    isDragging = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) {
                        panel.translationX = event.rawX + dX
                        panel.translationY = event.rawY + dY
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        isDragging = false
                        snapTabletToolPanel(panel)
                    }
                    true
                }
                else -> false
            }
        }

        if (isHoriz) {
            panel.addView(handleContainer, LinearLayout.LayoutParams(dp(32f), dp(58f)))
        } else {
            panel.addView(handleContainer, LinearLayout.LayoutParams(dp(68f), dp(32f)))
        }

        val tools = listOf(
            Triple(Tool.PEN, Icon.PEN, "Pena"),
            Triple(Tool.MARKER, Icon.MARKER, "Spidol"),
            Triple(Tool.HIGHLIGHTER, Icon.HIGHLIGHTER, "Stabilo"),
            Triple(Tool.ERASER, Icon.ERASER, "Hapus"),
            Triple(Tool.TEXT, Icon.TEXT, "Teks"),
            Triple(Tool.PAN, Icon.PAN, "Geser")
        )

        for ((t, ic, title) in tools) {
            val itemContainer = FrameLayout(this).apply {
                isClickable = true
                isFocusable = true
                contentDescription = title
                background = null
            }

            val innerColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = false
                isFocusable = false
            }

            val iconV = IconView(this).apply {
                showSelection = false
                isClickable = false
                isFocusable = false
                icon = ic
                tint = Palette.INK
                strokeWidthDp = 2f
                contentDescription = title
            }
            innerColumn.addView(iconV, LinearLayout.LayoutParams(dp(26f), dp(26f)))

            val labelV = TextView(this).apply {
                text = title
                setTextColor(Palette.INK)
                textSize = toolLabelSp(15f)
                typeface = Fonts.ui(context)
                gravity = Gravity.CENTER
            }
            innerColumn.addView(labelV, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2f)
            })

            itemContainer.addView(innerColumn, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            ))

            val dot = View(this).apply {
                val dotBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(getColorForTool(t))
                    setStroke(dp(2f), Palette.INK)
                }
                background = dotBg
                visibility = View.GONE
            }
            val dotLp = FrameLayout.LayoutParams(dp(11f), dp(11f), Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(5f), dp(5f), 0)
            }
            itemContainer.addView(dot, dotLp)

            itemContainer.setOnClickListener {
                applyToolSelection(t)
            }

            toolButtons[t] = itemContainer
            toolDots[t] = dot

            if (isHoriz) {
                panel.addView(itemContainer, LinearLayout.LayoutParams(dp(64f), dp(58f)).apply {
                    marginStart = dp(2f)
                    marginEnd = dp(2f)
                })
            } else {
                panel.addView(itemContainer, LinearLayout.LayoutParams(dp(68f), dp(58f)).apply {
                    topMargin = dp(2f)
                    bottomMargin = dp(2f)
                })
            }
        }

        applyToolSelection(activeTool)
        syncToolDots()

        return panel
    }

    private fun snapTabletToolPanel(panel: View) {
        val rootW = rootLayout.width.toFloat()
        val rootH = rootLayout.height.toFloat()
        if (rootW <= 0f || rootH <= 0f) {
            panel.translationX = 0f
            panel.translationY = 0f
            return
        }

        val location = IntArray(2)
        panel.getLocationInWindow(location)
        val rootLocation = IntArray(2)
        rootLayout.getLocationInWindow(rootLocation)

        val curLeft = (location[0] - rootLocation[0]).toFloat()
        val curTop = (location[1] - rootLocation[1]).toFloat()
        val centerX = curLeft + panel.width / 2f
        val centerY = curTop + panel.height / 2f

        val distTop = centerY
        val distBottom = rootH - centerY
        val distLeft = centerX
        val distRight = rootW - centerX

        val minDist = minOf(distTop, distBottom, distLeft, distRight)
        val targetDock = when (minDist) {
            distTop -> ToolDock.TOP
            distBottom -> ToolDock.BOTTOM
            distLeft -> ToolDock.LEFT
            else -> ToolDock.RIGHT
        }

        if (tabletToolDock != targetDock) {
            tabletToolDock = targetDock
            getSharedPreferences("tulis_prefs", MODE_PRIVATE)
                .edit()
                .putString("tool_dock", targetDock.name)
                .apply()

            rootLayout.removeView(panel)
            val newPanel = buildTabletToolPanel(targetDock)
            newPanel.alpha = 0f
            tabletToolPanel = newPanel
            rootLayout.addView(newPanel, makeTabletToolPanelLayoutParams(targetDock))
            newPanel.animate().alpha(1f).setDuration(160L).start()
            updateTabletLayout()
        } else {
            panel.animate()
                .translationX(0f)
                .translationY(0f)
                .setDuration(180L)
                .start()
        }
    }

    private fun buildTabletPageRail(m: NotebookMeta): View {
        val aside = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 8)
            setPadding(dp(12f), dp(14f), dp(12f), dp(14f))
        }

        val headerText = TextView(this).apply {
            text = "Halaman"
            setTextColor(Palette.INK)
            textSize = 30f
            typeface = Fonts.hand(context, bold = true)
        }
        aside.addView(headerText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10f)
        })

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        tabletThumbListContainer = list
        scroll.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        aside.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val addBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 1)
            setPadding(dp(8f), 0, dp(8f), 0)
            minimumHeight = dp(44f)
            isClickable = true
            isFocusable = true
            contentDescription = "Tambah Halaman"
            setOnClickListener {
                addNewPage()
            }
        }
        val addIcon = IconView(this).apply {
            showSelection = false
            isClickable = false
            isFocusable = false
            icon = Icon.ADD
            tint = Palette.INK
            strokeWidthDp = 2.4f
            contentDescription = "Tambah Halaman"
        }
        addBtn.addView(addIcon, LinearLayout.LayoutParams(dp(20f), dp(20f)).apply {
            marginEnd = dp(4f)
        })
        val addText = TextView(this).apply {
            text = "Halaman"
            setTextColor(Palette.INK)
            textSize = 18f
            typeface = Fonts.ui(context)
        }
        addBtn.addView(addText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val delBtn = FrameLayout(this).apply {
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, round = true)
            isClickable = true
            isFocusable = true
            contentDescription = "Hapus halaman ini"
            setOnClickListener {
                confirmDeletePage()
            }
        }
        val delIcon = IconView(this).apply {
            showSelection = false
            isClickable = false
            isFocusable = false
            icon = Icon.TRASH
            tint = Palette.RED
            strokeWidthDp = 2f
            contentDescription = "Hapus halaman ini"
        }
        delBtn.addView(delIcon, FrameLayout.LayoutParams(dp(22f), dp(22f), Gravity.CENTER))
        tabletBtnDeletePage = delBtn

        val bottomBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(addBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6f)
            })
            addView(delBtn, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        }

        aside.addView(bottomBtnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10f)
        })

        return aside
    }

    private fun refreshTabletPageRail() {
        val list = tabletThumbListContainer ?: return
        val m = meta ?: return
        list.removeAllViews()

        for (i in 0 until m.pageCount) {
            val isCurrent = i == currentPageIndex
            val itemContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                isFocusable = true
                contentDescription = "Halaman ${i + 1}${if (isCurrent) ", aktif" else ""}"
                setOnClickListener {
                    if (currentPageIndex != i) {
                        switchPage(i)
                    }
                }
            }

            val cardContainer = FrameLayout(this).apply {
                setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
                background = if (isCurrent) {
                    SketchDrawable(
                        fill = Palette.YELLOW,
                        stroke = Palette.INK,
                        strokeWidthDp = 2.5f,
                        shadowOffsetDp = 0f,
                        cornerDp = 6f
                    )
                } else {
                    null
                }
            }

            val thumbCard = FrameLayout(this).apply {
                val baseDraw = SketchDrawable(
                    fill = Palette.SHEET,
                    stroke = Palette.INK,
                    strokeWidthDp = if (isCurrent) 2f else 1.6f,
                    shadowOffsetDp = 0f,
                    cornerDp = 2f
                )
                background = baseDraw
            }

            val thumbImg = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            thumbCard.addView(thumbImg, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            val numPlaceholder = TextView(this).apply {
                text = "${i + 1}"
                setTextColor(0x552B2724)
                textSize = 36f
                typeface = Fonts.hand(context, bold = true)
                gravity = Gravity.CENTER
            }
            thumbCard.addView(numPlaceholder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))

            loadPageThumbnailAsync(i, thumbImg, numPlaceholder)

            val landscapeThumb = m.orientation == PageOrientation.LANDSCAPE
            cardContainer.addView(
                thumbCard,
                FrameLayout.LayoutParams(if (landscapeThumb) dp(136f) else dp(96f), if (landscapeThumb) dp(96f) else dp(136f))
            )
            itemContainer.addView(cardContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val numBadge = TextView(this).apply {
                text = "${i + 1}"
                setTextColor(Palette.INK)
                textSize = 16f
                typeface = Fonts.ui(context)
                gravity = Gravity.CENTER
                if (isCurrent) {
                    background = SketchDrawable(
                        fill = Palette.YELLOW,
                        stroke = Palette.INK,
                        strokeWidthDp = 2f,
                        shadowOffsetDp = 0f,
                        cornerDp = 8f
                    )
                    setPadding(dp(10f), 0, dp(10f), 0)
                }
            }
            val numLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = if (isCurrent) dp(4f) else dp(2f)
            }
            itemContainer.addView(numBadge, numLp)

            val itemLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12f)
            }
            list.addView(itemContainer, itemLp)
        }
    }

    private fun loadPageThumbnailAsync(index: Int, imageView: ImageView, placeholder: TextView) {
        val cacheKey = "${notebookId}_$index"
        val cached = thumbMemoryCache.get(cacheKey)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            placeholder.visibility = View.GONE
            return
        }

        imageView.setImageBitmap(null)
        placeholder.visibility = View.VISIBLE
        imageView.tag = cacheKey

        thumbExecutor.execute {
            val file = repo.pageThumbFile(notebookId, index)
            if (!file.exists()) return@execute

            val boundsOpts = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, boundsOpts)

            var sampleSize = 1
            while (boundsOpts.outWidth / (sampleSize * 2) >= 96 && boundsOpts.outHeight / (sampleSize * 2) >= 136) {
                sampleSize *= 2
            }

            val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = try {
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
            } catch (t: Throwable) {
                null
            } ?: return@execute

            mainHandler.post {
                thumbMemoryCache.put(cacheKey, bitmap)
                if (imageView.tag == cacheKey) {
                    imageView.setImageBitmap(bitmap)
                    placeholder.visibility = View.GONE
                }
            }
        }
    }

    private class WavyPreviewView(context: android.content.Context) : View(context) {
        var strokeColor: Int = Palette.BLUE
            set(value) {
                field = value
                paint.color = value
                invalidate()
            }
        var strokeWidthWorld: Float = 4.5f
            set(value) {
                field = value
                paint.strokeWidth = (value * resources.displayMetrics.density).coerceIn(2f, 18f)
                invalidate()
            }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = strokeColor
            strokeWidth = 4.5f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            path.reset()
            val wF = w.toFloat()
            val hF = h.toFloat()
            path.moveTo(wF * 0.05f, hF * 0.6f)
            path.cubicTo(wF * 0.2f, hF * 0.2f, wF * 0.3f, hF * 0.9f, wF * 0.45f, hF * 0.5f)
            path.cubicTo(wF * 0.6f, hF * 0.1f, wF * 0.7f, hF * 0.85f, wF * 0.85f, hF * 0.45f)
            path.lineTo(wF * 0.95f, hF * 0.5f)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawPath(path, paint)
        }
    }

    private fun buildTabletSidePanel(m: NotebookMeta): View {
        tabletSideColorViews.clear()
        tabletSideSlider = null

        val rootContainer = FrameLayout(this)

        val fullPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 8)
            setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
            visibility = if (isPanelCollapsed) View.GONE else View.VISIBLE
        }
        tabletSidePanelFull = fullPanel

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val toolTitle = TextView(this).apply {
            text = getToolName(activeTool)
            setTextColor(Palette.INK)
            textSize = 34f
            typeface = Fonts.hand(context, bold = true)
        }
        tabletSideTitle = toolTitle
        headerRow.addView(toolTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val btnCollapse = FrameLayout(this).apply {
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, round = true)
            isClickable = true
            isFocusable = true
            contentDescription = "Lipat panel"
            setOnClickListener {
                isPanelCollapsed = true
                getSharedPreferences("tulis_prefs", MODE_PRIVATE).edit().putBoolean("panel_collapsed", true).apply()
                updateTabletLayout()
            }
        }
        val chevronIcon = IconView(this).apply {
            showSelection = false
            isClickable = false
            isFocusable = false
            contentDescription = "Lipat panel"
            icon = Icon.NEXT
            tint = Palette.INK
            strokeWidthDp = 2f
        }
        btnCollapse.addView(chevronIcon, FrameLayout.LayoutParams(dp(22f), dp(22f), Gravity.CENTER))
        headerRow.addView(btnCollapse, LinearLayout.LayoutParams(dp(44f), dp(44f)))

        fullPanel.addView(headerRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val wavy = WavyPreviewView(this).apply {
            strokeColor = getColorForTool(activeTool)
            strokeWidthWorld = activeStrokeWidth
        }
        tabletStrokePreview = wavy
        fullPanel.addView(wavy, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40f)).apply {
            topMargin = dp(6f)
            bottomMargin = dp(4f)
        })

        if (m.mode == NoteMode.INFINITE) {
            val chipRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val cChip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 4)
                setPadding(dp(10f), 0, dp(12f), 0)
                isClickable = true
                isFocusable = true
                contentDescription = "Pusatkan"
                setOnClickListener {
                    canvasView.viewport.resetCenter(canvasView.width, canvasView.height)
                    updateZoomIndicator()
                    saveMeta()
                }
            }
            val locateIcon = IconView(this).apply {
                showSelection = false
                isClickable = false
                isFocusable = false
                contentDescription = "Pusatkan"
                icon = Icon.LOCATE
                tint = Palette.INK
                strokeWidthDp = 2.2f
            }
            cChip.addView(locateIcon, LinearLayout.LayoutParams(dp(18f), dp(18f)).apply {
                marginEnd = dp(6f)
            })
            val locateText = TextView(this).apply {
                text = "Pusatkan"
                setTextColor(Palette.INK)
                textSize = 16f
                typeface = Fonts.ui(context)
            }
            cChip.addView(locateText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            chipCenter = cChip
            chipRow.addView(cChip, LinearLayout.LayoutParams(0, dp(40f), 1f))

            val zChip = TextView(this).apply {
                text = "100%"
                setTextColor(Palette.INK)
                textSize = 16f
                typeface = Fonts.ui(context)
                gravity = Gravity.CENTER
                background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 5)
                setPadding(dp(10f), 0, dp(10f), 0)
                isClickable = true
                isFocusable = true
                contentDescription = "Zoom 100 persen, ketuk untuk mengatur ulang"
                setOnClickListener {
                    canvasView.viewport.zoomAt(1f / canvasView.viewport.scale, canvasView.width / 2f, canvasView.height / 2f)
                    updateZoomIndicator()
                    saveMeta()
                }
            }
            chipZoom = zChip
            chipRow.addView(zChip, LinearLayout.LayoutParams(dp(64f), dp(40f)).apply {
                marginStart = dp(8f)
            })

            fullPanel.addView(chipRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6f)
                bottomMargin = dp(4f)
            })
        }

        val colorLabel = TextView(this).apply {
            text = "Warna"
            setTextColor(Palette.INK_SOFT)
            textSize = 16f
            typeface = Fonts.ui(context)
        }
        fullPanel.addView(colorLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10f)
            bottomMargin = dp(8f)
        })

        val colors = listOf(
            Palette.INK,
            Palette.RED,
            Palette.BLUE,
            Palette.GREEN,
            Palette.ORANGE,
            Palette.PURPLE
        )

        val colorGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val currentToolColor = getColorForTool(activeTool)

        for (idx in colors.indices) {
            val c = colors[idx]
            val cName = when (c) {
                Palette.INK -> "Warna hitam"
                Palette.RED -> "Warna merah"
                Palette.BLUE -> "Warna biru"
                Palette.GREEN -> "Warna hijau"
                Palette.ORANGE -> "Warna oranye"
                Palette.PURPLE -> "Warna ungu"
                else -> "Warna"
            }
            val cBtn = ColorSwatchView(this).apply {
                color = c
                isSelectedSwatch = c == currentToolColor
                contentDescription = cName
                isSelected = c == currentToolColor
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    stateDescription = if (c == currentToolColor) "Dipilih" else "Tidak dipilih"
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    setColorForTool(activeTool, c)
                    activeColor = c
                    canvasView.colorArgb = c
                    syncToolDots()
                    tabletStrokePreview?.let {
                        (it as WavyPreviewView).strokeColor = c
                    }
                    for ((colorVal, btn) in tabletSideColorViews) {
                        val isSel = colorVal == c
                        (btn as? ColorSwatchView)?.let { swatch ->
                            swatch.isSelectedSwatch = isSel
                            swatch.isSelected = isSel
                            if (android.os.Build.VERSION.SDK_INT >= 30) {
                                swatch.stateDescription = if (isSel) "Dipilih" else "Tidak dipilih"
                            }
                        }
                    }
                    updateTabletCollapsedStrip()
                }
            }
            tabletSideColorViews[c] = cBtn

            val targetRow = if (idx < 3) row1 else row2
            val cLp = LinearLayout.LayoutParams(dp(44f), dp(44f)).apply {
                if (idx % 3 != 0) marginStart = dp(18f)
            }
            targetRow.addView(cBtn, cLp)
        }
        colorGrid.addView(row1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        colorGrid.addView(row2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12f)
        })
        fullPanel.addView(colorGrid)

        val thickLabel = TextView(this).apply {
            text = "Ketebalan"
            setTextColor(Palette.INK_SOFT)
            textSize = 16f
            typeface = Fonts.ui(context)
        }
        fullPanel.addView(thickLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(22f)
            bottomMargin = dp(8f)
        })

        val initialRange = widthRangeForTool(activeTool)
        val sideSlider = SketchSlider(
            context = this,
            min = initialRange[0],
            max = initialRange[1],
            step = initialRange[2],
            initial = getWidthForTool(activeTool),
            description = "Ketebalan",
            showButtons = true,
            compact = true,
            onChange = { v ->
                setWidthForTool(activeTool, v)
                activeStrokeWidth = v
                canvasView.widthWorld = v
                tabletStrokePreview?.let {
                    (it as WavyPreviewView).strokeWidthWorld = v
                }
                updateTabletCollapsedStrip()
            },
            onCommit = { v ->
                val key = when (activeTool) {
                    Tool.PEN -> "width_pen"
                    Tool.MARKER -> "width_marker"
                    Tool.HIGHLIGHTER -> "width_highlighter"
                    else -> null
                }
                if (key != null) {
                    getSharedPreferences("tulis_prefs", MODE_PRIVATE).edit().putFloat(key, v).apply()
                }
            }
        )
        tabletSideSlider = sideSlider
        fullPanel.addView(sideSlider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val spacer = View(this)
        fullPanel.addView(spacer, LinearLayout.LayoutParams(0, 0, 1f))

        val separator = DashedSeparatorView(this)
        fullPanel.addView(separator, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14f)).apply {
            topMargin = dp(14f)
        })

        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val switchLabel = TextView(this).apply {
            text = "Jari bisa menggambar"
            setTextColor(Palette.INK)
            textSize = 18f
            typeface = Fonts.ui(context)
        }
        switchRow.addView(switchLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(10f)
        })

        val switchBtn = FrameLayout(this).apply {
            val isChecked = fingerCanDraw
            background = SketchDrawable(
                fill = if (isChecked) Palette.YELLOW else Palette.CARD,
                stroke = Palette.INK,
                variant = 6
            )
            isClickable = true
            isFocusable = true
            contentDescription = "Jari bisa menggambar"
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                stateDescription = if (isChecked) "aktif" else "nonaktif"
            }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Switch"
                    info.isCheckable = true
                    info.isChecked = fingerCanDraw
                }
            }
        }
        tabletSideSwitchBtn = switchBtn

        val knob = View(this).apply {
            val knobBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Palette.INK)
            }
            background = knobBg
        }
        tabletSideKnob = knob
        val knobLp = FrameLayout.LayoutParams(dp(22f), dp(22f)).apply {
            topMargin = dp(4f)
            if (fingerCanDraw) {
                gravity = Gravity.TOP or Gravity.END
                marginEnd = dp(4f)
            } else {
                gravity = Gravity.TOP or Gravity.START
                marginStart = dp(4f)
            }
        }
        switchBtn.addView(knob, knobLp)

        switchBtn.setOnClickListener {
            val nextState = !fingerCanDraw
            setFingerDrawState(nextState)
        }

        switchRow.addView(switchBtn, LinearLayout.LayoutParams(dp(60f), dp(34f)))
        fullPanel.addView(switchRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8f)
        })

        rootContainer.addView(fullPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val collapsedStrip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 8)
            setPadding(dp(8f), dp(16f), dp(8f), dp(16f))
            visibility = if (isPanelCollapsed) View.VISIBLE else View.GONE
            isClickable = true
            isFocusable = true
            contentDescription = "Buka panel opsi"
            setOnClickListener {
                isPanelCollapsed = false
                getSharedPreferences("tulis_prefs", MODE_PRIVATE).edit().putBoolean("panel_collapsed", false).apply()
                updateTabletLayout()
            }
        }
        tabletSidePanelStrip = collapsedStrip

        val stripSwatch = ColorSwatchView(this).apply {
            id = 301
            color = getColorForTool(activeTool)
            isSelectedSwatch = true
            isClickable = false
            isFocusable = false
        }
        collapsedStrip.addView(stripSwatch, LinearLayout.LayoutParams(dp(36f), dp(36f)).apply {
            bottomMargin = dp(16f)
        })

        val stripThickContainer = FrameLayout(this).apply {
            id = 302
            background = SketchDrawable(fill = Palette.YELLOW, stroke = Palette.INK, variant = 3)
            isClickable = false
            isFocusable = false
        }
        val stripDot = View(this).apply {
            id = 303
            val dotBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColorForTool(activeTool))
            }
            background = dotBg
        }
        val curDotDp = (getWidthForTool(activeTool) * 1.2f).coerceIn(5f, 24f)
        val curDotSize = dp(curDotDp)
        stripThickContainer.addView(stripDot, FrameLayout.LayoutParams(curDotSize, curDotSize, Gravity.CENTER))
        collapsedStrip.addView(stripThickContainer, LinearLayout.LayoutParams(dp(36f), dp(36f)))

        rootContainer.addView(collapsedStrip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        return rootContainer
    }

    private fun updateTabletCollapsedStrip() {
        val strip = tabletSidePanelStrip ?: return
        val swatch = strip.findViewById<ColorSwatchView>(301)
        val curC = getColorForTool(activeTool)
        swatch?.let {
            it.color = curC
            it.invalidate()
        }
        val curDotDp = (getWidthForTool(activeTool) * 1.2f).coerceIn(5f, 24f)
        val dot = strip.findViewById<View>(303)
        dot?.let {
            (it.background as? GradientDrawable)?.setColor(curC)
            val dSize = dp(curDotDp)
            val lp = it.layoutParams as? FrameLayout.LayoutParams
            if (lp != null) {
                lp.width = dSize
                lp.height = dSize
                it.layoutParams = lp
            }
        }
    }

    private fun setFingerDrawState(enabled: Boolean) {
        fingerCanDraw = enabled
        canvasView.fingerDraws = enabled
        getSharedPreferences("tulis_prefs", MODE_PRIVATE).edit().putBoolean("finger_draws", enabled).apply()
        updateSwitchUi(enabled)
    }

    private fun updateSwitchUi(enabled: Boolean) {
        val updateBtn = { btn: FrameLayout?, knob: View? ->
            if (btn != null && knob != null) {
                btn.background = SketchDrawable(
                    fill = if (enabled) Palette.YELLOW else Palette.CARD,
                    stroke = Palette.INK,
                    variant = 6
                )
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    btn.stateDescription = if (enabled) "aktif" else "nonaktif"
                }
                val lp = knob.layoutParams as FrameLayout.LayoutParams
                if (enabled) {
                    lp.gravity = Gravity.TOP or Gravity.END
                    lp.marginEnd = dp(4f)
                    lp.marginStart = 0
                } else {
                    lp.gravity = Gravity.TOP or Gravity.START
                    lp.marginStart = dp(4f)
                    lp.marginEnd = 0
                }
                knob.layoutParams = lp
            }
        }
        updateBtn(tabletSideSwitchBtn, tabletSideKnob)
        updateBtn(phoneSwitchBtn, phoneSwitchKnob)
    }

    private fun getToolName(t: Tool): String {
        return when (t) {
            Tool.PEN -> "Pena"
            Tool.MARKER -> "Spidol"
            Tool.HIGHLIGHTER -> "Stabilo"
            Tool.ERASER -> "Hapus"
            Tool.TEXT -> "Teks"
            Tool.PAN -> "Geser"
        }
    }

    private fun updateTabletSidePanel() {
        tabletSideTitle?.text = getToolName(activeTool)
        val currentC = getColorForTool(activeTool)
        val currentW = getWidthForTool(activeTool)

        tabletStrokePreview?.let {
            val w = it as WavyPreviewView
            w.strokeColor = currentC
            w.strokeWidthWorld = currentW
        }

        tabletSideSlider?.let { slider ->
            val range = widthRangeForTool(activeTool)
            slider.setRange(range[0], range[1], range[2])
            slider.value = currentW
        }

        for ((colorVal, btn) in tabletSideColorViews) {
            val isSel = colorVal == currentC
            (btn as? ColorSwatchView)?.let { swatch ->
                swatch.isSelectedSwatch = isSel
                swatch.isSelected = isSel
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    swatch.stateDescription = if (isSel) "Dipilih" else "Tidak dipilih"
                }
            }
        }

        updateTabletCollapsedStrip()
    }

    private fun buildInfiniteChips(): Pair<View, TextView> {
        val locateContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 4)
            setPadding(dp(12f), 0, dp(16f), 0)
            minimumHeight = dp(44f)
            isClickable = true
            isFocusable = true
            contentDescription = "Pusatkan"
            setOnClickListener {
                canvasView.viewport.resetCenter(canvasView.width, canvasView.height)
                updateZoomIndicator()
                saveMeta()
            }
        }
        val locateIcon = IconView(this).apply {
            showSelection = false
            isClickable = false
            isFocusable = false
            icon = Icon.LOCATE
            tint = Palette.INK
            strokeWidthDp = 2.2f
            contentDescription = "Pusatkan"
        }
        locateContainer.addView(locateIcon, LinearLayout.LayoutParams(dp(20f), dp(20f)).apply {
            marginEnd = dp(8f)
        })
        val locateText = TextView(this).apply {
            text = "Pusatkan"
            setTextColor(Palette.INK)
            textSize = 17f
            typeface = Fonts.ui(context)
        }
        locateContainer.addView(locateText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val zChip = TextView(this).apply {
            text = "100%"
            setTextColor(Palette.INK)
            textSize = 17f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 5)
            setPadding(dp(14f), 0, dp(14f), 0)
            minWidth = dp(64f)
            minHeight = dp(44f)
            isClickable = true
            isFocusable = true
            contentDescription = "Zoom 100 persen, ketuk untuk mengatur ulang"
            setOnClickListener {
                canvasView.viewport.zoomAt(1f / canvasView.viewport.scale, canvasView.width / 2f, canvasView.height / 2f)
                updateZoomIndicator()
                saveMeta()
            }
        }

        return Pair(locateContainer, zChip)
    }

    private fun updateZoomIndicator() {
        val pct = (canvasView.viewport.scale * 100f).toInt()
        if (pct != lastZoomPct) {
            lastZoomPct = pct
            chipZoom?.let {
                it.text = "$pct%"
                it.contentDescription = "Zoom $pct persen, ketuk untuk mengatur ulang"
            }
        }
        if (pct != pillZoomPct) {
            tabletZoomPill?.let {
                pillZoomPct = pct
                it.text = "$pct%"
                it.contentDescription = if (meta?.mode == NoteMode.INFINITE) {
                    "Zoom $pct persen, ketuk untuk mengatur ulang"
                } else {
                    "Zoom $pct persen, ketuk untuk beralih mode pas"
                }
            }
        }
    }

    private fun updateUndoRedoState() {
        val canU: Boolean
        val canR: Boolean
        if (meta?.mode == NoteMode.PAGED) {
            canU = canvasView.canUndo()
            canR = canvasView.canRedo()
        } else {
            val page = canvasView.getPage()
            canU = page?.canUndo() == true
            canR = page?.canRedo() == true
        }
        btnUndo.alpha = if (canU) 1.0f else 0.35f
        btnUndo.parent?.let { (it as View).isEnabled = canU }
        btnRedo.alpha = if (canR) 1.0f else 0.35f
        btnRedo.parent?.let { (it as View).isEnabled = canR }
        minimapView?.refresh()
    }

    private fun buildBottomToolbar(): LinearLayout {
        val isLandscape = isPhoneLandscape
        val bar = LinearLayout(this).apply {
            orientation = if (isLandscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (isLandscape) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 11)
            setPadding(if (isLandscape) dp(2f) else dp(4f), if (isLandscape) dp(4f) else dp(2f), if (isLandscape) dp(2f) else dp(4f), if (isLandscape) dp(4f) else dp(2f))
            if (isLandscape) {
                minimumWidth = dp(64f)
            } else {
                minimumHeight = dp(62f)
            }
        }

        val tools = listOf(
            Triple(Tool.PEN, Icon.PEN, "Pena"),
            Triple(Tool.MARKER, Icon.MARKER, "Spidol"),
            Triple(Tool.HIGHLIGHTER, Icon.HIGHLIGHTER, "Stabilo"),
            Triple(Tool.ERASER, Icon.ERASER, "Hapus"),
            Triple(Tool.TEXT, Icon.TEXT, "Teks"),
            Triple(Tool.PAN, Icon.PAN, "Geser")
        )

        for ((t, ic, title) in tools) {
            val itemContainer = FrameLayout(this).apply {
                isClickable = true
                isFocusable = true
                contentDescription = title
                background = null
            }

            val innerColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = false
                isFocusable = false
            }

            val iconV = IconView(this).apply {
                showSelection = false
                isClickable = false
                isFocusable = false
                icon = ic
                tint = Palette.INK
                strokeWidthDp = 2f
                contentDescription = title
            }
            innerColumn.addView(iconV, LinearLayout.LayoutParams(dp(24f), dp(24f)))

            val labelV = TextView(this).apply {
                text = title
                setTextColor(Palette.INK)
                textSize = toolLabelSp(13f)
                typeface = Fonts.ui(context)
                gravity = Gravity.CENTER
            }
            innerColumn.addView(labelV, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2f)
            })

            itemContainer.addView(innerColumn, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            ))

            val dot = View(this).apply {
                val dotBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(getColorForTool(t))
                    setStroke(dp(2f), Palette.INK)
                }
                background = dotBg
                visibility = View.GONE
            }
            val dotLp = FrameLayout.LayoutParams(dp(11f), dp(11f), Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(4f), dp(4f), 0)
            }
            itemContainer.addView(dot, dotLp)

            itemContainer.setOnClickListener {
                if (activeTool == t) {
                    if (isBrushTool(t)) {
                        showToolOptionsPopover(it, t)
                    }
                } else {
                    applyToolSelection(t)
                }
            }

            toolButtons[t] = itemContainer
            toolDots[t] = dot
            if (isLandscape) {
                bar.addView(itemContainer, LinearLayout.LayoutParams(dp(58f), 0, 1f).apply {
                    topMargin = dp(2f)
                    bottomMargin = dp(2f)
                })
            } else {
                bar.addView(itemContainer, LinearLayout.LayoutParams(0, dp(62f), 1f))
            }
        }

        return object : LinearLayout(this) {
            init {
                orientation = bar.orientation
                gravity = bar.gravity
                background = bar.background
                setPadding(bar.paddingLeft, bar.paddingTop, bar.paddingRight, bar.paddingBottom)
                while (bar.childCount > 0) {
                    val child = bar.getChildAt(0)
                    bar.removeViewAt(0)
                    addView(child)
                }
            }

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                if (isLandscape) {
                    val targetW = dp(64f)
                    val newWidthSpec = MeasureSpec.makeMeasureSpec(targetW, MeasureSpec.EXACTLY)
                    super.onMeasure(newWidthSpec, heightMeasureSpec)
                } else {
                    val maxAllowed = dp(520f)
                    val widthSize = MeasureSpec.getSize(widthMeasureSpec)
                    val widthMode = MeasureSpec.getMode(widthMeasureSpec)
                    val newWidthSpec = if (widthMode != MeasureSpec.UNSPECIFIED && widthSize > maxAllowed) {
                        MeasureSpec.makeMeasureSpec(maxAllowed, widthMode)
                    } else {
                        widthMeasureSpec
                    }
                    super.onMeasure(newWidthSpec, heightMeasureSpec)
                }
            }
        }
    }

    private fun isBrushTool(t: Tool): Boolean {
        return t == Tool.PEN || t == Tool.MARKER || t == Tool.HIGHLIGHTER
    }

    private fun applyToolSelection(t: Tool) {
        activeTool = t
        activeColor = getColorForTool(t)
        activeStrokeWidth = getWidthForTool(t)

        canvasView.tool = t
        canvasView.colorArgb = activeColor
        canvasView.widthWorld = activeStrokeWidth

        for ((toolType, view) in toolButtons) {
            val isSel = toolType == t
            view.isSelected = isSel
            if (isSel) {
                view.background = SketchDrawable(
                    fill = Palette.YELLOW,
                    stroke = Palette.INK,
                    shadowOffsetDp = 0f,
                    variant = 4
                )
            } else {
                view.background = null
            }
        }
        syncToolDots()
        if (isTablet) {
            updateTabletSidePanel()
        }
    }

    private fun syncToolDots() {
        for ((toolType, dot) in toolDots) {
            if (toolType == activeTool && isBrushTool(toolType)) {
                dot.visibility = View.VISIBLE
                val c = getColorForTool(toolType)
                val dotBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(dp(2f), Palette.INK)
                }
                dot.background = dotBg
            } else {
                dot.visibility = View.GONE
            }
        }
    }

    private class PopoverCardView(context: android.content.Context) : LinearLayout(context) {
        private val bgDrawable = SketchDrawable(
            fill = Palette.CARD,
            stroke = Palette.INK,
            strokeWidthDp = 2.5f,
            shadowOffsetDp = 4f,
            shadowColor = Palette.SHADOW_SOFT,
            variant = 8
        )
        private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Palette.CARD
        }
        private val arrowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Palette.INK
            strokeWidth = 2.5f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val arrowPath = Path()
        var arrowCenterX: Float = 0f

        init {
            setWillNotDraw(false)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val arrowH = 10f * resources.displayMetrics.density
            bgDrawable.setBounds(0, 0, w, (h - arrowH).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            val arrowH = 10f * resources.displayMetrics.density
            val arrowW = 16f * resources.displayMetrics.density
            val cardBottom = height.toFloat() - arrowH

            bgDrawable.draw(canvas)

            val cx = arrowCenterX.coerceIn(arrowW, width.toFloat() - arrowW)
            arrowPath.reset()
            arrowPath.moveTo(cx - arrowW * 0.5f, cardBottom - 1f)
            arrowPath.lineTo(cx, height.toFloat() - 1f)
            arrowPath.lineTo(cx + arrowW * 0.5f, cardBottom - 1f)
            arrowPath.close()

            canvas.drawPath(arrowPath, arrowFillPaint)
            canvas.drawLine(cx - arrowW * 0.5f, cardBottom - 1f, cx, height.toFloat() - 1f, arrowStrokePaint)
            canvas.drawLine(cx, height.toFloat() - 1f, cx + arrowW * 0.5f, cardBottom - 1f, arrowStrokePaint)

            super.onDraw(canvas)
        }
    }

    private class DashedSeparatorView(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x592B2724
            strokeWidth = 2f * resources.displayMetrics.density
            pathEffect = DashPathEffect(floatArrayOf(6f * resources.displayMetrics.density, 5f * resources.displayMetrics.density), 0f)
        }
        private val linePath = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            linePath.reset()
            val y = height * 0.5f
            linePath.moveTo(0f, y)
            linePath.lineTo(width.toFloat(), y)
            canvas.drawPath(linePath, paint)
        }
    }

    private class ColorSwatchView(context: android.content.Context) : View(context) {
        var color: Int = Palette.INK
            set(value) {
                field = value
                fillPaint.color = value
                invalidate()
            }

        var isSelectedSwatch: Boolean = false
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Palette.INK
        }

        private val innerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Palette.INK
        }

        private val gapRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Palette.CARD
        }

        private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Palette.INK
        }

        init {
            val d = resources.displayMetrics.density
            innerStrokePaint.strokeWidth = 2.5f * d
            gapRingPaint.strokeWidth = 3f * d
            outerRingPaint.strokeWidth = 2.5f * d
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val d = resources.displayMetrics.density
            val cx = width * 0.5f
            val cy = height * 0.5f

            val rDot = 15f * d
            canvas.drawCircle(cx, cy, rDot, fillPaint)
            canvas.drawCircle(cx, cy, rDot, innerStrokePaint)

            if (isSelectedSwatch) {
                val rGap = rDot + 1.25f * d + 1.5f * d
                canvas.drawCircle(cx, cy, rGap, gapRingPaint)
                val rOuter = rDot + 1.25f * d + 3f * d + 1.25f * d
                canvas.drawCircle(cx, cy, rOuter, outerRingPaint)
            }
        }
    }

    private fun showToolOptionsPopover(anchor: View, t: Tool) {
        optionsPopup?.dismiss()

        val cardView = PopoverCardView(this).apply {
            orientation = LinearLayout.VERTICAL
            val padH = dp(18f)
            val padTop = dp(16f)
            val padBottom = dp(24f)
            setPadding(padH, padTop, padH, padBottom)
        }

        val colorLabel = TextView(this).apply {
            text = "Warna"
            setTextColor(Palette.INK_SOFT)
            textSize = 16f
            typeface = Fonts.ui(context)
        }
        cardView.addView(colorLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6f)
        })

        val colors = listOf(
            Palette.INK,
            Palette.RED,
            Palette.BLUE,
            Palette.GREEN,
            Palette.ORANGE,
            Palette.PURPLE
        )

        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(4f), dp(4f), dp(8f))
        }

        val colorViews = mutableMapOf<Int, View>()
        val currentToolColor = getColorForTool(t)

        for (c in colors) {
            val cName = when (c) {
                Palette.INK -> "Warna hitam"
                Palette.RED -> "Warna merah"
                Palette.BLUE -> "Warna biru"
                Palette.GREEN -> "Warna hijau"
                Palette.ORANGE -> "Warna oranye"
                Palette.PURPLE -> "Warna ungu"
                else -> "Warna"
            }
            val cBtn = ColorSwatchView(this).apply {
                color = c
                isSelectedSwatch = c == currentToolColor
                contentDescription = cName
                isSelected = c == currentToolColor
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    stateDescription = if (c == currentToolColor) "Dipilih" else "Tidak dipilih"
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    setColorForTool(t, c)
                    activeColor = c
                    canvasView.colorArgb = c
                    syncToolDots()

                    for ((colorVal, btn) in colorViews) {
                        val isSel = colorVal == c
                        (btn as? ColorSwatchView)?.let { swatch ->
                            swatch.isSelectedSwatch = isSel
                            swatch.isSelected = isSel
                            if (android.os.Build.VERSION.SDK_INT >= 30) {
                                swatch.stateDescription = if (isSel) "Dipilih" else "Tidak dipilih"
                            }
                        }
                    }
                }
            }
            colorViews[c] = cBtn

            val cLp = LinearLayout.LayoutParams(dp(44f), dp(44f)).apply {
                if (c != colors.first()) marginStart = dp(12f)
            }
            colorRow.addView(cBtn, cLp)
        }
        cardView.addView(colorRow)

        val thickLabel = TextView(this).apply {
            text = "Ketebalan"
            setTextColor(Palette.INK_SOFT)
            textSize = 16f
            typeface = Fonts.ui(context)
        }
        cardView.addView(thickLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4f)
            bottomMargin = dp(6f)
        })

        val range = widthRangeForTool(t)
        val thickSlider = SketchSlider(
            context = this,
            min = range[0],
            max = range[1],
            step = range[2],
            initial = getWidthForTool(t),
            description = "Ketebalan",
            showButtons = true,
            compact = true,
            onChange = { v ->
                setWidthForTool(t, v)
                activeStrokeWidth = v
                canvasView.widthWorld = v
                tabletStrokePreview?.let {
                    (it as WavyPreviewView).strokeWidthWorld = v
                }
            },
            onCommit = { v ->
                val key = when (t) {
                    Tool.PEN -> "width_pen"
                    Tool.MARKER -> "width_marker"
                    Tool.HIGHLIGHTER -> "width_highlighter"
                    else -> null
                }
                if (key != null) {
                    getSharedPreferences("tulis_prefs", MODE_PRIVATE).edit().putFloat(key, v).apply()
                }
            }
        )
        cardView.addView(thickSlider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val separator = DashedSeparatorView(this)
        cardView.addView(separator, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14f)).apply {
            topMargin = dp(14f)
        })

        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val switchLabel = TextView(this).apply {
            text = "Jari bisa menggambar"
            setTextColor(Palette.INK)
            textSize = 19f
            typeface = Fonts.ui(context)
        }
        switchRow.addView(switchLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val switchBtn = FrameLayout(this).apply {
            val isChecked = fingerCanDraw
            background = SketchDrawable(
                fill = if (isChecked) Palette.YELLOW else Palette.CARD,
                stroke = Palette.INK,
                variant = 6
            )
            isClickable = true
            isFocusable = true
            contentDescription = "Jari bisa menggambar"
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                stateDescription = if (isChecked) "aktif" else "nonaktif"
            }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Switch"
                    info.isCheckable = true
                    info.isChecked = fingerCanDraw
                }
            }
        }
        phoneSwitchBtn = switchBtn

        val knob = View(this).apply {
            val knobBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Palette.INK)
            }
            background = knobBg
        }
        phoneSwitchKnob = knob
        val knobLp = FrameLayout.LayoutParams(dp(22f), dp(22f)).apply {
            topMargin = dp(4f)
            if (fingerCanDraw) {
                gravity = Gravity.TOP or Gravity.END
                marginEnd = dp(4f)
            } else {
                gravity = Gravity.TOP or Gravity.START
                marginStart = dp(4f)
            }
        }
        switchBtn.addView(knob, knobLp)

        switchBtn.setOnClickListener {
            val nextState = !fingerCanDraw
            setFingerDrawState(nextState)
        }

        switchRow.addView(switchBtn, LinearLayout.LayoutParams(dp(60f), dp(34f)))
        cardView.addView(switchRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4f)
        })

        val maxAllowedW = dp(520f)
        val availableW = (rootLayout.width - dp(24f)).coerceAtLeast(dp(200f))
        val targetW = Math.min(availableW, maxAllowedW)

        val popup = PopupWindow(
            cardView,
            targetW,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            setOnDismissListener {
                phoneSwitchBtn = null
                phoneSwitchKnob = null
                optionsPopup = null
            }
        }
        optionsPopup = popup

        val rootLoc = IntArray(2)
        rootLayout.getLocationOnScreen(rootLoc)
        val anchorLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)

        val anchorCenterX = anchorLoc[0] + anchor.width * 0.5f
        val popupLeft = (anchorCenterX - targetW * 0.5f).toInt()
            .coerceIn(rootLoc[0] + dp(12f), rootLoc[0] + rootLayout.width - targetW - dp(12f))

        cardView.arrowCenterX = anchorCenterX - popupLeft

        cardView.measure(
            View.MeasureSpec.makeMeasureSpec(targetW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popH = cardView.measuredHeight
        val y = anchorLoc[1] - rootLoc[1] - popH - dp(6f)

        popup.showAtLocation(rootLayout, Gravity.NO_GRAVITY, popupLeft, rootLoc[1] + y)
    }

    private fun buildPageBar(m: NotebookMeta): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val pillContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44f)
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 0)
            setPadding(dp(4f), 0, dp(4f), 0)
        }

        val prevBtn = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            minimumHeight = dp(44f)
            contentDescription = "Halaman sebelumnya"
            setOnClickListener {
                if (currentPageIndex > 0) {
                    switchPage(currentPageIndex - 1)
                }
            }
        }
        val prevIcon = IconView(this).apply {
            showSelection = false
            isClickable = false
            isFocusable = false
            contentDescription = "Halaman sebelumnya"
            icon = Icon.PREV
            tint = Palette.INK
            strokeWidthDp = 2.2f
        }
        btnPrevPage = prevIcon
        prevBtn.addView(prevIcon, FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.CENTER))
        pillContainer.addView(prevBtn, LinearLayout.LayoutParams(dp(44f), ViewGroup.LayoutParams.WRAP_CONTENT))

        val indicator = TextView(this).apply {
            text = "1 / ${m.pageCount}"
            setTextColor(Palette.INK)
            textSize = 21f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            minWidth = dp(62f)
        }
        pageIndicator = indicator
        pillContainer.addView(indicator, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val nextBtn = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            minimumHeight = dp(44f)
            contentDescription = "Halaman berikutnya"
            setOnClickListener {
                val currentM = meta ?: return@setOnClickListener
                if (currentPageIndex < currentM.pageCount - 1) {
                    switchPage(currentPageIndex + 1)
                }
            }
        }
        val nextIcon = IconView(this).apply {
            showSelection = false
            isClickable = false
            isFocusable = false
            contentDescription = "Halaman berikutnya"
            icon = Icon.NEXT
            tint = Palette.INK
            strokeWidthDp = 2.2f
        }
        btnNextPage = nextIcon
        nextBtn.addView(nextIcon, FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.CENTER))
        pillContainer.addView(nextBtn, LinearLayout.LayoutParams(dp(44f), ViewGroup.LayoutParams.WRAP_CONTENT))

        bar.addView(pillContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val spacer = View(this)
        bar.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))

        val rightGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val addBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44f)
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 1)
            setPadding(dp(12f), 0, dp(16f), 0)
            isClickable = true
            isFocusable = true
            contentDescription = "+ Halaman"
            setOnClickListener {
                addNewPage()
            }
        }
        val addIcon = IconView(this).apply {
            showSelection = false
            isClickable = false
            isFocusable = false
            contentDescription = "+ Halaman"
            icon = Icon.ADD
            tint = Palette.INK
            strokeWidthDp = 2.4f
        }
        addBtn.addView(addIcon, LinearLayout.LayoutParams(dp(22f), dp(22f)).apply {
            marginEnd = dp(6f)
        })
        val addText = TextView(this).apply {
            text = "Halaman"
            setTextColor(Palette.INK)
            textSize = 19f
            typeface = Fonts.ui(context)
        }
        addBtn.addView(addText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        btnAddPage = addBtn
        rightGroup.addView(addBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(8f)
        })

        val delBtn = FrameLayout(this).apply {
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, round = true)
            isClickable = true
            isFocusable = true
            minimumHeight = dp(44f)
            contentDescription = "Hapus halaman ini"
            setOnClickListener {
                confirmDeletePage()
            }
        }
        val delIcon = IconView(this).apply {
            showSelection = false
            isClickable = false
            isFocusable = false
            contentDescription = "Hapus halaman ini"
            icon = Icon.TRASH
            tint = Palette.RED
            strokeWidthDp = 2f
        }
        delBtn.addView(delIcon, FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.CENTER))
        btnDeletePage = delBtn
        rightGroup.addView(delBtn, LinearLayout.LayoutParams(dp(52f), ViewGroup.LayoutParams.WRAP_CONTENT))

        bar.addView(rightGroup, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        return object : LinearLayout(this) {
            init {
                orientation = bar.orientation
                gravity = bar.gravity
                while (bar.childCount > 0) {
                    val child = bar.getChildAt(0)
                    bar.removeViewAt(0)
                    addView(child)
                }
            }

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxAllowed = dp(520f)
                val widthSize = MeasureSpec.getSize(widthMeasureSpec)
                val widthMode = MeasureSpec.getMode(widthMeasureSpec)
                val newWidthSpec = if (widthMode != MeasureSpec.UNSPECIFIED && widthSize > maxAllowed) {
                    MeasureSpec.makeMeasureSpec(maxAllowed, widthMode)
                } else {
                    widthMeasureSpec
                }
                super.onMeasure(newWidthSpec, heightMeasureSpec)
            }
        }
    }

    private fun updatePageIndicator() {
        val m = meta ?: return
        val currentDisplay = currentPageIndex + 1
        pageIndicator?.text = "$currentDisplay / ${m.pageCount}"
        tabletPagePill?.text = "$currentDisplay / ${m.pageCount}"
        btnPrevPage?.let {
            val enabled = currentPageIndex > 0
            it.parent?.let { p -> (p as View).isEnabled = enabled }
            it.alpha = if (enabled) 1.0f else 0.35f
        }
        btnNextPage?.let {
            val enabled = currentPageIndex < m.pageCount - 1
            it.parent?.let { p -> (p as View).isEnabled = enabled }
            it.alpha = if (enabled) 1.0f else 0.35f
        }
        val canDelete = m.pageCount > 1
        btnDeletePage?.visibility = if (canDelete) View.VISIBLE else View.GONE
        tabletBtnDeletePage?.visibility = if (canDelete) View.VISIBLE else View.GONE
    }

    private fun switchPage(targetIndex: Int) {
        if (targetIndex == currentPageIndex) return
        if (meta?.mode == NoteMode.PAGED) {
            canvasView.scrollToPage(targetIndex)
            return
        }
        flushAutosave(immediateThumb = false)
        canvasView.setPage(null)
        currentPageIndex = targetIndex
        meta?.lastPage = targetIndex
        saveMeta()
        loadPage(targetIndex)
        updatePageIndicator()
        meta?.let {
            rootLayout.announceForAccessibility("Halaman ${currentPageIndex + 1} dari ${it.pageCount}")
        }
        if (isTablet) {
            refreshTabletPageRail()
        }
    }

    private fun addNewPage() {
        val m = meta ?: return
        if (m.mode == NoteMode.PAGED) {
            val s = pageStack ?: return
            flushAutosave(immediateThumb = false)
            val newIndex = m.pageCount
            m.pageCount += 1
            m.lastPage = newIndex
            saveMeta()
            val newPage = PageContent()
            repo.insertPage(notebookId, newIndex, m.pageCount, newPage)
            s.insertAt(newIndex, newPage)
            canvasView.scrollToPage(newIndex)
            updatePageIndicator()
            rootLayout.announceForAccessibility("Halaman ${currentPageIndex + 1} dari ${m.pageCount}")
            if (isTablet) {
                refreshTabletPageRail()
            }
            return
        }
        flushAutosave(immediateThumb = false)
        val newIndex = m.pageCount
        m.pageCount += 1
        m.lastPage = newIndex
        saveMeta()
        canvasView.setPage(null)
        currentPageIndex = newIndex
        loadPage(newIndex)
        updatePageIndicator()
        rootLayout.announceForAccessibility("Halaman ${currentPageIndex + 1} dari ${m.pageCount}")
        if (isTablet) {
            refreshTabletPageRail()
        }
    }

    private fun confirmDeletePage() {
        val m = meta ?: return
        if (m.pageCount <= 1) return

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 0)
            val pad = dp(20f)
            setPadding(pad, pad, pad, pad)
        }
        val titleV = TextView(this).apply {
            text = "Hapus Halaman"
            setTextColor(Palette.INK)
            textSize = 24f
            typeface = Fonts.hand(context, bold = true)
        }
        dialogView.addView(titleV, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12f)
        })
        val messageV = TextView(this).apply {
            text = "Hapus halaman ini?"
            setTextColor(Palette.INK_SOFT)
            textSize = 18f
            typeface = Fonts.ui(context)
        }
        dialogView.addView(messageV, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(20f)
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        var dialogRef: AlertDialog? = null

        val cancelBtn = TextView(this).apply {
            text = "Batal"
            setTextColor(Palette.INK)
            textSize = 18f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK_SOFT, variant = 2)
            setPadding(dp(16f), 0, dp(16f), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialogRef?.dismiss()
            }
        }
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44f)).apply {
            marginEnd = dp(10f)
        })

        val deleteBtn = TextView(this).apply {
            text = "Hapus"
            setTextColor(Palette.PAPER)
            textSize = 18f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            background = SketchDrawable(fill = Palette.RED, stroke = Palette.INK, variant = 3)
            setPadding(dp(16f), 0, dp(16f), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialogRef?.dismiss()
                executeDeleteCurrentPage()
            }
        }
        btnRow.addView(deleteBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44f)))

        dialogView.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialogRef = AlertDialog.Builder(this)
            .setView(dialogView)
            .show().apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
            }
    }

    private fun executeDeleteCurrentPage() {
        val m = meta ?: return
        if (m.pageCount <= 1) return
        if (m.mode == NoteMode.PAGED) {
            val s = pageStack ?: return
            flushAutosave(immediateThumb = false)
            val deleteIdx = currentPageIndex
            val deletedSnapshot = s.get(deleteIdx)?.snapshot() ?: PageContent()
            val prevPageCount = m.pageCount
            repo.deletePage(notebookId, deleteIdx, prevPageCount)
            m.pageCount -= 1
            s.removeAt(deleteIdx)
            val nextIdx = if (deleteIdx >= m.pageCount) m.pageCount - 1 else deleteIdx
            currentPageIndex = nextIdx
            m.lastPage = nextIdx
            saveMeta()
            canvasView.scrollToPage(nextIdx)
            updatePageIndicator()
            rootLayout.announceForAccessibility("Halaman ${currentPageIndex + 1} dari ${m.pageCount}")
            repo.afterPending {
                thumbMemoryCache.evictAll()
                if (isTablet) {
                    refreshTabletPageRail()
                }
            }
            UndoBar.show(rootLayout, "Halaman dihapus", onUndo = {
                val curMeta = meta ?: return@show
                val curStack = pageStack ?: return@show
                val restoredCount = curMeta.pageCount + 1
                repo.insertPage(notebookId, deleteIdx, restoredCount, deletedSnapshot)
                curStack.insertAt(deleteIdx, deletedSnapshot)
                curMeta.pageCount = restoredCount
                curMeta.lastPage = deleteIdx
                saveMeta()
                currentPageIndex = deleteIdx
                canvasView.scrollToPage(deleteIdx)
                updatePageIndicator()
                rootLayout.announceForAccessibility("Halaman ${currentPageIndex + 1} dari ${curMeta.pageCount}")
                repo.afterPending {
                    thumbMemoryCache.evictAll()
                    if (isTablet) {
                        refreshTabletPageRail()
                    }
                }
            })
            return
        }
        val deleteIdx = currentPageIndex
        val deletedSnapshot = canvasView.getPage()?.snapshot() ?: PageContent()
        val prevPageCount = m.pageCount
        repo.deletePage(notebookId, deleteIdx, prevPageCount)
        m.pageCount -= 1
        val nextIdx = if (deleteIdx >= m.pageCount) m.pageCount - 1 else deleteIdx
        currentPageIndex = nextIdx
        m.lastPage = nextIdx
        saveMeta()
        canvasView.setPage(null)
        loadPage(nextIdx)
        updatePageIndicator()
        rootLayout.announceForAccessibility("Halaman ${currentPageIndex + 1} dari ${m.pageCount}")
        if (isTablet) {
            refreshTabletPageRail()
        }
        UndoBar.show(rootLayout, "Halaman dihapus", onUndo = {
            val curMeta = meta ?: return@show
            val restoredCount = curMeta.pageCount + 1
            repo.insertPage(notebookId, deleteIdx, restoredCount, deletedSnapshot)
            curMeta.pageCount = restoredCount
            curMeta.lastPage = deleteIdx
            saveMeta()
            currentPageIndex = deleteIdx
            canvasView.setPage(null)
            loadPage(deleteIdx)
            updatePageIndicator()
            rootLayout.announceForAccessibility("Halaman ${currentPageIndex + 1} dari ${curMeta.pageCount}")
            if (isTablet) {
                refreshTabletPageRail()
            }
        })
    }

    private fun loadPage(index: Int) {
        pageLoadToken++
        val token = pageLoadToken
        repo.loadPage(notebookId, index) { loadedPage ->
            if (token != pageLoadToken) return@loadPage
            lastSavedRevision = loadedPage?.revision ?: -1
            canvasView.setPage(loadedPage)
            if (meta?.mode == NoteMode.PAGED) {
                canvasView.fitPage()
            }
            updateUndoRedoState()
            minimapView?.refresh()
        }
    }

    private fun showTextEditDialog(item: TextItem?, wx: Float, wy: Float) {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 0)
            val pad = dp(20f)
            setPadding(pad, pad, pad, pad)
        }

        val titleV = TextView(this).apply {
            text = if (item == null) "Tambah Teks" else "Ubah Teks"
            setTextColor(Palette.INK)
            textSize = 24f
            typeface = Fonts.hand(context, bold = true)
        }
        dialogView.addView(titleV, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14f)
        })

        val editText = EditText(this).apply {
            setText(item?.text ?: "")
            setSelection(text.length)
            setTextColor(Palette.INK)
            textSize = 18f
            typeface = Fonts.ui(context)
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK_SOFT, variant = 2)
            val p = dp(12f)
            setPadding(p, p, p, p)
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        }
        dialogView.addView(editText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(18f)
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        var dialogRef: AlertDialog? = null

        if (item != null) {
            val deleteBtn = TextView(this).apply {
                text = "Hapus"
                setTextColor(Palette.PAPER)
                textSize = 17f
                typeface = Fonts.ui(context)
                gravity = Gravity.CENTER
                background = SketchDrawable(fill = Palette.RED, stroke = Palette.INK, variant = 3)
                setPadding(dp(14f), 0, dp(14f), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialogRef?.dismiss()
                    canvasView.applyTextEdit(item, wx, wy, "")
                }
            }
            btnRow.addView(deleteBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44f)))
        }

        val spacer = View(this)
        btnRow.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))

        val cancelBtn = TextView(this).apply {
            text = "Batal"
            setTextColor(Palette.INK)
            textSize = 17f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK_SOFT, variant = 2)
            setPadding(dp(14f), 0, dp(14f), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialogRef?.dismiss()
            }
        }
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44f)).apply {
            marginEnd = dp(8f)
        })

        val saveBtn = TextView(this).apply {
            text = "Simpan"
            setTextColor(Palette.PAPER)
            textSize = 17f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            background = SketchDrawable(fill = Palette.INK, stroke = Palette.INK, variant = 3)
            setPadding(dp(16f), 0, dp(16f), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialogRef?.dismiss()
                val newText = editText.text.toString()
                canvasView.applyTextEdit(item, wx, wy, newText)
            }
        }
        btnRow.addView(saveBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44f)))

        dialogView.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialogRef = AlertDialog.Builder(this)
            .setView(dialogView)
            .show().apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
            }
    }

    private fun scheduleAutosave() {
        mainHandler.removeCallbacks(autosaveRunnable)
        isAutosavePending = true
        mainHandler.postDelayed(autosaveRunnable, 1200L)
    }

    private fun confirmOrientationChange(target: PageOrientation) {
        val m = meta ?: return
        flushAutosave(immediateThumb = true)
        val count = m.pageCount
        val newW = target.pageW
        val newH = target.pageH
        var clipped = 0
        val box = RectF()
        fun finish() {
            val msg = StringBuilder("Orientasi halaman diubah ke ${if (target == PageOrientation.LANDSCAPE) "lanskap" else "potret"}. Riwayat urungkan akan direset.")
            if (clipped > 0) {
                msg.append("\n\n$clipped halaman punya tulisan di luar batas baru. Tulisan itu tersembunyi sampai orientasi dikembalikan.")
            }
            AlertDialog.Builder(this)
                .setTitle("Ubah orientasi?")
                .setMessage(msg.toString())
                .setNegativeButton("Batal", null)
                .setPositiveButton("Ubah") { _, _ ->
                    m.orientation = target
                    m.lastPage = currentPageIndex
                    isMetaDirty = true
                    saveMeta()
                    repo.deleteAllThumbsAsync(notebookId, count)
                    thumbMemoryCache.evictAll()
                    recreate()
                }
                .show()
        }
        fun scan(i: Int) {
            if (i >= count) {
                finish()
                return
            }
            repo.loadPage(notebookId, i) { p ->
                var out = false
                for (st in p.strokes) {
                    if (st.worldBounds(box) && (box.right > newW || box.bottom > newH)) {
                        out = true
                        break
                    }
                }
                if (!out) {
                    for (t in p.texts) {
                        t.worldBounds(box)
                        if (box.right > newW || box.bottom > newH) {
                            out = true
                            break
                        }
                    }
                }
                if (out) clipped++
                scan(i + 1)
            }
        }
        scan(0)
    }

    private fun ensurePageThumb(idx: Int, page: PageContent) {
        if (repo.pageThumbFile(notebookId, idx).exists()) return
        val bmp = canvasView.renderPageThumbnail(page, 192)
        if (idx == 0) repo.saveThumbAsync(notebookId, bmp)
        repo.savePageThumbAsync(notebookId, idx, bmp)
        thumbMemoryCache.put("${notebookId}_$idx", bmp)
        if (isTablet) refreshTabletPageRail()
    }

    private fun flushAutosave(immediateThumb: Boolean) {
        mainHandler.removeCallbacks(autosaveRunnable)
        isAutosavePending = false

        val m = meta ?: return
        if (m.mode == NoteMode.PAGED) {
            val s = pageStack ?: return
            val dirty = s.dirtyPages()
            if (dirty.isNotEmpty()) {
                for ((idx, p) in dirty) {
                    repo.savePageAsync(notebookId, idx, p.snapshot())
                    val bmp = canvasView.renderPageThumbnail(p, 192)
                    if (idx == 0) {
                        repo.saveThumbAsync(notebookId, bmp)
                    }
                    repo.savePageThumbAsync(notebookId, idx, bmp)
                    thumbMemoryCache.put("${notebookId}_$idx", bmp)
                    s.markSaved(p)
                }
                if (isTablet) {
                    refreshTabletPageRail()
                }
            }
            saveMeta()
            return
        }
        val page = canvasView.getPage()
        val currentRev = page?.revision
        if (page != null && currentRev != null && currentRev != lastSavedRevision) {
            val snapshot = page.snapshot()
            repo.savePageAsync(notebookId, currentPageIndex, snapshot)
            lastSavedRevision = currentRev

            val thumbBitmap = canvasView.renderThumbnail(192)
            if (currentPageIndex == 0) {
                repo.saveThumbAsync(notebookId, thumbBitmap)
            }
            repo.savePageThumbAsync(notebookId, currentPageIndex, thumbBitmap)
            val cacheKey = "${notebookId}_$currentPageIndex"
            thumbMemoryCache.put(cacheKey, thumbBitmap)
            if (isTablet) {
                refreshTabletPageRail()
            }
        }

        saveMeta()
    }


    private var pendingExport: ExportOptions? = null
    private var exportCancel: java.util.concurrent.atomic.AtomicBoolean? = null

    private fun startExport(opts: ExportOptions) {
        val m = meta ?: return
        pendingExport = opts
        val safeTitle = m.title.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().ifEmpty { "Tulis" }
        val isPdf = opts.format == com.example.tulis.export.ExportFormat.PDF
        val suffix = if (!isPdf && m.mode == NoteMode.PAGED) "_hal${currentPageIndex + 1}" else ""
        val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = if (isPdf) "application/pdf" else "image/png"
            putExtra(android.content.Intent.EXTRA_TITLE, safeTitle + suffix + if (isPdf) ".pdf" else ".png")
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_EXPORT)
        } catch (_: Exception) {
            pendingExport = null
            Toast.makeText(this, "Tidak ada aplikasi penyimpanan file", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT) return
        val opts = pendingExport
        pendingExport = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || opts == null) return
        runExport(uri, opts)
    }

    private fun runExport(uri: android.net.Uri, opts: ExportOptions) {
        val m = meta ?: return
        flushAutosave(immediateThumb = false)

        val snapshots = HashMap<Int, PageContent>()
        val indices: List<Int>
        var infinitePage: PageContent? = null
        if (m.mode == NoteMode.PAGED) {
            val s = pageStack ?: return
            for (i in s.residentIndices()) {
                s.get(i)?.let { snapshots[i] = it.snapshot() }
            }
            indices = if (opts.allPages) (0 until m.pageCount).toList() else listOf(currentPageIndex)
        } else {
            infinitePage = canvasView.getPage()?.snapshot()
            indices = listOf(0)
        }

        val job = ExportJob(
            notebookId, m.mode, m.orientation, m.paperStyle, m.paperSpacing,
            opts, indices, snapshots, infinitePage
        )
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        exportCancel = cancel
        val progressDialog = ExportProgressDialog(this) { cancel.set(true) }
        val alive = { !isFinishing && !isDestroyed }

        repo.afterPending {
            Thread {
                var error: String? = null
                try {
                    val out = contentResolver.openOutputStream(uri, "w") ?: throw java.io.IOException("Tidak bisa menulis file")
                    out.use {
                        Exporter.run(job, it, cancel, { idx -> repo.loadPageSync(notebookId, idx) }) { n, total ->
                            mainHandler.post { if (alive()) progressDialog.update("Halaman $n dari $total") }
                        }
                    }
                } catch (e: com.example.tulis.export.ExportCancelled) {
                    error = ""
                } catch (e: Throwable) {
                    error = e.message ?: "Ekspor gagal"
                }
                val err = error
                if (err != null) {
                    try {
                        android.provider.DocumentsContract.deleteDocument(contentResolver, uri)
                    } catch (_: Throwable) {
                    }
                }
                mainHandler.post {
                    exportCancel = null
                    if (!alive()) return@post
                    progressDialog.dismiss()
                    val msg = when {
                        err == null -> "Ekspor selesai"
                        err.isEmpty() -> "Ekspor dibatalkan"
                        else -> "Ekspor gagal: $err"
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }.start()
        }
    }

    private fun saveMeta() {
        val m = meta ?: return
        if (isMetaDirty) {
            m.updatedAt = System.currentTimeMillis()
            isMetaDirty = false
        }
        if (m.mode == NoteMode.INFINITE) {
            m.vpScale = canvasView.viewport.scale
            m.vpTx = canvasView.viewport.tx
            m.vpTy = canvasView.viewport.ty
        } else {
            m.lastPage = currentPageIndex
        }
        repo.saveMetaAsync(m)
    }

    private fun showRenameSketchDialog() {
        val m = meta ?: return
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 0)
            val pad = dp(20f)
            setPadding(pad, pad, pad, pad)
        }

        val titleV = TextView(this).apply {
            text = "Ganti Nama Notebook"
            setTextColor(Palette.INK)
            textSize = 24f
            typeface = Fonts.hand(context, bold = true)
        }
        dialogView.addView(titleV, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14f)
        })

        val editText = EditText(this).apply {
            setText(m.title)
            setSelection(m.title.length)
            setTextColor(Palette.INK)
            textSize = 18f
            typeface = Fonts.ui(context)
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK_SOFT, variant = 2)
            val p = dp(12f)
            setPadding(p, p, p, p)
            isSingleLine = true
        }
        dialogView.addView(editText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(18f)
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        var dialogRef: AlertDialog? = null

        val cancelBtn = TextView(this).apply {
            text = "Batal"
            setTextColor(Palette.INK)
            textSize = 17f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK_SOFT, variant = 2)
            setPadding(dp(16f), 0, dp(16f), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialogRef?.dismiss()
            }
        }
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44f)).apply {
            marginEnd = dp(10f)
        })

        val saveBtn = TextView(this).apply {
            text = "Simpan"
            setTextColor(Palette.PAPER)
            textSize = 17f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            background = SketchDrawable(fill = Palette.INK, stroke = Palette.INK, variant = 3)
            setPadding(dp(16f), 0, dp(16f), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialogRef?.dismiss()
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty() && newName != m.title) {
                    repo.rename(notebookId, newName)
                    m.title = newName
                    titleView.text = newName
                }
            }
        }
        btnRow.addView(saveBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44f)))

        dialogView.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialogRef = AlertDialog.Builder(this)
            .setView(dialogView)
            .show().apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
            }
    }

    override fun onBackPressed() {
        flushAutosave(immediateThumb = true)
        super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        flushAutosave(immediateThumb = true)
        repo.flush()
        if (notebookId.isNotEmpty()) com.example.tulis.storage.VaultSync.get(this).requestSync(listOf(notebookId))
    }

    override fun onDestroy() {
        super.onDestroy()
        exportCancel?.set(true)
        optionsPopup?.dismiss()
        thumbExecutor.shutdown()
        thumbMemoryCache.evictAll()
        canvasView.release()
    }
}
