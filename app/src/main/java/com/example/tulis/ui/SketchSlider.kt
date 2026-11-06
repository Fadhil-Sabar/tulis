package com.example.tulis.ui

import android.content.Context
import android.content.res.ColorStateList
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

class SketchSlider(
    context: Context,
    private var min: Float,
    private var max: Float,
    private var step: Float,
    initial: Float,
    private val displayScale: Float = 1f,
    private val suffix: String = "",
    description: String,
    private val showButtons: Boolean = true,
    private val compact: Boolean = false,
    private val buttonStep: Float = step,
    private val onChange: (Float) -> Unit,
    private val onCommit: (Float) -> Unit = {}
) : LinearLayout(context) {

    private val steps: Int
        get() = ((max - min) / step).roundToInt()
    private val seekBar: SeekBar
    private val field: EditText

    var value: Float = snap(initial)
        set(v) {
            field = snap(v)
            syncViews()
        }

    private fun decimals(): Int {
        val ds = step * displayScale
        return if (abs(ds - ds.roundToInt()) < 1e-3f) 0 else 1
    }

    private fun display(v: Float): String =
        String.format(java.util.Locale.US, "%.${decimals()}f", v * displayScale)

    init {
        orientation = if (compact) VERTICAL else HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val density = resources.displayMetrics.density
        fun dp(v: Float): Int = (v * density).toInt()
        minimumHeight = dp(44f)

        fun stepButton(label: String, desc: String, sign: Float): TextView = TextView(context).apply {
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
                field.clearFocus()
                val nv = snap(value + sign * buttonStep)
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

        field = EditText(context).apply {
            textSize = 16f
            typeface = Fonts.ui(context)
            setTextColor(Palette.INK)
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            setSelectAllOnFocus(true)
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            background = SketchDrawable(fill = Palette.CARD, stroke = Palette.INK_SOFT, cornerDp = 8f)
            contentDescription = "Nilai $description"
            setText(display(value))
        }

        field.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                v.clearFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(v.windowToken, 0)
                true
            } else {
                false
            }
        }
        field.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyTyped()
        }

        val suffixView = TextView(context).apply {
            textSize = 16f
            typeface = Fonts.ui(context)
            setTextColor(Palette.INK)
            gravity = Gravity.CENTER_VERTICAL
            text = suffix
            isClickable = false
            isFocusable = false
        }

        val controls: LinearLayout
        if (compact) {
            addView(seekBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            controls = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
            }
            addView(controls, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6f)
            })
        } else {
            controls = this
        }

        if (showButtons) {
            controls.addView(stepButton("−", "Kurangi", -1f), LayoutParams(dp(44f), dp(44f)))
        }
        if (!compact) {
            controls.addView(seekBar, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8f)
                marginEnd = dp(8f)
            })
        }
        controls.addView(field, LayoutParams(dp(if (compact) 72f else 64f), dp(44f)).apply {
            marginStart = dp(if (compact) 8f else 0f)
            marginEnd = if (suffix.isEmpty()) dp(8f) else dp(2f)
        })
        if (suffix.isNotEmpty()) {
            controls.addView(suffixView, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44f)).apply {
                marginEnd = dp(8f)
            })
        }
        if (showButtons) {
            controls.addView(stepButton("+", "Tambah", 1f), LayoutParams(dp(44f), dp(44f)))
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                field.clearFocus()
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

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        seekBar.isEnabled = enabled
        field.isEnabled = enabled
        for (i in 0 until childCount) getChildAt(i).isEnabled = enabled
    }

    fun setRange(newMin: Float, newMax: Float, newStep: Float) {
        min = newMin
        max = newMax
        step = newStep
        seekBar.max = steps
        value = value
    }

    private fun applyTyped() {
        val raw = field.text.toString().replace(',', '.').toFloatOrNull()
        if (raw != null) {
            val nv = snap(raw / displayScale)
            if (nv != value) {
                value = nv
                onChange(nv)
            }
            onCommit(value)
        }
        syncViews(force = true)
    }

    private fun snap(v: Float): Float {
        val clamped = v.coerceIn(min, max)
        return min + ((clamped - min) / step).roundToInt() * step
    }

    private fun toProgress(v: Float): Int = ((v - min) / step).roundToInt().coerceIn(0, steps)

    private fun fromProgress(p: Int): Float = snap(min + p * step)

    private fun syncViews(force: Boolean = false) {
        val p = toProgress(value)
        if (seekBar.progress != p) seekBar.progress = p
        val s = display(value)
        if (force || (field.text.toString() != s && !field.hasFocus())) field.setText(s)
    }
}
