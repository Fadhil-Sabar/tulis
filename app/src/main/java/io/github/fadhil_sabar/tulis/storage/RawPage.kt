package io.github.fadhil_sabar.tulis.storage

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

sealed class RawStrokeData {
    class V1Encoded(val bytes: ByteArray) : RawStrokeData() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is V1Encoded) return false
            return bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    class Points(
        val toolType: Int,
        val strokeUnitLengthCm: Float,
        val x: FloatArray,
        val y: FloatArray,
        val elapsedTimeMillis: LongArray,
        val pressure: FloatArray,
        val tiltRadians: FloatArray,
        val orientationRadians: FloatArray
    ) : RawStrokeData() {
        val count: Int get() = x.size

        fun repeatsPrevious(i: Int): Boolean =
            i > 0 && x[i] == x[i - 1] && y[i] == y[i - 1] && elapsedTimeMillis[i] == elapsedTimeMillis[i - 1]

        init {
            val c = x.size
            require(
                y.size == c &&
                    elapsedTimeMillis.size == c &&
                    pressure.size == c &&
                    tiltRadians.size == c &&
                    orientationRadians.size == c
            ) {
                "Point arrays must have identical length"
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Points) return false
            return toolType == other.toolType &&
                strokeUnitLengthCm == other.strokeUnitLengthCm &&
                x.contentEquals(other.x) &&
                y.contentEquals(other.y) &&
                elapsedTimeMillis.contentEquals(other.elapsedTimeMillis) &&
                pressure.contentEquals(other.pressure) &&
                tiltRadians.contentEquals(other.tiltRadians) &&
                orientationRadians.contentEquals(other.orientationRadians)
        }

        override fun hashCode(): Int {
            var result = toolType
            result = 31 * result + strokeUnitLengthCm.hashCode()
            result = 31 * result + x.contentHashCode()
            result = 31 * result + y.contentHashCode()
            result = 31 * result + elapsedTimeMillis.contentHashCode()
            result = 31 * result + pressure.contentHashCode()
            result = 31 * result + tiltRadians.contentHashCode()
            result = 31 * result + orientationRadians.contentHashCode()
            return result
        }
    }
}

data class RawStroke(
    val brushOrdinal: Int,
    val colorArgb: Int,
    val sizePx: Float,
    val matrix: FloatArray,
    val data: RawStrokeData
) {
    init {
        require(matrix.size == 6) { "Matrix must have 6 elements" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawStroke) return false
        return brushOrdinal == other.brushOrdinal &&
            colorArgb == other.colorArgb &&
            sizePx == other.sizePx &&
            matrix.contentEquals(other.matrix) &&
            data == other.data
    }

    override fun hashCode(): Int {
        var result = brushOrdinal
        result = 31 * result + colorArgb
        result = 31 * result + sizePx.hashCode()
        result = 31 * result + matrix.contentHashCode()
        result = 31 * result + data.hashCode()
        return result
    }
}

data class RawText(
    val x: Float,
    val y: Float,
    val sizeWorld: Float,
    val colorArgb: Int,
    val widthWorld: Float,
    val text: String
)

data class RawImage(
    val name: String,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val rotationDeg: Float
)

data class RawPage(
    val strokes: List<RawStroke> = emptyList(),
    val texts: List<RawText> = emptyList(),
    val images: List<RawImage> = emptyList()
)

object RawPageCodec {
    const val MAGIC = 0x494E4B50
    const val VERSION_1: Byte = 1
    const val VERSION_2: Byte = 2
    const val VERSION_3: Byte = 3

    const val NO_STROKE_UNIT_LENGTH = 0.0f
    const val NO_PRESSURE = -1.0f
    const val NO_TILT = -1.0f
    const val NO_ORIENTATION = -1.0f

    const val TOOL_TYPE_UNKNOWN: Byte = 0
    const val TOOL_TYPE_MOUSE: Byte = 1
    const val TOOL_TYPE_TOUCH: Byte = 2
    const val TOOL_TYPE_STYLUS: Byte = 3

    const val MAX_STROKES = 1_000_000
    const val MAX_POINTS_PER_STROKE = 1_000_000
    const val MAX_INPUT_LEN = 16 * 1024 * 1024
    const val MAX_TEXT_LEN = 10 * 1024 * 1024
    const val MAX_TEXTS = 100_000
    const val MAX_IMAGES = 10_000
    val IMAGE_NAME = Regex("^img_[0-9a-f]{40}\\.(webp|png|jpg)$")

    fun write(out: OutputStream, page: RawPage) {
        val dos = DataOutputStream(out)
        val images = page.images
        val version = if (images.isEmpty()) VERSION_2 else VERSION_3
        dos.writeInt(MAGIC)
        dos.writeByte(version.toInt())

        val strokes = page.strokes
        dos.writeInt(strokes.size)
        for (stroke in strokes) {
            val points = stroke.data as? RawStrokeData.Points
                ?: throw IllegalArgumentException("Cannot write v1 stroke data in v2 format")
            dos.writeByte(stroke.brushOrdinal)
            dos.writeInt(stroke.colorArgb)
            dos.writeFloat(stroke.sizePx)
            val matrix = stroke.matrix
            for (m in 0 until 6) {
                dos.writeFloat(matrix[m])
            }
            dos.writeByte(points.toolType)
            dos.writeFloat(points.strokeUnitLengthCm)
            val ptCount = points.count
            if (ptCount < 0 || ptCount > MAX_POINTS_PER_STROKE) {
                throw IOException("Invalid point count: $ptCount")
            }
            dos.writeInt(ptCount)
            for (p in 0 until ptCount) {
                dos.writeFloat(points.x[p])
                dos.writeFloat(points.y[p])
                dos.writeLong(points.elapsedTimeMillis[p])
                dos.writeFloat(points.pressure[p])
                dos.writeFloat(points.tiltRadians[p])
                dos.writeFloat(points.orientationRadians[p])
            }
        }

        val texts = page.texts
        dos.writeInt(texts.size)
        for (item in texts) {
            dos.writeFloat(item.x)
            dos.writeFloat(item.y)
            dos.writeFloat(item.sizeWorld)
            dos.writeInt(item.colorArgb)
            dos.writeFloat(item.widthWorld)
            val utf8 = item.text.toByteArray(StandardCharsets.UTF_8)
            dos.writeInt(utf8.size)
            dos.write(utf8)
        }

        if (images.isNotEmpty()) {
            if (images.size > MAX_IMAGES) {
                throw IOException("Invalid image count: ${images.size}")
            }
            dos.writeInt(images.size)
            for (img in images) {
                if (!img.cx.isFinite() || !img.cy.isFinite() || !img.rotationDeg.isFinite()) {
                    throw IOException("Invalid image coordinates")
                }
                if (!img.w.isFinite() || img.w <= 0f || !img.h.isFinite() || img.h <= 0f) {
                    throw IOException("Invalid image dimensions")
                }
                if (!IMAGE_NAME.matches(img.name)) {
                    throw IOException("Invalid image name: ${img.name}")
                }
                val nameBytes = img.name.toByteArray(StandardCharsets.US_ASCII)
                if (nameBytes.isEmpty() || nameBytes.size > 64) {
                    throw IOException("Invalid image name length: ${nameBytes.size}")
                }
                dos.writeFloat(img.cx)
                dos.writeFloat(img.cy)
                dos.writeFloat(img.w)
                dos.writeFloat(img.h)
                dos.writeFloat(img.rotationDeg)
                dos.writeInt(nameBytes.size)
                dos.write(nameBytes)
            }
        }
        dos.flush()
    }

    fun read(input: InputStream): RawPage {
        val dis = DataInputStream(input)
        val magic = dis.readInt()
        if (magic != MAGIC) {
            throw IOException("Invalid magic header: $magic")
        }
        val version = dis.readByte()
        if (version != VERSION_1 && version != VERSION_2 && version != VERSION_3) {
            throw IOException("Unsupported version: $version")
        }

        val strokeCount = dis.readInt()
        if (strokeCount < 0 || strokeCount > MAX_STROKES) {
            throw IOException("Invalid stroke count: $strokeCount")
        }

        val strokes = ArrayList<RawStroke>(strokeCount)
        if (version == VERSION_1) {
            for (i in 0 until strokeCount) {
                val kindOrdinal = dis.readByte().toInt()
                val colorArgb = dis.readInt()
                val sizePx = dis.readFloat()
                val matrix = FloatArray(6)
                for (m in 0 until 6) {
                    matrix[m] = dis.readFloat()
                }
                val inputLen = dis.readInt()
                if (inputLen < 0 || inputLen > MAX_INPUT_LEN) {
                    throw IOException("Invalid inputLen: $inputLen")
                }
                val inputs = ByteArray(inputLen)
                dis.readFully(inputs)
                strokes.add(
                    RawStroke(
                        brushOrdinal = kindOrdinal,
                        colorArgb = colorArgb,
                        sizePx = sizePx,
                        matrix = matrix,
                        data = RawStrokeData.V1Encoded(inputs)
                    )
                )
            }
        } else {
            for (i in 0 until strokeCount) {
                val kindOrdinal = dis.readByte().toInt()
                val colorArgb = dis.readInt()
                val sizePx = dis.readFloat()
                val matrix = FloatArray(6)
                for (m in 0 until 6) {
                    matrix[m] = dis.readFloat()
                }
                val toolType = dis.readByte().toInt()
                val strokeUnitLengthCm = dis.readFloat()
                val pointCount = dis.readInt()
                if (pointCount < 0 || pointCount > MAX_POINTS_PER_STROKE) {
                    throw IOException("Invalid point count: $pointCount")
                }
                val xs = FloatArray(pointCount)
                val ys = FloatArray(pointCount)
                val times = LongArray(pointCount)
                val pressures = FloatArray(pointCount)
                val tilts = FloatArray(pointCount)
                val orientations = FloatArray(pointCount)
                for (p in 0 until pointCount) {
                    xs[p] = dis.readFloat()
                    ys[p] = dis.readFloat()
                    times[p] = dis.readLong()
                    pressures[p] = dis.readFloat()
                    tilts[p] = dis.readFloat()
                    orientations[p] = dis.readFloat()
                }
                strokes.add(
                    RawStroke(
                        brushOrdinal = kindOrdinal,
                        colorArgb = colorArgb,
                        sizePx = sizePx,
                        matrix = matrix,
                        data = RawStrokeData.Points(
                            toolType = toolType,
                            strokeUnitLengthCm = strokeUnitLengthCm,
                            x = xs,
                            y = ys,
                            elapsedTimeMillis = times,
                            pressure = pressures,
                            tiltRadians = tilts,
                            orientationRadians = orientations
                        )
                    )
                )
            }
        }

        val textCount = dis.readInt()
        if (textCount < 0 || textCount > MAX_TEXTS) {
            throw IOException("Invalid text count: $textCount")
        }
        val texts = ArrayList<RawText>(textCount)
        for (i in 0 until textCount) {
            val x = dis.readFloat()
            val y = dis.readFloat()
            val sizeWorld = dis.readFloat()
            val colorArgb = dis.readInt()
            val widthWorld = dis.readFloat()
            val utf8Len = dis.readInt()
            if (utf8Len < 0 || utf8Len > MAX_TEXT_LEN) {
                throw IOException("Invalid text length: $utf8Len")
            }
            val bytes = ByteArray(utf8Len)
            dis.readFully(bytes)
            val text = String(bytes, StandardCharsets.UTF_8)
            texts.add(RawText(x, y, sizeWorld, colorArgb, widthWorld, text))
        }

        val images = if (version == VERSION_3) {
            val imageCount = dis.readInt()
            if (imageCount < 0 || imageCount > MAX_IMAGES) {
                throw IOException("Invalid image count: $imageCount")
            }
            val list = ArrayList<RawImage>(imageCount)
            for (i in 0 until imageCount) {
                val cx = dis.readFloat()
                val cy = dis.readFloat()
                val w = dis.readFloat()
                val h = dis.readFloat()
                val rotationDeg = dis.readFloat()
                if (!cx.isFinite() || !cy.isFinite() || !rotationDeg.isFinite()) {
                    throw IOException("Invalid image coordinates")
                }
                if (!w.isFinite() || w <= 0f || !h.isFinite() || h <= 0f) {
                    throw IOException("Invalid image dimensions")
                }
                val nameLen = dis.readInt()
                if (nameLen < 1 || nameLen > 64) {
                    throw IOException("Invalid image name length: $nameLen")
                }
                val nameBytes = ByteArray(nameLen)
                dis.readFully(nameBytes)
                val name = String(nameBytes, StandardCharsets.US_ASCII)
                if (!IMAGE_NAME.matches(name)) {
                    throw IOException("Invalid image name: $name")
                }
                list.add(RawImage(name, cx, cy, w, h, rotationDeg))
            }
            list
        } else {
            emptyList()
        }

        return RawPage(strokes, texts, images)
    }
}
