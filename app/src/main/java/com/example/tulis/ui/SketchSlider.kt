package com.example.tulis.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

class SketchSlider(
    context: Context,
    private var min: Float,
    private var max: Float,
    private var step: Float,
    initial: Float,
    private val format: (Float) -> String,
    description: String,
    private val showButtons: Boolean = true,
    private val onChange: (Float) -> Unit,
    private val onCommit: (Float) -> Unit = {}
) : LinearLayout(context) {

    private val steps: Int
        get() = ((max - min) / step).roundToInt()
    private val seekBar: SeekBar
    private val valueText: TextView

    var value: Float = snap(initial)
        set(v) {
            field = snap(v)
            syncViews()
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val density = resources.displayMetrics.density
        fun dp(v: Float): Int = (v * density).toInt()
        minimumHeight = dp(44f)

        fun stepButton(label: String, desc: String, delta: Float): TextView = TextView(context).apply {
            text = label
            textSize = 20f
            typeface = Fonts.ui(context)
            gravity = Gravity.CENTER
            setTextColor(Palette.INK)
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK, cornerDp = 8f)
            isClickable = true
            isFocusable = true
            contentDescription = "$desc $description"
            setOnClickListener {
                val nv = snap(value + delta)
                if (nv != value) {
                    value = nv
                    onChange(nv)
                }
                onCommit(value)
            }
        }

        seekBar = SeekBar(context).apply {
            max = steps
            progress = toProgress(value)
            contentDescription = description
            progressTintList = ColorStateList.valueOf(Palette.INK)
            thumbTintList = ColorStateList.valueOf(Palette.INK)
            progressBackgroundTintList = ColorStateList.valueOf(Palette.INK_SOFT)
        }

        valueText = TextView(context).apply {
            textSize = 16f
            typeface = Fonts.ui(context)
            setTextColor(Palette.INK)
            gravity = Gravity.CENTER
            isClickable = false
            isFocusable = false
            text = format(value)
        }

        if (showButtons) {
            addView(stepButton("−", "Kurangi", -step), LayoutParams(dp(44f), dp(44f)))
        }
        addView(seekBar, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8f)
            marginEnd = dp(8f)
        })
        addView(valueText, LayoutParams(dp(56f), dp(44f)))
        if (showButtons) {
            addView(stepButton("+", "Tambah", step), LayoutParams(dp(44f), dp(44f)))
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val nv = fromProgress(progress)
                if (nv != value) {
                    value = nv
                    onChange(nv)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                onCommit(value)
            }
        })
    }

    fun setRange(newMin: Float, newMax: Float, newStep: Float) {
        min = newMin
        max = newMax
        step = newStep
        seekBar.max = steps
        value = value
    }

    private fun snap(v: Float): Float {
        val clamped = v.coerceIn(min, max)
        return min + ((clamped - min) / step).roundToInt() * step
    }

    private fun toProgress(v: Float): Int = ((v - min) / step).roundToInt().coerceIn(0, steps)

    private fun fromProgress(p: Int): Float = snap(min + p * step)

    private fun syncViews() {
        val p = toProgress(value)
        if (seekBar.progress != p) seekBar.progress = p
        valueText.text = format(value)
    }
}
