package com.example.tulis.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.tulis.export.ExportFormat
import com.example.tulis.export.ExportOptions

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

private fun showSketchDialog(activity: Activity, view: LinearLayout, cancelable: Boolean): AlertDialog {
    val maxW = dialogDp(activity, 420f)
    return AlertDialog.Builder(activity)
        .setView(view)
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

fun showExportDialog(activity: Activity, paged: Boolean, onConfirm: (ExportOptions) -> Unit) {
    var format = ExportFormat.PDF
    var allPages = false
    var withPaper = true

    val root = sketchDialogRoot(activity)
    root.addView(dialogTitle(activity, "Ekspor"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dialogDp(activity, 14f)
    })

    fun addRow(label: String, options: List<String>, selected: () -> Int, onSelect: (Int) -> Unit): LinearLayout {
        val block = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        block.addView(TextView(activity).apply {
            text = label
            setTextColor(Palette.INK_SOFT)
            textSize = 15f
            typeface = Fonts.ui(context)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
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

    var rangeBlock: LinearLayout? = null
    fun refreshAll(vararg blocks: LinearLayout?) {
        for (b in blocks) {
            @Suppress("UNCHECKED_CAST")
            (b?.tag as? () -> Unit)?.invoke()
        }
        rangeBlock?.visibility = if (paged && format == ExportFormat.PDF) android.view.View.VISIBLE else android.view.View.GONE
    }

    lateinit var formatBlock: LinearLayout
    lateinit var paperBlock: LinearLayout
    val gap = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dialogDp(activity, 14f)
    }

    formatBlock = addRow("Format", listOf("PDF", "PNG"), { format.ordinal }, {
        format = ExportFormat.values()[it]
        refreshAll(formatBlock, rangeBlock, paperBlock)
    })
    root.addView(formatBlock, gap)

    if (paged) {
        val rb = addRow("Halaman", listOf("Halaman ini", "Semua halaman"), { if (allPages) 1 else 0 }, {
            allPages = it == 1
            refreshAll(formatBlock, rangeBlock, paperBlock)
        })
        rangeBlock = rb
        root.addView(rb, gap)
    }

    paperBlock = addRow("Latar", listOf("Dengan kertas", "Putih polos"), { if (withPaper) 0 else 1 }, {
        withPaper = it == 0
        refreshAll(formatBlock, rangeBlock, paperBlock)
    })
    root.addView(paperBlock, gap)
    refreshAll(formatBlock, rangeBlock, paperBlock)

    val btnRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
    }
    var dialogRef: AlertDialog? = null
    btnRow.addView(actionButton(activity, "Batal", false) { dialogRef?.dismiss() },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dialogDp(activity, 44f)).apply {
            marginEnd = dialogDp(activity, 10f)
        })
    btnRow.addView(actionButton(activity, "Ekspor", true) {
        dialogRef?.dismiss()
        onConfirm(ExportOptions(format, paged && format == ExportFormat.PDF && allPages, withPaper))
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dialogDp(activity, 44f)))
    root.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

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
