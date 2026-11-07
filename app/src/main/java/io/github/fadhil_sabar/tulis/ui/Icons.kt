package io.github.fadhil_sabar.tulis.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

object Palette {
    const val PAPER: Int = 0xFFF6F0E2.toInt()
    const val CARD: Int = 0xFFFBF7EC.toInt()
    const val INK: Int = 0xFF2B2724.toInt()
    const val INK_SOFT: Int = 0xFF5B534A.toInt()
    const val RED: Int = 0xFFC23B1F.toInt()
    const val YELLOW: Int = 0xFFF5D547.toInt()
    const val BLUE: Int = 0xFF2D5DA8.toInt()
    const val GREEN: Int = 0xFF3E7D4F.toInt()
    const val ORANGE: Int = 0xFFE0801C.toInt()
    const val PURPLE: Int = 0xFF7A4FA3.toInt()
    const val KRAFT: Int = 0xFFE9E0CB.toInt()
    const val CANVAS: Int = 0xFFFBF8EF.toInt()
    const val DESK: Int = 0xFFE6DDC8.toInt()
    const val SHEET: Int = 0xFFFFFDF6.toInt()
    const val SHADOW_SOFT: Int = 0x332B2724
    const val SHADOW_PAGE: Int = 0x232B2724
    const val DOT_SOFT: Int = 0x1A2B2724
    const val DOT_CANVAS: Int = 0x2E2B2724
    const val DOT_CARD: Int = 0x382B2724
}

enum class Icon {
    BACK,
    SEARCH,
    ADD,
    UNDO,
    REDO,
    PEN,
    MARKER,
    HIGHLIGHTER,
    ERASER,
    TEXT,
    PAN,
    TRASH,
    INFINITE,
    PAGE,
    PREV,
    NEXT,
    LOCATE,
    SETTINGS,
    PAPER,
    EXPORT,
    FOLDER,
    VAULT,
    MORE
}

object Icons {
    private val matrix = Matrix()
    private val drawPath = Path()

    private val cachedPaths: Array<Path> = Array(Icon.values().size) { index ->
        val icon = Icon.values()[index]
        parseSvgPath(pathDataFor(icon))
    }

    private fun pathDataFor(icon: Icon): String {
        return when (icon) {
            Icon.BACK -> "M14.5 5.5 C12 8.4 10 10.2 8.2 12.1 C10 13.9 12 15.8 14.6 18.6"
            Icon.SEARCH -> "M10.5 4.6 C14 4.4 16.8 7.2 16.6 10.6 C16.4 14 13.6 16.6 10.2 16.4 C6.8 16.2 4.4 13.4 4.6 10.1 C4.8 6.9 7.4 4.8 10.5 4.6 Z M15.2 15.4 C17 17.2 18.6 18.8 19.8 20"
            Icon.ADD -> "M12 4.5 C12.2 9 11.8 15 12 19.6 M4.4 12.2 C9 11.8 15 12.3 19.7 11.9"
            Icon.UNDO -> "M9 7.5 L5 11.2 L9.2 14.8 M5.4 11.2 C10 10.6 16 10.6 18.6 14.4 C19.6 15.8 19.4 17.4 19 18.4"
            Icon.REDO -> "M15 7.5 L19 11.2 L14.8 14.8 M18.6 11.2 C14 10.6 8 10.6 5.4 14.4 C4.4 15.8 4.6 17.4 5 18.4"
            Icon.PEN -> "M4.5 19.6 L5.4 15.2 L15.6 5 C16.6 4 18 4 19 5 C20 6 20 7.4 19 8.4 L8.8 18.6 Z M13.8 6.8 L17.2 10.2"
            Icon.MARKER -> "M7 19.4 L9.6 18.8 L8.6 16.6 Z M9.4 16.4 L17.4 8.4 L15.4 6.4 L7.4 14.4 Z M15.8 6 L17.2 4.6 C18 3.8 19.2 3.8 20 4.6 C20.8 5.4 20.8 6.6 20 7.4 L18.4 9"
            Icon.HIGHLIGHTER -> "M6.4 13.6 L13.4 6.6 L18 11.2 L11 18.2 L7 18.4 Z M4.5 20.6 L10.5 20.6 M13.4 6.6 L15.4 4.8"
            Icon.ERASER -> "M5.5 15.4 L13.6 6.6 C14.4 5.8 15.8 5.8 16.6 6.6 L19 9 C19.8 9.8 19.8 11.2 19 12 L13 18.6 L8.4 18.6 Z M9.6 11 L15 16.4 M8.4 20.4 L20 20.4"
            Icon.TEXT -> "M5.5 6.2 C10 5.8 14.2 6.2 18.6 5.9 M12.1 6 C11.9 10.4 12.2 14.6 12 19 M9.4 19 L14.6 19.1"
            Icon.PAN -> "M12 3.8 L12 20.2 M3.8 12 L20.2 12 M9 6.8 L12 3.8 L15 6.8 M9 17.2 L12 20.2 L15 17.2 M6.8 9 L3.8 12 L6.8 15 M17.2 9 L20.2 12 L17.2 15"
            Icon.TRASH -> "M5 7 L19 7.2 M9.5 7 C9.4 5.6 10.4 4.6 12 4.6 C13.6 4.6 14.6 5.6 14.5 7 M6.8 7.4 L7.6 19 C7.6 19.6 8.2 20 8.8 20 L15.2 20 C15.8 20 16.4 19.6 16.4 19 L17.2 7.4 M10.4 10.6 L10.6 16.6 M13.6 10.6 L13.4 16.6"
            Icon.INFINITE -> "M12 12 C10 8.6 5.6 8.4 5 12 C4.4 15.6 9 15.6 12 12 C15 8.4 19.6 8.4 19 12 C18.4 15.6 14 15.6 12 12 Z"
            Icon.PAGE -> "M6.5 4.4 L14.6 4.4 L18 7.8 L17.8 19.6 L6.4 19.7 Z M14.4 4.6 L14.6 8 L17.8 8"
            Icon.PREV -> "M14.5 5.5 C12 8.4 10 10.2 8.2 12.1 C10 13.9 12 15.8 14.6 18.6"
            Icon.NEXT -> "M9.5 5.5 C12 8.4 14 10.2 15.8 12.1 C14 13.9 12 15.8 9.4 18.6"
            Icon.LOCATE -> "M12 4 C16.6 3.8 20.2 7.4 20 12 C19.8 16.6 16.2 20.2 11.8 20 C7.4 19.8 3.8 16.4 4 11.8 C4.2 7.6 7.8 4.2 12 4 Z M12 1.8 L12 6 M12 18 L12 22.2 M1.8 12 L6 12 M18 12 L22.2 12"
            Icon.SETTINGS -> "M9.8 4.9 L10.3 2.3 L13.7 2.3 L14.2 4.9 L15.5 5.5 L17.6 4.0 L20.0 6.4 L18.5 8.5 L19.1 9.8 L21.7 10.3 L21.7 13.7 L19.1 14.2 L18.5 15.5 L20.0 17.6 L17.6 20.0 L15.5 18.5 L14.2 19.1 L13.7 21.7 L10.3 21.7 L9.8 19.1 L8.5 18.5 L6.4 20.0 L4.0 17.6 L5.5 15.5 L4.9 14.2 L2.3 13.7 L2.3 10.3 L4.9 9.8 L5.5 8.5 L4.0 6.4 L6.4 4.0 L8.5 5.5 Z M15 12 C15 13.66 13.66 15 12 15 C10.34 15 9 13.66 9 12 C9 10.34 10.34 9 12 9 C13.66 9 15 10.34 15 12 Z"
            Icon.PAPER -> "M4.5 4.5 L19.5 4.5 L19.5 19.5 L4.5 19.5 Z M12 4.5 L12 19.5 M4.5 12 L19.5 12"
            Icon.EXPORT -> "M12 15 L12 3.8 M7.8 8 L12 3.8 L16.2 8 M5 12.5 L5 19.6 L19 19.6 L19 12.5"
            Icon.FOLDER -> "M3.8 6.4 L9.4 6.2 L11.4 8.4 L20.2 8.4 L20 18.8 L4 19 Z M3.9 10.6 L20.1 10.6"
            Icon.VAULT -> "M7 18.4 C4.4 18.4 2.9 16.6 3.1 14.5 C3.3 12.7 4.7 11.5 6.5 11.7 C7.1 8.6 9.4 6.7 12.3 6.7 C15.4 6.7 17.3 8.9 17.7 11.6 C19.9 11.6 21.2 13.3 21 15.3 C20.8 17.2 19.3 18.4 17 18.4 Z M12 17.4 L12 10.6 M9.1 13.5 L12 10.6 L14.9 13.5"
            Icon.MORE -> "M5.2 12 L5.3 12 M12 12 L12.1 12 M18.7 12 L18.8 12"
        }
    }

    private fun parseSvgPath(d: String): Path {
        val path = Path()
        val n = d.length
        var i = 0

        var currentX = 0f
        var currentY = 0f
        var startX = 0f
        var startY = 0f
        var lastCmd = ' '

        fun nextFloat(): Float {
            while (i < n && (d[i].isWhitespace() || d[i] == ',')) {
                i++
            }
            val start = i
            if (i < n && (d[i] == '+' || d[i] == '-')) {
                i++
            }
            var hasDot = false
            while (i < n) {
                val c = d[i]
                if (c in '0'..'9') {
                    i++
                } else if (c == '.' && !hasDot) {
                    hasDot = true
                    i++
                } else {
                    break
                }
            }
            return d.substring(start, i).toFloat()
        }

        fun hasMoreNumbers(): Boolean {
            var j = i
            while (j < n && (d[j].isWhitespace() || d[j] == ',')) {
                j++
            }
            if (j >= n) return false
            val c = d[j]
            return c in '0'..'9' || c == '-' || c == '+' || c == '.'
        }

        while (i < n) {
            val c = d[i]
            if (c.isWhitespace() || c == ',') {
                i++
                continue
            }
            val cmd = if (c.isLetter()) {
                i++
                lastCmd = c
                c
            } else {
                lastCmd
            }

            when (cmd) {
                'M' -> {
                    val x = nextFloat()
                    val y = nextFloat()
                    path.moveTo(x, y)
                    currentX = x
                    currentY = y
                    startX = x
                    startY = y
                    lastCmd = 'L'
                }
                'm' -> {
                    val x = currentX + nextFloat()
                    val y = currentY + nextFloat()
                    path.moveTo(x, y)
                    currentX = x
                    currentY = y
                    startX = x
                    startY = y
                    lastCmd = 'l'
                }
                'L' -> {
                    val x = nextFloat()
                    val y = nextFloat()
                    path.lineTo(x, y)
                    currentX = x
                    currentY = y
                }
                'l' -> {
                    val x = currentX + nextFloat()
                    val y = currentY + nextFloat()
                    path.lineTo(x, y)
                    currentX = x
                    currentY = y
                }
                'H' -> {
                    val x = nextFloat()
                    path.lineTo(x, currentY)
                    currentX = x
                }
                'h' -> {
                    val x = currentX + nextFloat()
                    path.lineTo(x, currentY)
                    currentX = x
                }
                'V' -> {
                    val y = nextFloat()
                    path.lineTo(currentX, y)
                    currentY = y
                }
                'v' -> {
                    val y = currentY + nextFloat()
                    path.lineTo(currentX, y)
                    currentY = y
                }
                'C' -> {
                    val x1 = nextFloat()
                    val y1 = nextFloat()
                    val x2 = nextFloat()
                    val y2 = nextFloat()
                    val x = nextFloat()
                    val y = nextFloat()
                    path.cubicTo(x1, y1, x2, y2, x, y)
                    currentX = x
                    currentY = y
                }
                'c' -> {
                    val x1 = currentX + nextFloat()
                    val y1 = currentY + nextFloat()
                    val x2 = currentX + nextFloat()
                    val y2 = currentY + nextFloat()
                    val x = currentX + nextFloat()
                    val y = currentY + nextFloat()
                    path.cubicTo(x1, y1, x2, y2, x, y)
                    currentX = x
                    currentY = y
                }
                'Z', 'z' -> {
                    path.close()
                    currentX = startX
                    currentY = startY
                }
            }

            if (cmd != 'Z' && cmd != 'z' && !hasMoreNumbers()) {
                lastCmd = ' '
            }
        }
        return path
    }

    fun draw(canvas: Canvas, icon: Icon, bounds: RectF, paint: Paint) {
        val oldStyle = paint.style
        val oldCap = paint.strokeCap
        val oldJoin = paint.strokeJoin
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        val srcPath = cachedPaths[icon.ordinal]
        matrix.reset()
        matrix.postScale(bounds.width() / 24f, bounds.height() / 24f)
        matrix.postTranslate(bounds.left, bounds.top)

        drawPath.reset()
        srcPath.transform(matrix, drawPath)
        canvas.drawPath(drawPath, paint)

        paint.style = oldStyle
        paint.strokeCap = oldCap
        paint.strokeJoin = oldJoin
    }
}

class IconView(context: Context) : View(context) {
    var icon: Icon = Icon.PEN
        set(value) {
            field = value
            invalidate()
        }

    var tint: Int = Palette.INK
        set(value) {
            field = value
            paint.color = value
            invalidate()
        }

    var strokeWidthDp: Float = 2f
        set(value) {
            field = value
            paint.strokeWidth = value * resources.displayMetrics.density
            invalidate()
        }

    private val minSizePx: Int = (44f * resources.displayMetrics.density).toInt()
    private val iconBounds = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = tint
    }

    var showSelection: Boolean = true

    private val selectedDrawable = SketchDrawable(
        fill = Palette.CARD,
        stroke = Palette.INK,
        strokeWidthDp = 2f,
        shadowOffsetDp = 0f,
        cornerDp = 8f
    )

    init {
        isClickable = true
        isFocusable = true
    }

    override fun setSelected(selected: Boolean) {
        val changed = isSelected != selected
        super.setSelected(selected)
        if (changed) {
            if (selected) {
                selectedDrawable.reseed()
            }
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val wSize = MeasureSpec.getSize(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val hSize = MeasureSpec.getSize(heightMeasureSpec)

        val w = when (wMode) {
            MeasureSpec.EXACTLY -> wSize
            MeasureSpec.AT_MOST -> Math.min(wSize, minSizePx).coerceAtLeast(minSizePx)
            else -> minSizePx
        }

        val h = when (hMode) {
            MeasureSpec.EXACTLY -> hSize
            MeasureSpec.AT_MOST -> Math.min(hSize, minSizePx).coerceAtLeast(minSizePx)
            else -> minSizePx
        }

        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val padLeft = paddingLeft.toFloat()
        val padTop = paddingTop.toFloat()
        val padRight = paddingRight.toFloat()
        val padBottom = paddingBottom.toFloat()

        val availW = (w - padLeft - padRight).coerceAtLeast(0f)
        val availH = (h - padTop - padBottom).coerceAtLeast(0f)
        val side = Math.min(availW, availH)

        val cx = padLeft + availW * 0.5f
        val cy = padTop + availH * 0.5f
        val half = side * 0.5f
        iconBounds.set(cx - half, cy - half, cx + half, cy + half)

        selectedDrawable.setBounds(0, 0, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isSelected && showSelection) {
            selectedDrawable.draw(canvas)
        }
        paint.color = tint
        Icons.draw(canvas, icon, iconBounds, paint)
    }
}
