package io.github.fadhil_sabar.tulis.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.fadhil_sabar.tulis.export.ExportFormat
import io.github.fadhil_sabar.tulis.export.ExportOptions

private fun dialogDp(activity: Activity, v: Float): Int = (v * activity.resources.displayMetrics.density).toInt()

private fun sketchDialogRoot(activity: Activity): LinearLayout {
    val pad = dialogDp(activity, 20f)
    return LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 0)
        setPadding(pad, pad, pad, pad)
    }
}

private fun dialogTitle(activity: Activity, text: String): TextView = TextView(activity).apply {
    this.text = text
    setTextColor(Palette.INK)
    textSize = 24f
    typeface = Fonts.hand(context, bold = true)
}

private fun fieldLabel(activity: Activity, text: String): TextView = TextView(activity).apply {
    this.text = text
    setTextColor(Palette.INK_SOFT)
    textSize = 15f
    typeface = Fonts.ui(context)
}

private fun showSketchDialog(activity: Activity, view: LinearLayout, cancelable: Boolean): AlertDialog {
    val maxW = dialogDp(activity, 420f)
    return AlertDialog.Builder(activity)
        .setView(ScrollView(activity).apply { addView(view) })
        .setCancelable(cancelable)
        .show().apply {
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setLayout(
                Math.min(activity.resources.displayMetrics.widthPixels - dialogDp(activity, 32f), maxW),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
}

private fun actionButton(activity: Activity, label: String, primary: Boolean, onClick: () -> Unit): TextView =
    TextView(activity).apply {
        text = label
        setTextColor(if (primary) Palette.PAPER else Palette.INK)
        textSize = 17f
        typeface = Fonts.ui(context)
        gravity = Gravity.CENTER
        background = if (primary) {
            SketchDrawable(fill = Palette.INK, stroke = Palette.INK, variant = 3)
        } else {
            SketchDrawable(fill = Palette.CARD, stroke = Palette.INK_SOFT, variant = 2)
        }
        setPadding(dialogDp(activity, 18f), 0, dialogDp(activity, 18f), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

fun showExportDialog(
    activity: Activity,
    paged: Boolean,
    pageCount: Int,
    currentPage: Int,
    landscape: Boolean,
    loadThumb: (Int, ImageView, TextView) -> Unit,
    onConfirm: (ExportOptions) -> Unit
) {
    var format = ExportFormat.PDF
    var withPaper = true
    val picked = sortedSetOf<Int>()
    if (paged && currentPage in 0 until pageCount) picked.add(currentPage)
    var dialogRef: AlertDialog? = null
    lateinit var confirmBtn: TextView

    val root = sketchDialogRoot(activity)
    root.addView(dialogTitle(activity, "Ekspor"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dialogDp(activity, 14f)
    })

    fun addRow(label: String, options: List<String>, selected: () -> Int, onSelect: (Int) -> Unit): LinearLayout {
        val block = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        block.addView(fieldLabel(activity, label), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dialogDp(activity, 6f)
        })
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = ArrayList<TextView>()
        fun refresh() {
            for ((i, c) in chips.withIndex()) {
                val on = i == selected()
                c.setTextColor(if (on) Palette.PAPER else Palette.INK)
                c.background = if (on) {
                    SketchDrawable(fill = Palette.INK, stroke = Palette.INK, cornerDp = 8f)
                } else {
                    SketchDrawable(fill = Palette.CARD, stroke = Palette.INK_SOFT, cornerDp = 8f)
                }
            }
        }
        for ((i, name) in options.withIndex()) {
            val chip = TextView(activity).apply {
                text = name
                textSize = 16f
                typeface = Fonts.ui(context)
                gravity = Gravity.CENTER
                setPadding(dialogDp(activity, 14f), 0, dialogDp(activity, 14f), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    onSelect(i)
                }
            }
            chips.add(chip)
            row.addView(chip, LinearLayout.LayoutParams(0, dialogDp(activity, 44f), 1f).apply {
                if (i < options.size - 1) marginEnd = dialogDp(activity, 8f)
            })
        }
        block.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        block.tag = { refresh() }
        refresh()
        return block
    }

    fun refreshAll(vararg blocks: LinearLayout?) {
        for (b in blocks) {
            @Suppress("UNCHECKED_CAST")
            (b?.tag as? () -> Unit)?.invoke()
        }
    }

    fun updateConfirm() {
        confirmBtn.text = if (paged) "Ekspor (${picked.size} halaman)" else "Ekspor"
        confirmBtn.alpha = if (paged && picked.isEmpty()) 0.5f else 1f
    }

    fun confirm() {
        if (paged && picked.isEmpty()) return
        dialogRef?.dismiss()
        onConfirm(ExportOptions(format, if (paged) picked.toList() else emptyList(), withPaper))
    }

    lateinit var formatBlock: LinearLayout
    lateinit var paperBlock: LinearLayout
    val gap = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dialogDp(activity, 14f)
    }

    formatBlock = addRow("Format", listOf("PDF", "PNG"), { format.ordinal }, {
        format = ExportFormat.values()[it]
        refreshAll(formatBlock, paperBlock)
    })
    root.addView(formatBlock, gap)

    if (paged) {
        val cols = if (landscape) 2 else 3
        val cardW = if (landscape) 136f else 96f
        val cardH = if (landscape) 96f else 136f
        val refreshers = ArrayList<() -> Unit>()

        fun pageCard(i: Int): LinearLayout {
            val bg = SketchDrawable(fill = Palette.SHEET, stroke = Palette.INK_SOFT, strokeWidthDp = 1.6f, shadowOffsetDp = 0f, cornerDp = 4f)
            val frame = FrameLayout(activity).apply { background = bg }
            val thumb = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            val number = TextView(activity).apply {
                text = "${i + 1}"
                setTextColor(0x552B2724)
                textSize = 32f
                typeface = Fonts.hand(context, bold = true)
                gravity = Gravity.CENTER
            }
            val mark = TextView(activity).apply {
                text = "✓"
                setTextColor(Palette.PAPER)
                textSize = 14f
                typeface = Fonts.ui(context)
                gravity = Gravity.CENTER
                background = SketchDrawable(fill = Palette.INK, stroke = Palette.INK, round = true)
                visibility = View.GONE
            }
            val caption = TextView(activity).apply {
                text = "${i + 1}"
                textSize = 13f
                typeface = Fonts.ui(context)
                gravity = Gravity.CENTER
            }
            val inset = dialogDp(activity, 4f)
            val markSize = dialogDp(activity, 22f)
            frame.addView(thumb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                setMargins(inset, inset, inset, inset)
            })
            frame.addView(number, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            frame.addView(mark, FrameLayout.LayoutParams(markSize, markSize, Gravity.TOP or Gravity.END).apply {
                setMargins(0, dialogDp(activity, 2f), dialogDp(activity, 2f), 0)
            })
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                isFocusable = true
                addView(frame, LinearLayout.LayoutParams(dialogDp(activity, cardW), dialogDp(activity, cardH)))
                addView(caption, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dialogDp(activity, 22f)))
            }
            fun refresh() {
                val on = i in picked
                bg.applyFill(if (on) Palette.YELLOW else Palette.SHEET)
                bg.applyStroke(if (on) Palette.INK else Palette.INK_SOFT)
                bg.strokeWidthPx = (if (on) 3f else 1.6f) * activity.resources.displayMetrics.density
                mark.visibility = if (on) View.VISIBLE else View.GONE
                caption.setTextColor(if (on) Palette.INK else Palette.INK_SOFT)
            }
            refreshers.add { refresh() }
            refresh()
            card.setOnClickListener {
                if (!picked.remove(i)) picked.add(i)
                refresh()
                updateConfirm()
            }
            loadThumb(i, thumb, number)
            return card
        }

        val pagesBlock = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        pagesBlock.addView(fieldLabel(activity, "Halaman"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dialogDp(activity, 6f)
        })
        val chipRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        chipRow.addView(actionButton(activity, "Semua", false) {
            picked.clear()
            picked.addAll(0 until pageCount)
            refreshers.forEach { it() }
            updateConfirm()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dialogDp(activity, 36f)).apply {
            marginEnd = dialogDp(activity, 8f)
        })
        chipRow.addView(actionButton(activity, "Kosongkan", false) {
            picked.clear()
            refreshers.forEach { it() }
            updateConfirm()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dialogDp(activity, 36f)))
        pagesBlock.addView(chipRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dialogDp(activity, 8f)
        })

        val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        for (rowStart in 0 until pageCount step cols) {
            val rowView = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in rowStart until minOf(rowStart + cols, pageCount)) {
                rowView.addView(pageCard(i), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            grid.addView(rowView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dialogDp(activity, 8f)
            })
        }
        val rowCount = (pageCount + cols - 1) / cols
        val rowH = dialogDp(activity, cardH + 22f + 8f)
        val maxH = activity.resources.displayMetrics.heightPixels / 2
        val scroll = ScrollView(activity).apply { addView(grid) }
        pagesBlock.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(rowCount * rowH, maxH)))
        root.addView(pagesBlock, gap)
    }

    paperBlock = addRow("Latar", listOf("Dengan kertas", "Putih polos"), { if (withPaper) 0 else 1 }, {
        withPaper = it == 0
        refreshAll(formatBlock, paperBlock)
    })
    root.addView(paperBlock, gap)
    refreshAll(formatBlock, paperBlock)

    val btnRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
    }
    btnRow.addView(actionButton(activity, "Batal", false) { dialogRef?.dismiss() },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dialogDp(activity, 44f)).apply {
            marginEnd = dialogDp(activity, 10f)
        })
    confirmBtn = actionButton(activity, "Ekspor", true) { confirm() }
    btnRow.addView(confirmBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dialogDp(activity, 44f)))
    root.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    updateConfirm()

    dialogRef = showSketchDialog(activity, root, true)
}

class ExportProgressDialog(private val activity: Activity, onCancel: () -> Unit) {
    private val status = TextView(activity).apply {
        text = "Menyiapkan..."
        setTextColor(Palette.INK)
        textSize = 18f
        typeface = Fonts.ui(context)
    }
    private val dialog: AlertDialog

    init {
        val root = sketchDialogRoot(activity)
        root.addView(dialogTitle(activity, "Mengekspor"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dialogDp(activity, 12f)
        })
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dialogDp(activity, 18f)
        })
        val row = LinearLayout(activity).apply { gravity = Gravity.END }
        row.addView(actionButton(activity, "Batal", false) {
            status.text = "Membatalkan..."
            onCancel()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dialogDp(activity, 44f)))
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog = showSketchDialog(activity, root, false)
    }

    fun update(text: String) {
        status.text = text
    }

    fun dismiss() {
        if (!activity.isFinishing && !activity.isDestroyed) dialog.dismiss()
    }
}
