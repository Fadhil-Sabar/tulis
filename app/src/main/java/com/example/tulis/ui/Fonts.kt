package com.example.tulis.ui

import android.content.Context
import android.graphics.Typeface
import com.example.tulis.R

object Fonts {
    private var uiTypeface: Typeface? = null
    private var handBoldTypeface: Typeface? = null
    private var handRegularTypeface: Typeface? = null

    fun ui(context: Context): Typeface {
        val cached = uiTypeface
        if (cached != null) return cached
        val loaded = context.resources.getFont(R.font.patrick_hand)
        uiTypeface = loaded
        return loaded
    }

    fun hand(context: Context, bold: Boolean = true): Typeface {
        return if (bold) {
            val cached = handBoldTypeface
            if (cached != null) return cached
            val base = context.resources.getFont(R.font.caveat)
            val weighted = Typeface.create(base, 700, false)
            handBoldTypeface = weighted
            weighted
        } else {
            val cached = handRegularTypeface
            if (cached != null) return cached
            val base = context.resources.getFont(R.font.caveat)
            val weighted = Typeface.create(base, 600, false)
            handRegularTypeface = weighted
            weighted
        }
    }
}
