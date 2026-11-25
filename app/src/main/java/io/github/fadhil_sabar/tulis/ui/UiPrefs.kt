package io.github.fadhil_sabar.tulis.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources

enum class TapAction {
    UNDO,
    REDO,
    TOGGLE_ERASER,
    PREVIOUS_TOOL,
    TOOL_PEN,
    TOOL_MARKER,
    TOOL_HIGHLIGHTER,
    TOOL_ERASER,
    TOOL_TEXT,
    TOOL_PAN,
    TOOL_SHAPE,
    TOOL_SELECT,
    NONE
}

fun TapAction.label(): String = when (this) {
    TapAction.UNDO -> "Urungkan"
    TapAction.REDO -> "Ulangi"
    TapAction.TOGGLE_ERASER -> "Toggle penghapus"
    TapAction.PREVIOUS_TOOL -> "Alat sebelumnya"
    TapAction.TOOL_PEN -> "Pena"
    TapAction.TOOL_MARKER -> "Spidol"
    TapAction.TOOL_HIGHLIGHTER -> "Stabilo"
    TapAction.TOOL_ERASER -> "Hapus"
    TapAction.TOOL_TEXT -> "Teks"
    TapAction.TOOL_PAN -> "Geser"
    TapAction.TOOL_SHAPE -> "Bentuk"
    TapAction.TOOL_SELECT -> "Pilih"
    TapAction.NONE -> "Tidak ada"
}

object UiPrefs {
    private const val PREFS_NAME = "tulis_prefs"
    private const val KEY_FONT_SCALE = "ui_font_scale"
    private const val KEY_ICON_SCALE = "ui_icon_scale"
    private const val KEY_MINIMAP_VISIBLE = "minimap_visible"
    private const val KEY_TAP2_ACTION = "tap2_action"
    private const val KEY_TAP3_ACTION = "tap3_action"
    private const val KEY_STYLUS_BACK_ACTION = "stylus_back_action"
    private const val KEY_STYLUS_FORWARD_ACTION = "stylus_forward_action"

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

    private fun readTapAction(ctx: Context, key: String, fallback: TapAction): TapAction {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = sp.getString(key, null) ?: return fallback
        return TapAction.values().firstOrNull { it.name == raw } ?: fallback
    }

    private fun writeTapAction(ctx: Context, key: String, a: TapAction) {
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(key, a.name).apply()
    }

    private fun tapSlot(fingers: Int): Pair<String, TapAction>? = when (fingers) {
        2 -> KEY_TAP2_ACTION to TapAction.UNDO
        3 -> KEY_TAP3_ACTION to TapAction.REDO
        else -> null
    }

    fun tapAction(ctx: Context, fingers: Int): TapAction {
        val (key, fallback) = tapSlot(fingers) ?: return TapAction.UNDO
        return readTapAction(ctx, key, fallback)
    }

    fun setTapAction(ctx: Context, fingers: Int, a: TapAction) {
        val (key, _) = tapSlot(fingers) ?: return
        writeTapAction(ctx, key, a)
    }

    fun stylusAction(ctx: Context, forward: Boolean): TapAction {
        val key = if (forward) KEY_STYLUS_FORWARD_ACTION else KEY_STYLUS_BACK_ACTION
        val fallback = if (forward) TapAction.REDO else TapAction.UNDO
        return readTapAction(ctx, key, fallback)
    }

    fun setStylusAction(ctx: Context, forward: Boolean, a: TapAction) {
        val key = if (forward) KEY_STYLUS_FORWARD_ACTION else KEY_STYLUS_BACK_ACTION
        writeTapAction(ctx, key, a)
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
