package com.example.tulis

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.tulis.model.NoteMode
import com.example.tulis.model.NotebookMeta
import com.example.tulis.storage.NotebookRepository
import com.example.tulis.ui.Fonts
import com.example.tulis.ui.Icon
import com.example.tulis.ui.IconView
import com.example.tulis.ui.Palette
import com.example.tulis.ui.SketchDrawable
import com.example.tulis.ui.UndoBar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private enum class FilterTab { ALL, INFINITE, PAGED }
    private enum class SortOrder { LATEST, TITLE_AZ }

    private lateinit var repo: NotebookRepository
    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())
    private val thumbExecutor = Executors.newFixedThreadPool(2)
    private val thumbMemoryCache = object : LruCache<String, Bitmap>(32) {}

    private var allNotebooks: List<NotebookMeta> = emptyList()
    private var displayedNotebooks: List<NotebookMeta> = emptyList()
    private var currentFilter = FilterTab.ALL
    private var currentSortOrder = SortOrder.LATEST
    private var currentSearchQuery = ""
    private var isSearchVisible = false

    private lateinit var gridView: GridView
    private lateinit var adapter: NotebookAdapter
    private lateinit var emptyContainer: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var emptyActionBtn: TextView

    private lateinit var chipAll: TextView
    private lateinit var chipInfinite: TextView
    private lateinit var chipPaged: TextView

    private lateinit var searchInput: EditText
    private lateinit var searchContainer: LinearLayout
    private lateinit var sortBtn: TextView
    private lateinit var trashBadgeView: TextView
    private lateinit var rootLayout: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = NotebookRepository(this)
        prefs = getSharedPreferences("tulis_prefs", Context.MODE_PRIVATE)
        val savedSort = prefs.getString("sort_order", SortOrder.LATEST.name)
        currentSortOrder = try {
            SortOrder.valueOf(savedSort ?: SortOrder.LATEST.name)
        } catch (t: Throwable) {
            SortOrder.LATEST
        }
        repo.purgeExpired()
        setContentView(buildContentView())
    }

    override fun onResume() {
        super.onResume()
        updateTrashBadge()
        refreshNotebooks()
    }

    override fun onBackPressed() {
        if (isSearchVisible) {
            toggleSearch()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        thumbExecutor.shutdown()
        thumbMemoryCache.evictAll()
    }

    private fun updateTrashBadge() {
        if (!::trashBadgeView.isInitialized) return
        val count = repo.trashCount()
        if (count > 0) {
            trashBadgeView.visibility = View.VISIBLE
            trashBadgeView.text = if (count > 99) "99+" else count.toString()
        } else {
            trashBadgeView.visibility = View.GONE
        }
    }

    private fun dp(value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        ).toInt()
    }

    private fun buildContentView(): View {
        val root = FrameLayout(this).apply {
            fitsSystemWindows = true
            background = DotGridDrawable(22f, Palette.DOT_SOFT, Palette.PAPER)
        }
        rootLayout = root

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(24f), dp(44f), dp(24f), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val titleView = TextView(this).apply {
            text = "Tulis"
            textSize = 54f
            typeface = Fonts.hand(context, true)
            setTextColor(Palette.INK)
            includeFontPadding = false
        }
        val wavyLine = WavyHeaderView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(104f),
                dp(14f)
            ).apply {
                topMargin = dp(2f)
            }
        }
        titleContainer.addView(titleView)
        titleContainer.addView(wavyLine)
        headerLayout.addView(titleContainer)

        val sortPill = TextView(this).apply {
            minimumHeight = dp(44f)
            minimumWidth = dp(44f)
            textSize = 16f
            typeface = Fonts.ui(context)
            setTextColor(Palette.INK)
            gravity = Gravity.CENTER
            val padH = dp(14f)
            setPadding(padH, 0, padH, 0)
            background = SketchDrawable(
                seed = 776L,
                fill = Palette.CARD,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = 2.5f,
                cornerDp = 14f,
                variant = 0
            )
            isClickable = true
            isFocusable = true
            text = if (currentSortOrder == SortOrder.LATEST) "Terbaru" else "Judul A-Z"
            contentDescription = "Urutan catatan: $text"
            setOnClickListener {
                currentSortOrder = if (currentSortOrder == SortOrder.LATEST) SortOrder.TITLE_AZ else SortOrder.LATEST
                prefs.edit().putString("sort_order", currentSortOrder.name).apply()
                text = if (currentSortOrder == SortOrder.LATEST) "Terbaru" else "Judul A-Z"
                contentDescription = "Urutan catatan: $text"
                applyFilter()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                rightMargin = dp(10f)
            }
        }
        sortBtn = sortPill
        headerLayout.addView(sortPill)

        val trashBtn = FrameLayout(this).apply {
            val s48 = dp(48f)
            layoutParams = LinearLayout.LayoutParams(s48, s48).apply {
                gravity = Gravity.CENTER_VERTICAL
                rightMargin = dp(10f)
            }
            background = SketchDrawable(
                seed = 778L,
                fill = Palette.CARD,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = 2.5f,
                cornerDp = 24f,
                round = true
            )
            isClickable = true
            isFocusable = true
            contentDescription = "Baru dihapus"

            val trashIcon = IconView(context).apply {
                icon = Icon.TRASH
                tint = Palette.INK
                strokeWidthDp = 2f
                isClickable = false
                isFocusable = false
                layoutParams = FrameLayout.LayoutParams(dp(24f), dp(24f)).apply {
                    gravity = Gravity.CENTER
                }
            }
            addView(trashIcon)

            val badge = TextView(context).apply {
                textSize = 12f
                typeface = Fonts.ui(context)
                setTextColor(Palette.CARD)
                gravity = Gravity.CENTER
                background = SketchDrawable(
                    seed = 779L,
                    fill = Palette.RED,
                    stroke = Palette.INK,
                    strokeWidthDp = 1.5f,
                    shadowOffsetDp = 0f,
                    cornerDp = 10f,
                    round = true
                )
                includeFontPadding = false
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(dp(20f), dp(20f)).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = dp(-2f)
                    rightMargin = dp(-2f)
                }
            }
            trashBadgeView = badge
            addView(badge)

            setOnClickListener {
                startActivity(Intent(this@MainActivity, TrashActivity::class.java))
            }
        }
        headerLayout.addView(trashBtn)

        val searchBtn = FrameLayout(this).apply {
            val s48 = dp(48f)
            layoutParams = LinearLayout.LayoutParams(s48, s48).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            background = SketchDrawable(
                seed = 777L,
                fill = Palette.CARD,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = 2.5f,
                cornerDp = 24f,
                round = true
            )
            isClickable = true
            isFocusable = true
            contentDescription = "Cari catatan"

            val searchIcon = IconView(context).apply {
                icon = Icon.SEARCH
                tint = Palette.INK
                strokeWidthDp = 2f
                isClickable = false
                isFocusable = false
                layoutParams = FrameLayout.LayoutParams(dp(24f), dp(24f)).apply {
                    gravity = Gravity.CENTER
                }
            }
            addView(searchIcon)

            setOnClickListener {
                toggleSearch()
            }
        }
        headerLayout.addView(searchBtn)
        contentLayout.addView(headerLayout)

        searchContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48f)
            visibility = View.GONE
            val padH = dp(14f)
            val padV = dp(4f)
            setPadding(padH, padV, padH, padV)
            background = SketchDrawable(
                seed = 888L,
                fill = Palette.CARD,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = 2f,
                cornerDp = 14f,
                variant = 0
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(24f), dp(12f), dp(24f), 0)
            }

            val input = EditText(context).apply {
                hint = "Cari catatan..."
                textSize = 18f
                typeface = Fonts.ui(context)
                setTextColor(Palette.INK)
                setHintTextColor(Palette.INK_SOFT)
                background = null
                minimumHeight = dp(44f)
                setPadding(0, 0, dp(8f), 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        currentSearchQuery = s?.toString()?.trim() ?: ""
                        applyFilter()
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })
            }
            searchInput = input
            addView(input)

            val clearBtn = TextView(context).apply {
                text = "×"
                textSize = 28f
                typeface = Fonts.hand(context, true)
                setTextColor(Palette.INK_SOFT)
                gravity = Gravity.CENTER
                minimumWidth = dp(44f)
                minimumHeight = dp(44f)
                isClickable = true
                isFocusable = true
                contentDescription = "Hapus teks pencarian atau tutup"
                setOnClickListener {
                    if (searchInput.text.isNotEmpty()) {
                        searchInput.setText("")
                    } else {
                        toggleSearch()
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            addView(clearBtn)
        }
        contentLayout.addView(searchContainer)

        val chipsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24f), dp(22f), dp(24f), dp(6f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        chipAll = createChipView("Semua", FilterTab.ALL, 0)
        chipInfinite = createChipView("Infinite", FilterTab.INFINITE, 1)
        chipPaged = createChipView("Halaman", FilterTab.PAGED, 2)

        chipsLayout.addView(chipAll)
        chipsLayout.addView(chipInfinite)
        chipsLayout.addView(chipPaged)
        contentLayout.addView(chipsLayout)

        val gridContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val screenWidthDp = resources.configuration.screenWidthDp
        val numCols = when {
            screenWidthDp >= 900 -> 4
            screenWidthDp >= 600 -> 3
            else -> 2
        }

        gridView = GridView(this).apply {
            numColumns = numCols
            horizontalSpacing = dp(16f)
            verticalSpacing = dp(20f)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(24f), dp(16f), dp(24f), dp(100f))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        adapter = NotebookAdapter()
        gridView.adapter = adapter
        gridView.setOnItemClickListener { _, _, position, _ ->
            val meta = displayedNotebooks.getOrNull(position) ?: return@setOnItemClickListener
            openEditor(meta.id)
        }
        gridView.setOnItemLongClickListener { _, _, position, _ ->
            val meta = displayedNotebooks.getOrNull(position) ?: return@setOnItemLongClickListener false
            showItemOptionsDialog(meta)
            true
        }
        gridContainer.addView(gridView)

        emptyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            val pad = dp(24f)
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            val sketchIcon = IconView(context).apply {
                icon = Icon.PEN
                tint = Palette.INK_SOFT
                strokeWidthDp = 2f
                isClickable = false
                isFocusable = false
                layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f)).apply {
                    bottomMargin = dp(12f)
                }
            }
            addView(sketchIcon)

            emptyText = TextView(context).apply {
                textSize = 19f
                typeface = Fonts.ui(context)
                setTextColor(Palette.INK_SOFT)
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            addView(emptyText)

            emptyActionBtn = TextView(context).apply {
                textSize = 17f
                typeface = Fonts.ui(context)
                setTextColor(Palette.INK)
                gravity = Gravity.CENTER
                minimumHeight = dp(44f)
                val hPad = dp(18f)
                setPadding(hPad, 0, hPad, 0)
                background = SketchDrawable(
                    seed = 991L,
                    fill = Palette.CARD,
                    stroke = Palette.INK,
                    strokeWidthDp = 2.5f,
                    shadowOffsetDp = 2f,
                    cornerDp = 14f,
                    variant = 0
                )
                isClickable = true
                isFocusable = true
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(16f)
                }
            }
            addView(emptyActionBtn)
        }
        gridContainer.addView(emptyContainer)

        contentLayout.addView(gridContainer)
        root.addView(contentLayout)

        val fab = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60f)
            setPadding(dp(16f), 0, dp(24f), 0)
            background = SketchDrawable(
                seed = 999L,
                fill = Palette.RED,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = 3.5f,
                shadowColor = Palette.INK,
                cornerDp = 24f,
                variant = 6
            )
            contentDescription = "Buat notebook baru"
            isFocusable = true
            isClickable = true

            val addIcon = IconView(context).apply {
                isClickable = false
                isFocusable = false
                icon = Icon.ADD
                tint = Palette.CARD
                strokeWidthDp = 2.4f
                layoutParams = LinearLayout.LayoutParams(dp(26f), dp(26f)).apply {
                    rightMargin = dp(10f)
                }
            }
            val addLabel = TextView(context).apply {
                text = "Baru"
                textSize = 22f
                typeface = Fonts.ui(context)
                setTextColor(Palette.CARD)
                includeFontPadding = false
            }
            addView(addIcon)
            addView(addLabel)

            setOnClickListener {
                showNewNotebookDialog()
            }

            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = dp(24f)
                bottomMargin = dp(32f)
            }
        }
        root.addView(fab)

        updateChipStyles()
        return root
    }

    private fun toggleSearch() {
        isSearchVisible = !isSearchVisible
        if (isSearchVisible) {
            searchContainer.visibility = View.VISIBLE
            searchInput.requestFocus()
        } else {
            searchContainer.visibility = View.GONE
            searchInput.setText("")
            currentSearchQuery = ""
            applyFilter()
        }
    }

    private fun createChipView(label: String, tab: FilterTab, variantIndex: Int): TextView {
        return TextView(this).apply {
            text = label
            textSize = 19f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            minimumHeight = dp(44f)
            minimumWidth = dp(44f)
            val hPad = dp(18f)
            val vPad = dp(4f)
            setPadding(hPad, vPad, hPad, vPad)
            contentDescription = "Filter $label"
            isClickable = true
            isFocusable = true
            tag = variantIndex
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = dp(10f)
            }
            setOnClickListener {
                if (currentFilter != tab) {
                    currentFilter = tab
                    updateChipStyles()
                    applyFilter()
                }
            }
        }
    }

    private fun updateChipStyles() {
        fun styleChip(chip: TextView, isSelected: Boolean) {
            chip.isSelected = isSelected
            val variant = (chip.tag as? Int) ?: 0
            chip.setTextColor(Palette.INK)
            chip.background = SketchDrawable(
                seed = 111L + chip.text.hashCode(),
                fill = if (isSelected) Palette.YELLOW else 0,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = 0f,
                cornerDp = 14f,
                variant = variant
            )
        }
        styleChip(chipAll, currentFilter == FilterTab.ALL)
        styleChip(chipInfinite, currentFilter == FilterTab.INFINITE)
        styleChip(chipPaged, currentFilter == FilterTab.PAGED)
    }

    private fun refreshNotebooks() {
        allNotebooks = repo.list()
        applyFilter()
    }

    private fun applyFilter() {
        val tabFiltered = when (currentFilter) {
            FilterTab.ALL -> allNotebooks
            FilterTab.INFINITE -> allNotebooks.filter { it.mode == NoteMode.INFINITE }
            FilterTab.PAGED -> allNotebooks.filter { it.mode == NoteMode.PAGED }
        }
        val searched = if (currentSearchQuery.isEmpty()) {
            tabFiltered
        } else {
            tabFiltered.filter { it.title.contains(currentSearchQuery, ignoreCase = true) }
        }

        displayedNotebooks = when (currentSortOrder) {
            SortOrder.LATEST -> searched.sortedByDescending { it.updatedAt }
            SortOrder.TITLE_AZ -> searched.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        }

        adapter.notifyDataSetChanged()

        if (displayedNotebooks.isEmpty()) {
            emptyContainer.visibility = View.VISIBLE
            if (currentSearchQuery.isNotEmpty()) {
                emptyText.text = "Tidak ada yang cocok dengan '$currentSearchQuery'"
                emptyActionBtn.visibility = View.VISIBLE
                emptyActionBtn.text = "Hapus pencarian"
                emptyActionBtn.contentDescription = "Hapus pencarian"
                emptyActionBtn.setOnClickListener {
                    searchInput.setText("")
                }
            } else if (currentFilter != FilterTab.ALL) {
                val modeName = if (currentFilter == FilterTab.INFINITE) "Infinite" else "Halaman"
                emptyText.text = "Belum ada notebook $modeName"
                emptyActionBtn.visibility = View.GONE
                emptyActionBtn.setOnClickListener(null)
            } else {
                emptyText.text = "Belum ada notebook.\nKetuk tombol Baru ↘ untuk membuat catatan."
                emptyActionBtn.visibility = View.GONE
                emptyActionBtn.setOnClickListener(null)
            }
        } else {
            emptyContainer.visibility = View.GONE
        }
    }

    private fun openEditor(id: String) {
        val intent = Intent(this, EditorActivity::class.java).apply {
            putExtra(EditorActivity.EXTRA_ID, id)
        }
        startActivity(intent)
    }

    private fun showNewNotebookDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val savedModeStr = prefs.getString("last_canvas_mode", NoteMode.INFINITE.name)
        var selectedMode = try {
            NoteMode.valueOf(savedModeStr ?: NoteMode.INFINITE.name)
        } catch (t: Throwable) {
            NoteMode.INFINITE
        }

        val sheetContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padH = dp(24f)
            val padT = dp(14f)
            val padB = dp(32f)
            setPadding(padH, padT, padH, padB)
            background = SheetBackgroundDrawable(Palette.PAPER, Palette.INK)
        }

        val handleView = WavyHandleView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(64f),
                dp(10f)
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(10f)
            }
        }
        sheetContent.addView(handleView)

        val titleView = TextView(this).apply {
            text = "Notebook baru"
            textSize = 42f
            typeface = Fonts.hand(context, true)
            setTextColor(Palette.INK)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14f)
            }
        }
        sheetContent.addView(titleView)

        val labelJudul = TextView(this).apply {
            text = "Judul"
            textSize = 17f
            typeface = Fonts.ui(context)
            setTextColor(Palette.INK_SOFT)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6f)
            }
        }
        sheetContent.addView(labelJudul)

        val titleInput = EditText(this).apply {
            setText("")
            hint = "Judul (opsional)"
            textSize = 22f
            typeface = Fonts.ui(context)
            setTextColor(Palette.INK)
            setHintTextColor(Palette.INK_SOFT)
            minimumHeight = dp(54f)
            val padH = dp(16f)
            val padV = dp(10f)
            setPadding(padH, padV, padH, padV)
            background = SketchDrawable(
                seed = 101L,
                fill = Palette.CARD,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = 0f,
                cornerDp = 12f,
                variant = 0
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        sheetContent.addView(titleInput)

        val labelJenis = TextView(this).apply {
            text = "Jenis kanvas"
            textSize = 17f
            typeface = Fonts.ui(context)
            setTextColor(Palette.INK_SOFT)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(20f)
                bottomMargin = dp(8f)
            }
        }
        sheetContent.addView(labelJenis)

        val cardsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        lateinit var cardInfinite: LinearLayout
        lateinit var cardPaged: LinearLayout

        fun updateModeSelection() {
            val isInf = (selectedMode == NoteMode.INFINITE)
            cardInfinite.isSelected = isInf
            cardPaged.isSelected = !isInf
            cardInfinite.background = SketchDrawable(
                seed = 301L,
                fill = if (isInf) Palette.YELLOW else Palette.CARD,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = if (isInf) 3.5f else 0f,
                shadowColor = Palette.INK,
                cornerDp = 14f,
                variant = 1
            )
            cardPaged.background = SketchDrawable(
                seed = 302L,
                fill = if (!isInf) Palette.YELLOW else Palette.CARD,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = if (!isInf) 3.5f else 0f,
                shadowColor = Palette.INK,
                cornerDp = 14f,
                variant = 2
            )
        }

        cardInfinite = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            minimumHeight = dp(150f)
            val padCard = dp(14f)
            setPadding(padCard, padCard, padCard, padCard)
            contentDescription = "Tak terbatas: Geser dan zoom bebas, cocok untuk brainstorm"
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(7f)
            }

            val iconInfinite = IconView(context).apply {
                isClickable = false
                isFocusable = false
                icon = Icon.INFINITE
                tint = Palette.INK
                strokeWidthDp = 2f
                layoutParams = LinearLayout.LayoutParams(dp(36f), dp(36f)).apply {
                    bottomMargin = dp(6f)
                }
            }
            val titleInfinite = TextView(context).apply {
                text = "Tak terbatas"
                textSize = 22f
                typeface = Fonts.ui(context)
                setTextColor(Palette.INK)
                includeFontPadding = false
            }
            val descInfinite = TextView(context).apply {
                text = "Geser dan zoom bebas, cocok untuk brainstorm"
                textSize = 16f
                typeface = Fonts.ui(context)
                setTextColor(Palette.INK)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(4f)
                }
            }
            addView(iconInfinite)
            addView(titleInfinite)
            addView(descInfinite)

            setOnClickListener {
                selectedMode = NoteMode.INFINITE
                updateModeSelection()
            }
        }

        cardPaged = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            minimumHeight = dp(150f)
            val padCard = dp(14f)
            setPadding(padCard, padCard, padCard, padCard)
            contentDescription = "Per halaman: Halaman A4 yang ditambah satu per satu"
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(7f)
            }

            val iconPage = IconView(context).apply {
                isClickable = false
                isFocusable = false
                icon = Icon.PAGE
                tint = Palette.INK
                strokeWidthDp = 2f
                layoutParams = LinearLayout.LayoutParams(dp(36f), dp(36f)).apply {
                    bottomMargin = dp(6f)
                }
            }
            val titlePage = TextView(context).apply {
                text = "Per halaman"
                textSize = 22f
                typeface = Fonts.ui(context)
                setTextColor(Palette.INK)
                includeFontPadding = false
            }
            val descPage = TextView(context).apply {
                text = "Halaman A4 yang ditambah satu per satu"
                textSize = 16f
                typeface = Fonts.ui(context)
                setTextColor(Palette.INK)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(4f)
                }
            }
            addView(iconPage)
            addView(titlePage)
            addView(descPage)

            setOnClickListener {
                selectedMode = NoteMode.PAGED
                updateModeSelection()
            }
        }

        cardsRow.addView(cardInfinite)
        cardsRow.addView(cardPaged)
        sheetContent.addView(cardsRow)

        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(24f)
            }
        }

        val btnCancel = TextView(this).apply {
            text = "Batal"
            textSize = 22f
            typeface = Fonts.ui(context)
            setTextColor(Palette.INK)
            gravity = Gravity.CENTER
            minimumHeight = dp(56f)
            background = SketchDrawable(
                seed = 401L,
                fill = 0,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = 0f,
                cornerDp = 16f,
                variant = 3
            )
            contentDescription = "Batal buat notebook"
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(7f)
            }
            setOnClickListener {
                dialog.dismiss()
            }
        }

        val btnCreate = TextView(this).apply {
            text = "Buat notebook"
            textSize = 22f
            typeface = Fonts.ui(context)
            setTextColor(Palette.CARD)
            gravity = Gravity.CENTER
            minimumHeight = dp(56f)
            background = SketchDrawable(
                seed = 402L,
                fill = Palette.RED,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = 3.5f,
                shadowColor = Palette.INK,
                cornerDp = 16f,
                variant = 4
            )
            contentDescription = "Buat notebook baru"
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f).apply {
                leftMargin = dp(7f)
            }
            setOnClickListener {
                val inputTitle = titleInput.text.toString().trim()
                prefs.edit().putString("last_canvas_mode", selectedMode.name).apply()
                val created = repo.create(inputTitle, selectedMode)
                dialog.dismiss()
                openEditor(created.id)
            }
        }

        buttonsRow.addView(btnCancel)
        buttonsRow.addView(btnCreate)
        sheetContent.addView(buttonsRow)

        val maxSheetWidthPx = dp(560f)
        val container = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                width = FrameLayout.LayoutParams.MATCH_PARENT
            }
            sheetContent.layoutParams = lp
            addView(sheetContent)
        }

        sheetContent.post {
            val actualW = sheetContent.width
            if (actualW > maxSheetWidthPx) {
                val p = sheetContent.layoutParams as FrameLayout.LayoutParams
                p.width = maxSheetWidthPx
                p.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                sheetContent.layoutParams = p
            }
        }

        dialog.setContentView(container)

        val window = dialog.window
        if (window != null) {
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            window.setGravity(Gravity.BOTTOM)
            window.setBackgroundDrawable(ColorDrawable(0))
            window.setDimAmount(0.5f)
        }

        updateModeSelection()
        dialog.show()
    }

    private fun showItemOptionsDialog(meta: NotebookMeta) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SketchDrawable(
                seed = 601L,
                fill = Palette.CARD,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = 3.5f,
                cornerDp = 16f,
                variant = 0
            )
            val pad = dp(20f)
            setPadding(pad, pad, pad, pad)
        }

        val titleV = TextView(this).apply {
            text = meta.title
            setTextColor(Palette.INK)
            textSize = 24f
            typeface = Fonts.hand(context, bold = true)
            maxLines = 2
            includeFontPadding = false
        }
        dialogView.addView(titleV, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(16f)
        })

        val renameBtn = TextView(this).apply {
            text = "Ganti nama"
            setTextColor(Palette.INK)
            textSize = 19f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            minimumHeight = dp(48f)
            background = SketchDrawable(
                seed = 602L,
                fill = Palette.YELLOW,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = 2f,
                cornerDp = 14f,
                variant = 1
            )
            val padH = dp(16f)
            setPadding(padH, 0, padH, 0)
            isClickable = true
            isFocusable = true
            contentDescription = "Ganti nama notebook"
            setOnClickListener {
                dialog.dismiss()
                showRenameDialog(meta)
            }
        }
        dialogView.addView(renameBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10f)
        })

        val deleteBtn = TextView(this).apply {
            text = "Hapus"
            setTextColor(Palette.CARD)
            textSize = 19f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            minimumHeight = dp(48f)
            background = SketchDrawable(
                seed = 603L,
                fill = Palette.RED,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = 2f,
                cornerDp = 14f,
                variant = 2
            )
            val padH = dp(16f)
            setPadding(padH, 0, padH, 0)
            isClickable = true
            isFocusable = true
            contentDescription = "Hapus notebook"
            setOnClickListener {
                dialog.dismiss()
                performDeleteNotebook(meta)
            }
        }
        dialogView.addView(deleteBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10f)
        })

        val cancelBtn = TextView(this).apply {
            text = "Batal"
            setTextColor(Palette.INK)
            textSize = 19f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            minimumHeight = dp(44f)
            background = SketchDrawable(
                seed = 604L,
                fill = Palette.CARD,
                stroke = Palette.INK_SOFT,
                strokeWidthDp = 2f,
                shadowOffsetDp = 0f,
                cornerDp = 14f,
                variant = 3
            )
            val padH = dp(16f)
            setPadding(padH, 0, padH, 0)
            isClickable = true
            isFocusable = true
            contentDescription = "Batal"
            setOnClickListener {
                dialog.dismiss()
            }
        }
        dialogView.addView(cancelBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val maxDialogW = dp(380f)
        val container = FrameLayout(this).apply {
            val pad = dp(24f)
            setPadding(pad, pad, pad, pad)
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            dialogView.layoutParams = lp
            addView(dialogView)
        }

        dialogView.post {
            if (dialogView.width > maxDialogW) {
                val p = dialogView.layoutParams as FrameLayout.LayoutParams
                p.width = maxDialogW
                dialogView.layoutParams = p
            }
        }

        dialog.setContentView(container)
        dialog.window?.let { w ->
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            w.setBackgroundDrawable(ColorDrawable(0))
            w.setDimAmount(0.5f)
        }
        dialog.show()
    }

    private fun performDeleteNotebook(meta: NotebookMeta) {
        repo.softDelete(meta.id)
        updateTrashBadge()
        refreshNotebooks()
        UndoBar.show(
            parent = rootLayout,
            message = "Notebook dihapus",
            onUndo = {
                repo.restore(meta.id)
                updateTrashBadge()
                refreshNotebooks()
            },
            onExpire = {
                updateTrashBadge()
            }
        )
    }

    private fun showRenameDialog(meta: NotebookMeta) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SketchDrawable(
                seed = 502L,
                fill = Palette.CARD,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = 3.5f,
                cornerDp = 16f,
                variant = 0
            )
            val pad = dp(20f)
            setPadding(pad, pad, pad, pad)
        }

        val titleV = TextView(this).apply {
            text = "Ganti nama"
            setTextColor(Palette.INK)
            textSize = 24f
            typeface = Fonts.hand(context, bold = true)
            includeFontPadding = false
        }
        dialogView.addView(titleV, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14f)
        })

        val input = EditText(this).apply {
            setText(meta.title)
            setSelection(meta.title.length)
            textSize = 20f
            typeface = Fonts.ui(context)
            setTextColor(Palette.INK)
            minimumHeight = dp(48f)
            val pad = dp(12f)
            setPadding(pad, pad, pad, pad)
            background = SketchDrawable(
                seed = 501L,
                fill = Palette.CARD,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = 2f,
                cornerDp = 12f,
                variant = 1
            )
        }
        dialogView.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(20f)
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val cancelBtn = TextView(this).apply {
            text = "Batal"
            setTextColor(Palette.INK)
            textSize = 18f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            minimumHeight = dp(44f)
            background = SketchDrawable(
                seed = 503L,
                fill = Palette.CARD,
                stroke = Palette.INK_SOFT,
                strokeWidthDp = 2f,
                shadowOffsetDp = 0f,
                cornerDp = 14f,
                variant = 2
            )
            val padH = dp(16f)
            setPadding(padH, 0, padH, 0)
            isClickable = true
            isFocusable = true
            contentDescription = "Batal ganti nama"
            setOnClickListener {
                dialog.dismiss()
            }
        }
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(10f)
        })

        val saveBtn = TextView(this).apply {
            text = "Simpan"
            setTextColor(Palette.CARD)
            textSize = 18f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            minimumHeight = dp(44f)
            background = SketchDrawable(
                seed = 504L,
                fill = Palette.RED,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = 2.5f,
                shadowColor = Palette.INK,
                cornerDp = 14f,
                variant = 3
            )
            val padH = dp(20f)
            setPadding(padH, 0, padH, 0)
            isClickable = true
            isFocusable = true
            contentDescription = "Simpan nama baru"
            setOnClickListener {
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != meta.title) {
                    repo.rename(meta.id, newName)
                    refreshNotebooks()
                }
                dialog.dismiss()
            }
        }
        btnRow.addView(saveBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialogView.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val maxDialogW = dp(380f)
        val container = FrameLayout(this).apply {
            val pad = dp(24f)
            setPadding(pad, pad, pad, pad)
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            dialogView.layoutParams = lp
            addView(dialogView)
        }

        dialogView.post {
            if (dialogView.width > maxDialogW) {
                val p = dialogView.layoutParams as FrameLayout.LayoutParams
                p.width = maxDialogW
                dialogView.layoutParams = p
            }
        }

        dialog.setContentView(container)
        dialog.window?.let { w ->
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            w.setBackgroundDrawable(ColorDrawable(0))
            w.setDimAmount(0.5f)
        }
        dialog.show()
    }

    private fun loadThumbnailAsync(id: String, imageView: ImageView) {
        val cached = thumbMemoryCache.get(id)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        imageView.setImageBitmap(null)
        imageView.tag = id

        thumbExecutor.execute {
            val file: File = repo.thumbFile(id)
            if (!file.exists() || file.length() == 0L) {
                return@execute
            }

            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)

            var sampleSize = 1
            val targetSize = dp(200f)
            while (options.outWidth / (sampleSize * 2) >= targetSize &&
                options.outHeight / (sampleSize * 2) >= targetSize
            ) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return@execute

            mainHandler.post {
                thumbMemoryCache.put(id, bitmap)
                if (imageView.tag == id) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    private inner class NotebookAdapter : BaseAdapter() {

        private val dateFormat = SimpleDateFormat("d MMM", Locale("id", "ID"))

        override fun getCount(): Int = displayedNotebooks.size

        override fun getItem(position: Int): Any = displayedNotebooks[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val meta = displayedNotebooks[position]
            val cardView: LinearLayout
            val thumbContainer: FrameLayout
            val thumbView: ImageView
            val titleView: TextView
            val metaIcon: IconView
            val metaText: TextView
            val menuBtn: FrameLayout

            if (convertView == null) {
                cardView = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    contentDescription = "Notebook ${meta.title}"
                }

                thumbContainer = FrameLayout(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(132f)
                    )
                }

                thumbView = ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                thumbContainer.addView(thumbView)

                val menuContainer = FrameLayout(this@MainActivity).apply {
                    val s44 = dp(44f)
                    layoutParams = FrameLayout.LayoutParams(s44, s44).apply {
                        gravity = Gravity.TOP or Gravity.END
                        topMargin = dp(2f)
                        rightMargin = dp(2f)
                    }
                    background = SketchDrawable(
                        seed = 801L,
                        fill = Palette.CARD,
                        stroke = Palette.INK,
                        strokeWidthDp = 1.8f,
                        shadowOffsetDp = 1.5f,
                        cornerDp = 12f,
                        round = true
                    )
                    isClickable = true
                    isFocusable = false
                    contentDescription = "Opsi notebook"

                    val dots = ThreeDotsView(this@MainActivity).apply {
                        layoutParams = FrameLayout.LayoutParams(dp(20f), dp(20f)).apply {
                            gravity = Gravity.CENTER
                        }
                    }
                    addView(dots)
                }
                thumbContainer.addView(menuContainer)
                menuBtn = menuContainer
                cardView.addView(thumbContainer)

                val textPad = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(4f), dp(8f), dp(4f), 0)
                }

                titleView = TextView(this@MainActivity).apply {
                    textSize = 20f
                    typeface = Fonts.ui(context)
                    setTextColor(Palette.INK)
                    maxLines = 1
                    includeFontPadding = false
                }
                textPad.addView(titleView)

                val metaRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(2f)
                    }
                }

                metaIcon = IconView(this@MainActivity).apply {
                    strokeWidthDp = 2.2f
                    tint = Palette.INK_SOFT
                    isClickable = false
                    isFocusable = false
                    layoutParams = LinearLayout.LayoutParams(dp(18f), dp(18f)).apply {
                        rightMargin = dp(6f)
                    }
                }
                metaRow.addView(metaIcon)

                metaText = TextView(this@MainActivity).apply {
                    textSize = 15f
                    typeface = Fonts.ui(context)
                    setTextColor(Palette.INK_SOFT)
                    maxLines = 1
                    includeFontPadding = false
                }
                metaRow.addView(metaText)

                textPad.addView(metaRow)
                cardView.addView(textPad)

                cardView.tag = CardViewHolder(thumbContainer, thumbView, titleView, metaIcon, metaText, menuBtn)
            } else {
                cardView = convertView as LinearLayout
                val holder = cardView.tag as CardViewHolder
                thumbContainer = holder.thumbContainer
                thumbView = holder.thumbView
                titleView = holder.titleView
                metaIcon = holder.metaIcon
                metaText = holder.metaText
                menuBtn = holder.menuBtn
            }

            menuBtn.setOnClickListener {
                showItemOptionsDialog(meta)
            }

            val cardSeed = meta.id.hashCode().toLong()
            val variantIdx = (position % 8).coerceAtLeast(0)

            if (meta.mode == NoteMode.INFINITE) {
                thumbContainer.background = DotGridDrawable(
                    spacingDp = 14f,
                    dotColor = Palette.DOT_CARD,
                    bgColor = Palette.CARD,
                    sketchSeed = cardSeed,
                    sketchStroke = Palette.INK,
                    sketchStrokeDp = 2.5f,
                    sketchCornerDp = 14f,
                    sketchShadowDp = 3f,
                    sketchShadowColor = Palette.SHADOW_SOFT,
                    sketchVariant = variantIdx
                )
            } else {
                thumbContainer.background = SketchDrawable(
                    seed = cardSeed,
                    fill = Palette.KRAFT,
                    stroke = Palette.INK,
                    strokeWidthDp = 2.5f,
                    shadowOffsetDp = 3f,
                    shadowColor = Palette.SHADOW_SOFT,
                    cornerDp = 14f,
                    variant = variantIdx
                )
            }

            val rotations = floatArrayOf(-1f, 0.8f, 0.6f, -0.7f, -0.5f, 0.9f)
            cardView.rotation = rotations[position % rotations.size]

            titleView.text = meta.title
            val dateStr = dateFormat.format(Date(meta.updatedAt))
            if (meta.mode == NoteMode.INFINITE) {
                metaIcon.icon = Icon.INFINITE
                metaText.text = "Tak terbatas · $dateStr"
            } else {
                metaIcon.icon = Icon.PAGE
                metaText.text = "${meta.pageCount} halaman · $dateStr"
            }
            cardView.contentDescription = "${meta.title}, ${metaText.text}"

            loadThumbnailAsync(meta.id, thumbView)

            return cardView
        }
    }

    private class CardViewHolder(
        val thumbContainer: FrameLayout,
        val thumbView: ImageView,
        val titleView: TextView,
        val metaIcon: IconView,
        val metaText: TextView,
        val menuBtn: FrameLayout
    )

    private class ThreeDotsView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.INK
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val r = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.8f, resources.displayMetrics)
            val cx = w / 2f
            canvas.drawCircle(cx, h * 0.25f, r, paint)
            canvas.drawCircle(cx, h * 0.50f, r, paint)
            canvas.drawCircle(cx, h * 0.75f, r, paint)
        }
    }

    private class WavyHeaderView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.RED
            style = Paint.Style.STROKE
            strokeWidth = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                3f,
                context.resources.displayMetrics
            )
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            path.reset()
            fun sx(x: Float): Float = x / 156f * w
            fun sy(y: Float): Float = y / 14f * h

            path.moveTo(sx(2f), sy(8f))
            path.cubicTo(sx(14f), sy(2f), sx(22f), sy(13f), sx(36f), sy(7f))
            path.cubicTo(sx(50f), sy(1f), sx(58f), sy(12f), sx(72f), sy(7f))
            path.cubicTo(sx(86f), sy(2f), sx(94f), sy(12f), sx(108f), sy(7f))
            path.cubicTo(sx(120f), sy(3f), sx(134f), sy(11f), sx(154f), sy(6f))

            canvas.drawPath(path, paint)
        }
    }

    private class WavyHandleView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.INK
            style = Paint.Style.STROKE
            strokeWidth = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                3f,
                context.resources.displayMetrics
            )
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            path.reset()
            fun sx(x: Float): Float = x / 64f * w
            fun sy(y: Float): Float = y / 10f * h

            path.moveTo(sx(3f), sy(5f))
            path.cubicTo(sx(12f), sy(1f), sx(20f), sy(9f), sx(30f), sy(5f))
            path.cubicTo(sx(40f), sy(1f), sx(50f), sy(9f), sx(61f), sy(4f))

            canvas.drawPath(path, paint)
        }
    }

    private class SheetBackgroundDrawable(
        private val fillColor: Int,
        private val strokeColor: Int
    ) : Drawable() {
        private val density = Resources.getSystem().displayMetrics.density
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = strokeColor
            strokeWidth = 2.5f * density
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillColor
        }
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x2E2B2724
        }
        private val path = Path()
        private val shadowPath = Path()

        override fun onBoundsChange(bounds: android.graphics.Rect) {
            super.onBoundsChange(bounds)
            buildPaths(bounds)
        }

        private fun buildPaths(bounds: android.graphics.Rect) {
            val l = bounds.left.toFloat()
            val t = bounds.top.toFloat() + 4f * density
            val r = bounds.right.toFloat()
            val b = bounds.bottom.toFloat()

            path.reset()
            val rTlX = 28f * density
            val rTlY = 16f * density
            val rTrX = 14f * density
            val rTrY = 26f * density

            path.moveTo(l, b)
            path.lineTo(l, t + rTlY)
            path.cubicTo(l, t + rTlY * 0.45f, l + rTlX * 0.45f, t, l + rTlX, t)
            path.lineTo(r - rTrX, t)
            path.cubicTo(r - rTrX * 0.45f, t, r, t + rTrY * 0.45f, r, t + rTrY)
            path.lineTo(r, b)
            path.close()

            shadowPath.reset()
            val st = t - 4f * density
            shadowPath.moveTo(l, b)
            shadowPath.lineTo(l, st + rTlY)
            shadowPath.cubicTo(l, st + rTlY * 0.45f, l + rTlX * 0.45f, st, l + rTlX, st)
            shadowPath.lineTo(r - rTrX, st)
            shadowPath.cubicTo(r - rTrX * 0.45f, st, r, st + rTrY * 0.45f, r, st + rTrY)
            shadowPath.lineTo(r, b)
            shadowPath.close()
        }

        override fun draw(canvas: Canvas) {
            canvas.drawPath(shadowPath, shadowPaint)
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, strokePaint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private class DotGridDrawable(
        private val spacingDp: Float,
        private val dotColor: Int,
        private val bgColor: Int,
        private val sketchSeed: Long = 0L,
        private val sketchStroke: Int = 0,
        private val sketchStrokeDp: Float = 0f,
        private val sketchCornerDp: Float = 0f,
        private val sketchShadowDp: Float = 0f,
        private val sketchShadowColor: Int = 0,
        private val sketchVariant: Int = -1
    ) : Drawable() {
        private val density = Resources.getSystem().displayMetrics.density
        private val dotSpacing = spacingDp * density
        private val dotRadius = 1.1f * density
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = dotColor
            style = Paint.Style.FILL
        }
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        private val sketchDrawable: SketchDrawable? = if (sketchStrokeDp > 0f) {
            SketchDrawable(
                seed = sketchSeed,
                fill = bgColor,
                stroke = sketchStroke,
                strokeWidthDp = sketchStrokeDp,
                shadowOffsetDp = sketchShadowDp,
                shadowColor = sketchShadowColor,
                cornerDp = sketchCornerDp,
                variant = sketchVariant
            )
        } else null

        private val clipPath = Path()

        override fun onBoundsChange(bounds: android.graphics.Rect) {
            super.onBoundsChange(bounds)
            sketchDrawable?.bounds = bounds
            clipPath.reset()
            val r = bounds.right.toFloat()
            val b = bounds.bottom.toFloat()
            val c = sketchCornerDp * density
            clipPath.addRoundRect(0f, 0f, r, b, c, c, Path.Direction.CW)
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0f || h <= 0f) return

            if (sketchDrawable != null) {
                sketchDrawable.draw(canvas)
                val count = canvas.save()
                canvas.clipPath(clipPath)
                drawDots(canvas, w, h)
                canvas.restoreToCount(count)
            } else {
                canvas.drawRect(b, bgPaint)
                drawDots(canvas, w, h)
            }
        }

        private fun drawDots(canvas: Canvas, w: Float, h: Float) {
            var y = dotSpacing / 2f
            while (y < h) {
                var x = dotSpacing / 2f
                while (x < w) {
                    canvas.drawCircle(x, y, dotRadius, dotPaint)
                    x += dotSpacing
                }
                y += dotSpacing
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }
}
