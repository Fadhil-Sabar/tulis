package com.example.tulis.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.example.tulis.model.PaperStyle

fun showUiSettingsDialog(activity: Activity, onApplied: () -> Unit) {
    val density = activity.resources.displayMetrics.density
    fun dp(v: Float): Int = (v * density).toInt()

    val maxDialogWidth = (420f * density).toInt()

    var changed = false
    var currentFont = UiPrefs.fontScale(activity)
    var currentIcon = UiPrefs.iconScale(activity)
    var currentMinimap = UiPrefs.minimapVisible(activity)

    val scrollView = ScrollView(activity).apply {
        isFillViewport = true
    }

    val dialogView = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 0)
        val pad = dp(20f)
        setPadding(pad, pad, pad, pad)
    }
    scrollView.addView(
        dialogView,
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    )

    val titleV = TextView(activity).apply {
        text = "Tampilan"
        setTextColor(Palette.INK)
        textSize = 24f
        typeface = Fonts.hand(context, bold = true)
    }
    dialogView.addView(
        titleV,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(16f)
        }
    )

    val fontLabel = TextView(activity).apply {
        text = "Ukuran font"
        setTextColor(Palette.INK)
        textSize = 16f
        typeface = Fonts.ui(context)
    }
    dialogView.addView(
        fontLabel,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8f)
        }
    )

    val fontOptions = listOf(
        0.85f to "Kecil",
        1.0f to "Normal",
        1.15f to "Besar",
        1.3f to "Lebih besar",
        1.5f to "Terbesar"
    )

    val fontChipsLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
    }
    val fontScroll = android.widget.HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        addView(fontChipsLayout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    dialogView.addView(
        fontScroll,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(16f)
        }
    )

    val fontViews = mutableListOf<TextView>()
    fun updateFontChips() {
        for ((idx, opt) in fontOptions.withIndex()) {
            val tv = fontViews[idx]
            val isSelected = Math.abs(currentFont - opt.first) < 0.01f
            if (isSelected) {
                tv.background = SketchDrawable(fill = Palette.INK, stroke = Palette.INK, cornerDp = 8f)
                tv.setTextColor(Palette.PAPER)
            } else {
                tv.background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK_SOFT, cornerDp = 8f)
                tv.setTextColor(Palette.INK)
            }
        }
    }

    for (opt in fontOptions) {
        val chip = TextView(activity).apply {
            text = opt.second
            textSize = 14f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            minimumHeight = dp(44f)
            val hp = dp(12f)
            setPadding(hp, 0, hp, 0)
            isClickable = true
            isFocusable = true
            contentDescription = "Ukuran font: ${opt.second}"
            setOnClickListener {
                if (Math.abs(currentFont - opt.first) >= 0.01f) {
                    currentFont = opt.first
                    UiPrefs.setFontScale(activity, currentFont)
                    changed = true
                    updateFontChips()
                }
            }
        }
        fontViews.add(chip)
        fontChipsLayout.addView(
            chip,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8f)
            }
        )
    }
    updateFontChips()

    val iconLabel = TextView(activity).apply {
        text = "Zoom tampilan (tombol, ikon, teks)"
        setTextColor(Palette.INK)
        textSize = 16f
        typeface = Fonts.ui(context)
    }
    dialogView.addView(
        iconLabel,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8f)
        }
    )

    val iconOptions = listOf(
        0.85f to "Kecil",
        1.0f to "Normal",
        1.15f to "Besar",
        1.3f to "Terbesar"
    )

    val iconChipsLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
    }
    val iconScroll = android.widget.HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        addView(iconChipsLayout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    dialogView.addView(
        iconScroll,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(16f)
        }
    )

    val iconViews = mutableListOf<TextView>()
    fun updateIconChips() {
        for ((idx, opt) in iconOptions.withIndex()) {
            val tv = iconViews[idx]
            val isSelected = Math.abs(currentIcon - opt.first) < 0.01f
            if (isSelected) {
                tv.background = SketchDrawable(fill = Palette.INK, stroke = Palette.INK, cornerDp = 8f)
                tv.setTextColor(Palette.PAPER)
            } else {
                tv.background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK_SOFT, cornerDp = 8f)
                tv.setTextColor(Palette.INK)
            }
        }
    }

    for (opt in iconOptions) {
        val chip = TextView(activity).apply {
            text = opt.second
            textSize = 14f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            minimumHeight = dp(44f)
            val hp = dp(12f)
            setPadding(hp, 0, hp, 0)
            isClickable = true
            isFocusable = true
            contentDescription = "Zoom tampilan: ${opt.second}"
            setOnClickListener {
                if (Math.abs(currentIcon - opt.first) >= 0.01f) {
                    currentIcon = opt.first
                    UiPrefs.setIconScale(activity, currentIcon)
                    changed = true
                    updateIconChips()
                }
            }
        }
        iconViews.add(chip)
        iconChipsLayout.addView(
            chip,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8f)
            }
        )
    }
    updateIconChips()

    val minimapRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(44f)
        isClickable = true
        isFocusable = true
    }

    val minimapLabel = TextView(activity).apply {
        text = "Peta mini (kanvas tak terbatas)"
        setTextColor(Palette.INK)
        textSize = 16f
        typeface = Fonts.ui(context)
        isClickable = false
        isFocusable = false
    }
    minimapRow.addView(
        minimapLabel,
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    )

    val toggleBtn = TextView(activity).apply {
        textSize = 14f
        typeface = Fonts.ui(context)
        gravity = Gravity.CENTER
        minimumHeight = dp(36f)
        val hp = dp(14f)
        setPadding(hp, 0, hp, 0)
        isClickable = false
        isFocusable = false
    }
    fun updateToggleUi() {
        if (currentMinimap) {
            toggleBtn.text = "Aktif"
            toggleBtn.background = SketchDrawable(fill = Palette.GREEN, stroke = Palette.INK, cornerDp = 8f)
            toggleBtn.setTextColor(Palette.PAPER)
            minimapRow.contentDescription = "Peta mini (kanvas tak terbatas): Aktif"
        } else {
            toggleBtn.text = "Mati"
            toggleBtn.background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK_SOFT, cornerDp = 8f)
            toggleBtn.setTextColor(Palette.INK_SOFT)
            minimapRow.contentDescription = "Peta mini (kanvas tak terbatas): Mati"
        }
    }
    updateToggleUi()
    minimapRow.addView(
        toggleBtn,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    )

    minimapRow.setOnClickListener {
        currentMinimap = !currentMinimap
        UiPrefs.setMinimapVisible(activity, currentMinimap)
        changed = true
        updateToggleUi()
    }

    dialogView.addView(
        minimapRow,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(20f)
        }
    )

    val btnRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
    }

    var dialogRef: AlertDialog? = null

    val doneBtn = TextView(activity).apply {
        text = "Selesai"
        setTextColor(Palette.PAPER)
        textSize = 17f
        typeface = Fonts.ui(context)
        gravity = Gravity.CENTER
        background = SketchDrawable(fill = Palette.INK, stroke = Palette.INK, variant = 3)
        minimumHeight = dp(44f)
        setPadding(dp(20f), 0, dp(20f), 0)
        isClickable = true
        isFocusable = true
        contentDescription = "Selesai pengaturan tampilan"
        setOnClickListener {
            dialogRef?.dismiss()
        }
    }
    btnRow.addView(doneBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    dialogView.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    dialogRef = AlertDialog.Builder(activity)
        .setView(scrollView)
        .setOnDismissListener {
            if (changed) {
                onApplied()
            }
        }
        .show().apply {
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setLayout(
                Math.min(activity.resources.displayMetrics.widthPixels - dp(32f), maxDialogWidth),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
}

private class PaperPreviewView(context: Context) : View(context) {
    var style: PaperStyle = PaperStyle.PLAIN
        set(value) {
            field = value
            invalidate()
        }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x552B2724
        strokeWidth = 1f * resources.displayMetrics.density
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.DOT_CANVAS
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val density = resources.displayMetrics.density

        when (style) {
            PaperStyle.PLAIN -> {
            }
            PaperStyle.DOTS -> {
                val step = 7f * density
                val dotRadius = 1f * density
                var y = step
                while (y < h - 2f) {
                    var x = step
                    while (x < w - 2f) {
                        canvas.drawCircle(x, y, dotRadius, dotPaint)
                        x += step
                    }
                    y += step
                }
            }
            PaperStyle.GRID -> {
                val step = 7f * density
                var x = step
                while (x < w) {
                    canvas.drawLine(x, 0f, x, h, linePaint)
                    x += step
                }
                var y = step
                while (y < h) {
                    canvas.drawLine(0f, y, w, y, linePaint)
                    y += step
                }
            }
            PaperStyle.LINES -> {
                val step = 7f * density
                var y = step
                while (y < h) {
                    canvas.drawLine(0f, y, w, y, linePaint)
                    y += step
                }
            }
        }
    }
}

fun showPaperDialog(
    activity: Activity,
    style: PaperStyle,
    spacing: Float,
    onChanged: (PaperStyle, Float) -> Unit
) {
    val density = activity.resources.displayMetrics.density
    fun dp(v: Float): Int = (v * density).toInt()

    val maxDialogWidth = (420f * density).toInt()

    var currentStyle = style
    var currentSpacing = spacing.coerceIn(12f, 120f)

    val scrollView = ScrollView(activity).apply {
        isFillViewport = true
    }

    val dialogView = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, variant = 0)
        val pad = dp(20f)
        setPadding(pad, pad, pad, pad)
    }
    scrollView.addView(
        dialogView,
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    )

    val titleV = TextView(activity).apply {
        text = "Kertas"
        setTextColor(Palette.INK)
        textSize = 24f
        typeface = Fonts.hand(context, bold = true)
    }
    dialogView.addView(
        titleV,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(16f)
        }
    )

    val styleLabel = TextView(activity).apply {
        text = "Gaya kertas"
        setTextColor(Palette.INK)
        textSize = 16f
        typeface = Fonts.ui(context)
    }
    dialogView.addView(
        styleLabel,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8f)
        }
    )

    val styles = listOf(
        PaperStyle.PLAIN to "Polos",
        PaperStyle.DOTS to "Titik",
        PaperStyle.GRID to "Kotak",
        PaperStyle.LINES to "Garis"
    )

    val chipsRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
    }
    val chipsScroll = android.widget.HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        addView(chipsRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    dialogView.addView(
        chipsScroll,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(16f)
        }
    )

    val chipViews = mutableListOf<LinearLayout>()
    val chipTexts = mutableListOf<TextView>()
    lateinit var spacingContainer: LinearLayout

    fun updateStyleSelection() {
        for ((idx, item) in styles.withIndex()) {
            val isSel = currentStyle == item.first
            val cView = chipViews[idx]
            val cText = chipTexts[idx]
            if (isSel) {
                cView.background = SketchDrawable(fill = Palette.INK, stroke = Palette.INK, cornerDp = 8f)
                cText.setTextColor(Palette.PAPER)
            } else {
                cView.background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK_SOFT, cornerDp = 8f)
                cText.setTextColor(Palette.INK)
            }
        }
        val isPlain = currentStyle == PaperStyle.PLAIN
        spacingContainer.alpha = if (isPlain) 0.4f else 1.0f
        for (i in 0 until spacingContainer.childCount) {
            spacingContainer.getChildAt(i).isEnabled = !isPlain
        }
    }

    for (item in styles) {
        val chip = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44f)
            val hp = dp(10f)
            setPadding(hp, dp(4f), hp, dp(4f))
            isClickable = true
            isFocusable = true
            contentDescription = "Gaya kertas: ${item.second}"
        }

        val preview = PaperPreviewView(activity).apply {
            this.style = item.first
            isClickable = false
            isFocusable = false
            background = SketchDrawable(fill = Palette.PAPER, stroke = Palette.INK_SOFT, strokeWidthDp = 1f, cornerDp = 4f)
        }
        chip.addView(preview, LinearLayout.LayoutParams(dp(26f), dp(26f)).apply {
            marginEnd = dp(6f)
        })

        val label = TextView(activity).apply {
            text = item.second
            textSize = 14f
            typeface = Fonts.ui(context)
            isClickable = false
            isFocusable = false
        }
        chip.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        chip.setOnClickListener {
            if (currentStyle != item.first) {
                currentStyle = item.first
                updateStyleSelection()
                onChanged(currentStyle, currentSpacing)
            }
        }

        chipViews.add(chip)
        chipTexts.add(label)
        chipsRow.addView(
            chip,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8f)
            }
        )
    }

    val spacingLabel = TextView(activity).apply {
        text = "Jarak"
        setTextColor(Palette.INK)
        textSize = 16f
        typeface = Fonts.ui(context)
    }
    dialogView.addView(
        spacingLabel,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8f)
        }
    )

    spacingContainer = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(44f)
    }

    val minusBtn = TextView(activity).apply {
        text = "−"
        textSize = 20f
        typeface = Fonts.ui(context)
        gravity = Gravity.CENTER
        setTextColor(Palette.INK)
        background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, cornerDp = 8f)
        minimumHeight = dp(44f)
        isClickable = true
        isFocusable = true
        contentDescription = "Kurangi jarak kertas"
    }

    val spacingText = TextView(activity).apply {
        text = "${currentSpacing.toInt()}"
        textSize = 16f
        typeface = Fonts.ui(context)
        setTextColor(Palette.INK)
        gravity = Gravity.CENTER
        minimumHeight = dp(44f)
        isClickable = false
        isFocusable = false
    }

    val plusBtn = TextView(activity).apply {
        text = "+"
        textSize = 20f
        typeface = Fonts.ui(context)
        gravity = Gravity.CENTER
        setTextColor(Palette.INK)
        background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, cornerDp = 8f)
        minimumHeight = dp(44f)
        isClickable = true
        isFocusable = true
        contentDescription = "Tambah jarak kertas"
    }

    val seekBar = SeekBar(activity).apply {
        max = 120 - 12
        progress = (currentSpacing - 12f).toInt()
        contentDescription = "Penggeser jarak kertas"
    }

    fun applySpacing(newSp: Float) {
        val clamped = newSp.coerceIn(12f, 120f)
        currentSpacing = clamped
        spacingText.text = "${clamped.toInt()}"
        val p = (clamped - 12f).toInt()
        if (seekBar.progress != p) {
            seekBar.progress = p
        }
        onChanged(currentStyle, currentSpacing)
    }

    minusBtn.setOnClickListener {
        applySpacing(currentSpacing - 4f)
    }

    plusBtn.setOnClickListener {
        applySpacing(currentSpacing + 4f)
    }

    seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) {
                val sp = 12f + progress.toFloat()
                applySpacing(sp)
            }
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    })

    spacingContainer.addView(minusBtn, LinearLayout.LayoutParams(dp(44f), dp(44f)))
    spacingContainer.addView(seekBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginStart = dp(8f)
        marginEnd = dp(8f)
    })
    spacingContainer.addView(spacingText, LinearLayout.LayoutParams(dp(36f), dp(44f)))
    spacingContainer.addView(plusBtn, LinearLayout.LayoutParams(dp(44f), dp(44f)))

    dialogView.addView(
        spacingContainer,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(20f)
        }
    )

    updateStyleSelection()

    val btnRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
    }

    var dialogRef: AlertDialog? = null

    val doneBtn = TextView(activity).apply {
        text = "Selesai"
        setTextColor(Palette.PAPER)
        textSize = 17f
        typeface = Fonts.ui(context)
        gravity = Gravity.CENTER
        background = SketchDrawable(fill = Palette.INK, stroke = Palette.INK, variant = 3)
        minimumHeight = dp(44f)
        setPadding(dp(20f), 0, dp(20f), 0)
        isClickable = true
        isFocusable = true
        contentDescription = "Selesai pengaturan kertas"
        setOnClickListener {
            dialogRef?.dismiss()
        }
    }
    btnRow.addView(doneBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    dialogView.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    dialogRef = AlertDialog.Builder(activity)
        .setView(scrollView)
        .show().apply {
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setLayout(
                Math.min(activity.resources.displayMetrics.widthPixels - dp(32f), maxDialogWidth),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
}
