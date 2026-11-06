package com.example.tulis

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.tulis.model.NoteMode
import com.example.tulis.storage.NotebookRepository
import com.example.tulis.storage.TrashedNotebook
import com.example.tulis.ui.Fonts
import com.example.tulis.ui.Icon
import com.example.tulis.ui.IconView
import com.example.tulis.ui.Palette
import com.example.tulis.ui.SketchDrawable
import com.example.tulis.ui.UiPrefs
import com.example.tulis.ui.UndoBar
import java.io.File
import java.util.concurrent.Executors

class TrashActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiPrefs.wrap(newBase))
    }

    private lateinit var repo: NotebookRepository
    private val mainHandler = Handler(Looper.getMainLooper())
    private val thumbExecutor = Executors.newFixedThreadPool(2)
    private val thumbMemoryCache = object : LruCache<String, Bitmap>(32) {}

    private var trashedNotebooks: List<TrashedNotebook> = emptyList()

    private lateinit var rootLayout: FrameLayout
    private lateinit var gridView: GridView
    private lateinit var adapter: TrashAdapter
    private lateinit var emptyContainer: LinearLayout
    private lateinit var btnEmptyTrash: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = NotebookRepository(this)
        repo.purgeExpired(30)
        setContentView(buildContentView())
    }

    override fun onResume() {
        super.onResume()
        refreshTrash()
    }

    override fun onDestroy() {
        super.onDestroy()
        thumbExecutor.shutdown()
        thumbMemoryCache.evictAll()
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
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24f), dp(40f), dp(24f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val backBtn = FrameLayout(this).apply {
            val s48 = dp(48f)
            layoutParams = LinearLayout.LayoutParams(s48, s48).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = dp(16f)
            }
            background = SketchDrawable(
                seed = 701L,
                fill = Palette.CARD,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = 2.5f,
                cornerDp = 24f,
                round = true
            )
            isClickable = true
            isFocusable = true
            contentDescription = "Kembali ke beranda"

            val backIcon = IconView(context).apply {
                icon = Icon.BACK
                tint = Palette.INK
                strokeWidthDp = 2.2f
                isClickable = false
                isFocusable = false
                layoutParams = FrameLayout.LayoutParams(dp(24f), dp(24f)).apply {
                    gravity = Gravity.CENTER
                }
            }
            addView(backIcon)

            setOnClickListener {
                finish()
            }
        }
        headerLayout.addView(backBtn)

        val titleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val titleView = TextView(this).apply {
            text = "Baru dihapus"
            textSize = 42f
            typeface = Fonts.hand(context, true)
            setTextColor(Palette.INK)
            includeFontPadding = false
        }

        val subtitleView = TextView(this).apply {
            text = "Notebook dihapus otomatis setelah 30 hari"
            textSize = 16f
            typeface = Fonts.ui(context)
            setTextColor(Palette.INK_SOFT)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2f)
            }
        }

        titleContainer.addView(titleView)
        titleContainer.addView(subtitleView)
        headerLayout.addView(titleContainer)

        btnEmptyTrash = TextView(this).apply {
            text = "Kosongkan"
            textSize = 19f
            typeface = Fonts.ui(context)
            setTextColor(Palette.RED)
            gravity = Gravity.CENTER
            minimumHeight = dp(44f)
            minimumWidth = dp(44f)
            val hPad = dp(18f)
            val vPad = dp(6f)
            setPadding(hPad, vPad, hPad, vPad)
            background = SketchDrawable(
                seed = 702L,
                fill = Palette.CARD,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = 2f,
                cornerDp = 18f,
                variant = 1
            )
            contentDescription = "Kosongkan semua notebook yang dihapus"
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(12f)
            }
            setOnClickListener {
                showEmptyTrashConfirmDialog()
            }
        }
        headerLayout.addView(btnEmptyTrash)

        contentLayout.addView(headerLayout)

        val gridContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val screenWidthDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
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
            setPadding(dp(24f), dp(16f), dp(24f), dp(40f))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        adapter = TrashAdapter()
        gridView.adapter = adapter
        gridView.setOnItemClickListener { _, _, position, _ ->
            val item = trashedNotebooks.getOrNull(position) ?: return@setOnItemClickListener
            showItemActionDialog(item)
        }
        gridContainer.addView(gridView)

        emptyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            val pad = dp(32f)
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            val sketchIcon = TrashEmptyIllustrationView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(72f), dp(72f)).apply {
                    bottomMargin = dp(16f)
                }
            }
            val emptyTitle = TextView(context).apply {
                text = "Tidak ada notebook yang dihapus"
                textSize = 24f
                typeface = Fonts.hand(context, true)
                setTextColor(Palette.INK)
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            val emptySubtitle = TextView(context).apply {
                text = "Notebook yang dihapus muncul di sini selama 30 hari"
                textSize = 17f
                typeface = Fonts.ui(context)
                setTextColor(Palette.INK_SOFT)
                gravity = Gravity.CENTER
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(6f)
                }
            }

            addView(sketchIcon)
            addView(emptyTitle)
            addView(emptySubtitle)
        }
        gridContainer.addView(emptyContainer)

        contentLayout.addView(gridContainer)
        root.addView(contentLayout)

        return root
    }

    private fun refreshTrash() {
        thumbExecutor.execute {
            val list = repo.listTrash()
            mainHandler.post {
                trashedNotebooks = list
                adapter.notifyDataSetChanged()
                val isEmpty = list.isEmpty()
                emptyContainer.visibility = if (isEmpty) View.VISIBLE else View.GONE
                gridView.visibility = if (isEmpty) View.GONE else View.VISIBLE
                btnEmptyTrash.visibility = if (isEmpty) View.GONE else View.VISIBLE
            }
        }
    }

    private fun showEmptyTrashConfirmDialog() {
        var dialogRef: AlertDialog? = null
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SketchDrawable(
                fill = Palette.CARD,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = 3f,
                cornerDp = 18f,
                variant = 0
            )
            val pad = dp(24f)
            setPadding(pad, pad, pad, pad)
        }

        val titleV = TextView(this).apply {
            text = "Kosongkan sampah"
            setTextColor(Palette.INK)
            textSize = 28f
            typeface = Fonts.hand(context, bold = true)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12f)
            }
        }
        dialogView.addView(titleV)

        val messageV = TextView(this).apply {
            text = "Hapus semua notebook ini selamanya?"
            setTextColor(Palette.INK_SOFT)
            textSize = 18f
            typeface = Fonts.ui(context)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(24f)
            }
        }
        dialogView.addView(messageV)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val cancelBtn = TextView(this).apply {
            text = "Batal"
            setTextColor(Palette.INK)
            textSize = 19f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            minimumHeight = dp(44f)
            minimumWidth = dp(44f)
            background = SketchDrawable(
                fill = Palette.CARD,
                stroke = Palette.INK_SOFT,
                strokeWidthDp = 2f,
                shadowOffsetDp = 0f,
                cornerDp = 14f,
                variant = 2
            )
            val hPad = dp(18f)
            val vPad = dp(6f)
            setPadding(hPad, vPad, hPad, vPad)
            isClickable = true
            isFocusable = true
            contentDescription = "Batal kosongkan sampah"
            setOnClickListener {
                dialogRef?.dismiss()
            }
        }
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = dp(10f)
        })

        val confirmBtn = TextView(this).apply {
            text = "Hapus semua"
            setTextColor(Palette.PAPER)
            textSize = 19f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            minimumHeight = dp(44f)
            minimumWidth = dp(44f)
            background = SketchDrawable(
                fill = Palette.RED,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = 2.5f,
                cornerDp = 14f,
                variant = 3
            )
            val hPad = dp(18f)
            val vPad = dp(6f)
            setPadding(hPad, vPad, hPad, vPad)
            isClickable = true
            isFocusable = true
            contentDescription = "Hapus semua notebook selamanya"
            setOnClickListener {
                dialogRef?.dismiss()
                thumbExecutor.execute {
                    repo.emptyTrash()
                    mainHandler.post {
                        refreshTrash()
                    }
                }
            }
        }
        btnRow.addView(confirmBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        dialogView.addView(btnRow)

        dialogRef = AlertDialog.Builder(this)
            .setView(dialogView)
            .show().apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
            }
    }

    private fun showItemActionDialog(item: TrashedNotebook) {
        var dialogRef: AlertDialog? = null
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SketchDrawable(
                fill = Palette.CARD,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = 3f,
                cornerDp = 18f,
                variant = 4
            )
            val pad = dp(24f)
            setPadding(pad, pad, pad, pad)
        }

        val titleV = TextView(this).apply {
            text = item.meta.title
            setTextColor(Palette.INK)
            textSize = 28f
            typeface = Fonts.hand(context, bold = true)
            maxLines = 2
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(16f)
            }
        }
        dialogView.addView(titleV)

        val btnRestore = TextView(this).apply {
            text = "Pulihkan"
            setTextColor(Palette.INK)
            textSize = 20f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            minimumHeight = dp(48f)
            minimumWidth = dp(44f)
            background = SketchDrawable(
                fill = Palette.YELLOW,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = 2.5f,
                cornerDp = 16f,
                variant = 5
            )
            val hPad = dp(20f)
            val vPad = dp(8f)
            setPadding(hPad, vPad, hPad, vPad)
            isClickable = true
            isFocusable = true
            contentDescription = "Pulihkan ${item.meta.title}"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12f)
            }
            setOnClickListener {
                dialogRef?.dismiss()
                performRestore(item)
            }
        }
        dialogView.addView(btnRestore)

        val btnDeleteForever = TextView(this).apply {
            text = "Hapus permanen"
            setTextColor(Palette.PAPER)
            textSize = 20f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            minimumHeight = dp(48f)
            minimumWidth = dp(44f)
            background = SketchDrawable(
                fill = Palette.RED,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = 2.5f,
                cornerDp = 16f,
                variant = 6
            )
            val hPad = dp(20f)
            val vPad = dp(8f)
            setPadding(hPad, vPad, hPad, vPad)
            isClickable = true
            isFocusable = true
            contentDescription = "Hapus permanen ${item.meta.title}"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                dialogRef?.dismiss()
                showDeleteForeverConfirmDialog(item)
            }
        }
        dialogView.addView(btnDeleteForever)

        dialogRef = AlertDialog.Builder(this)
            .setView(dialogView)
            .show().apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
            }
    }

    private fun performRestore(item: TrashedNotebook) {
        val id = item.meta.id
        repo.restore(id)
        refreshTrash()
        UndoBar.show(
            parent = rootLayout,
            message = "Notebook dipulihkan",
            onUndo = {
                repo.softDelete(id)
                refreshTrash()
            }
        )
    }

    private fun showDeleteForeverConfirmDialog(item: TrashedNotebook) {
        var dialogRef: AlertDialog? = null
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SketchDrawable(
                fill = Palette.CARD,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = 3f,
                cornerDp = 18f,
                variant = 7
            )
            val pad = dp(24f)
            setPadding(pad, pad, pad, pad)
        }

        val titleV = TextView(this).apply {
            text = "Hapus permanen?"
            setTextColor(Palette.INK)
            textSize = 26f
            typeface = Fonts.hand(context, bold = true)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10f)
            }
        }
        dialogView.addView(titleV)

        val messageV = TextView(this).apply {
            text = "\"${item.meta.title}\" akan dihapus selamanya dan tidak dapat dibatalkan."
            setTextColor(Palette.INK_SOFT)
            textSize = 17f
            typeface = Fonts.ui(context)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(20f)
            }
        }
        dialogView.addView(messageV)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val cancelBtn = TextView(this).apply {
            text = "Batal"
            setTextColor(Palette.INK)
            textSize = 18f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            minimumHeight = dp(44f)
            minimumWidth = dp(44f)
            background = SketchDrawable(
                fill = Palette.CARD,
                stroke = Palette.INK_SOFT,
                strokeWidthDp = 2f,
                shadowOffsetDp = 0f,
                cornerDp = 14f,
                variant = 0
            )
            val hPad = dp(16f)
            val vPad = dp(6f)
            setPadding(hPad, vPad, hPad, vPad)
            isClickable = true
            isFocusable = true
            contentDescription = "Batal hapus permanen"
            setOnClickListener {
                dialogRef?.dismiss()
            }
        }
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = dp(10f)
        })

        val confirmBtn = TextView(this).apply {
            text = "Hapus permanen"
            setTextColor(Palette.PAPER)
            textSize = 18f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            minimumHeight = dp(44f)
            minimumWidth = dp(44f)
            background = SketchDrawable(
                fill = Palette.RED,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = 2.5f,
                cornerDp = 14f,
                variant = 1
            )
            val hPad = dp(16f)
            val vPad = dp(6f)
            setPadding(hPad, vPad, hPad, vPad)
            isClickable = true
            isFocusable = true
            contentDescription = "Konfirmasi hapus permanen ${item.meta.title}"
            setOnClickListener {
                dialogRef?.dismiss()
                thumbExecutor.execute {
                    repo.deleteForever(item.meta.id)
                    mainHandler.post {
                        refreshTrash()
                    }
                }
            }
        }
        btnRow.addView(confirmBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        dialogView.addView(btnRow)

        dialogRef = AlertDialog.Builder(this)
            .setView(dialogView)
            .show().apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
            }
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
            val file: File = repo.trashThumbFile(id)
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

    private inner class TrashAdapter : BaseAdapter() {

        override fun getCount(): Int = trashedNotebooks.size

        override fun getItem(position: Int): Any = trashedNotebooks[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = trashedNotebooks[position]
            val cardView: LinearLayout
            val thumbContainer: FrameLayout
            val thumbView: ImageView
            val titleView: TextView
            val deletedAtView: TextView
            val expireView: TextView
            val btnMenu: FrameLayout

            if (convertView == null) {
                cardView = LinearLayout(this@TrashActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }

                thumbContainer = FrameLayout(this@TrashActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(132f)
                    )
                }

                thumbView = ImageView(this@TrashActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                thumbContainer.addView(thumbView)

                btnMenu = FrameLayout(this@TrashActivity).apply {
                    val s44 = dp(44f)
                    layoutParams = FrameLayout.LayoutParams(s44, s44).apply {
                        gravity = Gravity.TOP or Gravity.END
                        marginEnd = dp(4f)
                        topMargin = dp(4f)
                    }
                    background = SketchDrawable(
                        seed = 801L,
                        fill = Palette.CARD,
                        stroke = Palette.INK,
                        strokeWidthDp = 2f,
                        shadowOffsetDp = 1.5f,
                        cornerDp = 22f,
                        round = true
                    )
                    contentDescription = "Opsi notebook"
                    isClickable = true
                    isFocusable = false

                    val dotsIcon = MoreDotsIconView(context).apply {
                        isClickable = false
                        isFocusable = false
                        layoutParams = FrameLayout.LayoutParams(dp(20f), dp(20f)).apply {
                            gravity = Gravity.CENTER
                        }
                    }
                    addView(dotsIcon)
                }
                thumbContainer.addView(btnMenu)

                cardView.addView(thumbContainer)

                val textPad = LinearLayout(this@TrashActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(4f), dp(8f), dp(4f), 0)
                }

                titleView = TextView(this@TrashActivity).apply {
                    textSize = 20f
                    typeface = Fonts.ui(context)
                    setTextColor(Palette.INK)
                    maxLines = 1
                    includeFontPadding = false
                }
                textPad.addView(titleView)

                val infoRow = LinearLayout(this@TrashActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(2f)
                    }
                }

                deletedAtView = TextView(this@TrashActivity).apply {
                    textSize = 15f
                    typeface = Fonts.ui(context)
                    setTextColor(Palette.INK_SOFT)
                    maxLines = 1
                    includeFontPadding = false
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }
                infoRow.addView(deletedAtView)

                expireView = TextView(this@TrashActivity).apply {
                    textSize = 15f
                    typeface = Fonts.ui(context)
                    maxLines = 1
                    includeFontPadding = false
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = dp(6f)
                    }
                }
                infoRow.addView(expireView)

                textPad.addView(infoRow)
                cardView.addView(textPad)

                cardView.tag = TrashCardViewHolder(
                    thumbContainer,
                    thumbView,
                    titleView,
                    deletedAtView,
                    expireView,
                    btnMenu
                )
            } else {
                cardView = convertView as LinearLayout
                val holder = cardView.tag as TrashCardViewHolder
                thumbContainer = holder.thumbContainer
                thumbView = holder.thumbView
                titleView = holder.titleView
                deletedAtView = holder.deletedAtView
                expireView = holder.expireView
                btnMenu = holder.btnMenu
            }

            val cardSeed = item.meta.id.hashCode().toLong()
            val variantIdx = (position % 8).coerceAtLeast(0)

            if (item.meta.mode == NoteMode.INFINITE) {
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

            titleView.text = item.meta.title

            val now = System.currentTimeMillis()
            val diffMs = (now - item.deletedAt).coerceAtLeast(0L)
            val diffDays = (diffMs / (1000L * 60 * 60 * 24)).toInt()

            val deletedText = when {
                diffDays <= 0 -> "Dihapus hari ini"
                diffDays == 1 -> "Dihapus kemarin"
                else -> "Dihapus $diffDays hari lalu"
            }
            deletedAtView.text = deletedText

            val remainingDays = (30 - diffDays).coerceAtLeast(0)
            val expireText = "Hilang dalam $remainingDays hari"
            expireView.text = expireText
            if (remainingDays <= 3) {
                expireView.setTextColor(Palette.RED)
            } else {
                expireView.setTextColor(Palette.INK_SOFT)
            }

            val itemDesc = "${item.meta.title}, $deletedText, $expireText"
            cardView.contentDescription = itemDesc

            btnMenu.contentDescription = "Opsi untuk ${item.meta.title}"
            btnMenu.setOnClickListener {
                showItemActionDialog(item)
            }

            loadThumbnailAsync(item.meta.id, thumbView)

            return cardView
        }
    }

    private class TrashCardViewHolder(
        val thumbContainer: FrameLayout,
        val thumbView: ImageView,
        val titleView: TextView,
        val deletedAtView: TextView,
        val expireView: TextView,
        val btnMenu: FrameLayout
    )

    private class MoreDotsIconView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.INK
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cx = w / 2f
            val r = 2.2f * resources.displayMetrics.density
            val step = 5.5f * resources.displayMetrics.density
            canvas.drawCircle(cx, h / 2f - step, r, paint)
            canvas.drawCircle(cx, h / 2f, r, paint)
            canvas.drawCircle(cx, h / 2f + step, r, paint)
        }
    }

    private class TrashEmptyIllustrationView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.INK_SOFT
            style = Paint.Style.STROKE
            strokeWidth = 2.4f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            fun sx(x: Float): Float = x / 48f * w
            fun sy(y: Float): Float = y / 48f * h

            path.reset()
            path.moveTo(sx(10f), sy(14f))
            path.lineTo(sx(38f), sy(14.4f))

            path.moveTo(sx(19f), sy(14f))
            path.cubicTo(sx(18.8f), sy(11.2f), sx(20.8f), sy(9.2f), sx(24f), sy(9.2f))
            path.cubicTo(sx(27.2f), sy(9.2f), sx(29.2f), sy(11.2f), sx(29f), sy(14f))

            path.moveTo(sx(13.6f), sy(14.8f))
            path.lineTo(sx(15.2f), sy(38f))
            path.cubicTo(sx(15.2f), sy(39.2f), sx(16.4f), sy(40f), sx(17.6f), sy(40f))
            path.lineTo(sx(30.4f), sy(40f))
            path.cubicTo(sx(31.6f), sy(40f), sx(32.8f), sy(39.2f), sx(32.8f), sy(38f))
            path.lineTo(sx(34.4f), sy(14.8f))

            path.moveTo(sx(20.8f), sy(21.2f))
            path.lineTo(sx(21.2f), sy(33.2f))

            path.moveTo(sx(27.2f), sy(21.2f))
            path.lineTo(sx(26.8f), sy(33.2f))

            canvas.drawPath(path, paint)
        }
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
        private val density = UiPrefs.systemDensity()
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
