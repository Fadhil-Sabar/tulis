package io.github.fadhil_sabar.tulis.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources

object UiPrefs {
    private const val PREFS_NAME = "tulis_prefs"
    private const val KEY_FONT_SCALE = "ui_font_scale"
    private const val KEY_ICON_SCALE = "ui_icon_scale"
    private const val KEY_MINIMAP_VISIBLE = "minimap_visible"

    @Volatile
    var iconScale: Float = 1f

    fun fontScale(ctx: Context): Float {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getFloat(KEY_FONT_SCALE, 1.0f)
    }

    fun setFontScale(ctx: Context, v: Float) {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putFloat(KEY_FONT_SCALE, v).apply()
    }

    fun iconScale(ctx: Context): Float {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val v = sp.getFloat(KEY_ICON_SCALE, 1.0f)
        iconScale = v
        return v
    }

    fun setIconScale(ctx: Context, v: Float) {
        iconScale = v
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putFloat(KEY_ICON_SCALE, v).apply()
    }

    fun minimapVisible(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_MINIMAP_VISIBLE, true)
    }

    fun setMinimapVisible(ctx: Context, v: Boolean) {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_MINIMAP_VISIBLE, v).apply()
    }

    fun physicalWidthDp(ctx: Context): Float =
        ctx.resources.displayMetrics.widthPixels / Resources.getSystem().displayMetrics.density

    fun physicalHeightDp(ctx: Context): Float =
        ctx.resources.displayMetrics.heightPixels / Resources.getSystem().displayMetrics.density

    fun systemDensity(): Float = Resources.getSystem().displayMetrics.density * iconScale

    fun wrap(base: Context): Context {
        val sp = base.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val zoom = sp.getFloat(KEY_ICON_SCALE, 1.0f)
        iconScale = zoom
        val userFontScale = sp.getFloat(KEY_FONT_SCALE, 1.0f)

        if (zoom == 1.0f && userFontScale == 1.0f) return base

        val sys = Resources.getSystem().configuration
        val config = Configuration()
        if (zoom != 1.0f) {
            config.densityDpi = Math.round(sys.densityDpi * zoom)
        }
        if (userFontScale != 1.0f) {
            config.fontScale = sys.fontScale * userFontScale
        }
        return base.createConfigurationContext(config)
    }
}
