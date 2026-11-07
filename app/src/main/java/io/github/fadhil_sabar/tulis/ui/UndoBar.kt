package io.github.fadhil_sabar.tulis.ui

import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

object UndoBar {
    private const val TAG_UNDO_BAR = "TULIS_UNDO_BAR_VIEW"

    fun show(
        parent: ViewGroup,
        message: String,
        onUndo: () -> Unit,
        onExpire: () -> Unit = {},
        durationMs: Long = 6000L
    ) {
        val oldBar = parent.findViewWithTag<View>(TAG_UNDO_BAR)
        if (oldBar != null) {
            val oldRunnable = oldBar.getTag(Int.MIN_VALUE) as? Runnable
            if (oldRunnable != null) {
                oldBar.removeCallbacks(oldRunnable)
                oldBar.setTag(Int.MIN_VALUE, null)
            }
            val oldExpire = oldBar.getTag(Int.MIN_VALUE + 1) as? (() -> Unit)
            oldExpire?.invoke()
            parent.removeView(oldBar)
        }

        val context = parent.context
        val density = context.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()

        val bar = LinearLayout(context).apply {
            tag = TAG_UNDO_BAR
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56f)
            background = SketchDrawable(
                seed = System.nanoTime(),
                fill = Palette.CARD,
                stroke = Palette.INK,
                strokeWidthDp = 2.5f,
                shadowOffsetDp = 3f,
                cornerDp = 14f
            )
            val padH = dp(16f)
            val padV = dp(6f)
            setPadding(padH, padV, padH, padV)
            isClickable = true
            isFocusable = true
        }

        val textView = TextView(context).apply {
            text = message
            textSize = 18f
            typeface = Fonts.ui(context)
            setTextColor(Palette.INK)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(12f)
            }
        }
        bar.addView(textView)

        val btnUndo = TextView(context).apply {
            text = "URUNGKAN"
            textSize = 18f
            typeface = Fonts.ui(context)
            setTextColor(Palette.INK)
            gravity = Gravity.CENTER
            minimumHeight = dp(44f)
            minimumWidth = dp(44f)
            val hPad = dp(14f)
            val vPad = dp(6f)
            setPadding(hPad, vPad, hPad, vPad)
            background = SketchDrawable(
                seed = System.nanoTime() + 1,
                fill = Palette.YELLOW,
                stroke = Palette.INK,
                strokeWidthDp = 2f,
                shadowOffsetDp = 1.5f,
                cornerDp = 22f
            )
            contentDescription = "Urungkan $message"
            isClickable = true
            isFocusable = true
        }
        bar.addView(btnUndo)

        val expireRunnable = Runnable {
            if (bar.parent === parent) {
                parent.removeView(bar)
                onExpire()
            }
        }

        bar.setTag(Int.MIN_VALUE, expireRunnable)
        bar.setTag(Int.MIN_VALUE + 1, onExpire)

        btnUndo.setOnClickListener {
            bar.removeCallbacks(expireRunnable)
            bar.setTag(Int.MIN_VALUE, null)
            bar.setTag(Int.MIN_VALUE + 1, null)
            if (bar.parent === parent) {
                parent.removeView(bar)
            }
            onUndo()
        }

        val lp = if (parent is FrameLayout) {
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                val mH = dp(16f)
                val mB = dp(24f)
                setMargins(mH, 0, mH, mB)
            }
        } else {
            ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val mH = dp(16f)
                val mB = dp(24f)
                setMargins(mH, 0, mH, mB)
            }
        }

        parent.addView(bar, lp)
        bar.postDelayed(expireRunnable, durationMs)

        bar.contentDescription = "$message. Ketuk tombol urungkan untuk membatalkan."
        bar.announceForAccessibility(message)
    }
}
