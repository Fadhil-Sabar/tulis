package io.github.fadhil_sabar.tulis.storage

import android.graphics.Matrix
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import io.github.fadhil_sabar.tulis.ink.InkBridge
import io.github.fadhil_sabar.tulis.ink.StrokeItem
import io.github.fadhil_sabar.tulis.model.BrushKind
import io.github.fadhil_sabar.tulis.model.PageContent
import io.github.fadhil_sabar.tulis.model.TextItem
import java.io.InputStream
import java.io.OutputStream

object PageCodec {
    const val MAGIC = RawPageCodec.MAGIC
    const val VERSION: Byte = RawPageCodec.VERSION_2

    fun mapToolTypeToByte(toolType: InputToolType?): Byte {
        return when (toolType) {
            InputToolType.MOUSE -> RawPageCodec.TOOL_TYPE_MOUSE
            InputToolType.TOUCH -> RawPageCodec.TOOL_TYPE_TOUCH
            InputToolType.STYLUS -> RawPageCodec.TOOL_TYPE_STYLUS
            else -> RawPageCodec.TOOL_TYPE_UNKNOWN
        }
    }

    fun mapByteToToolType(byte: Int): InputToolType {
        return when (byte.toByte()) {
            RawPageCodec.TOOL_TYPE_MOUSE -> InputToolType.MOUSE
            RawPageCodec.TOOL_TYPE_TOUCH -> InputToolType.TOUCH
            RawPageCodec.TOOL_TYPE_STYLUS -> InputToolType.STYLUS
            else -> InputToolType.UNKNOWN
        }
    }

    fun write(out: OutputStream, page: PageContent) {
        val strokes = page.strokes
        val rawStrokes = ArrayList<RawStroke>(strokes.size)
        val matrixValues = FloatArray(9)
        val scratchInput = StrokeInput()

        for (item in strokes) {
            val inputs = item.stroke.inputs
            val count = inputs.size
            val xs = FloatArray(count)
            val ys = FloatArray(count)
            val times = LongArray(count)
            val pressures = FloatArray(count)
            val tilts = FloatArray(count)
            val orientations = FloatArray(count)

            for (i in 0 until count) {
                inputs.populate(i, scratchInput)
                xs[i] = scratchInput.x
                ys[i] = scratchInput.y
                times[i] = scratchInput.elapsedTimeMillis
                pressures[i] = scratchInput.pressure
                tilts[i] = scratchInput.tiltRadians
                orientations[i] = scratchInput.orientationRadians
            }

            item.strokeToWorld.getValues(matrixValues)
            val matrix6 = floatArrayOf(
                matrixValues[Matrix.MSCALE_X],
                matrixValues[Matrix.MSKEW_X],
                matrixValues[Matrix.MTRANS_X],
                matrixValues[Matrix.MSKEW_Y],
                matrixValues[Matrix.MSCALE_Y],
                matrixValues[Matrix.MTRANS_Y]
            )

            val toolTypeByte = mapToolTypeToByte(inputs.getToolType())
            val strokeUnitLength = inputs.getStrokeUnitLengthCm()
            val points = RawStrokeData.Points(
                toolType = toolTypeByte.toInt(),
                strokeUnitLengthCm = strokeUnitLength,
                x = xs,
                y = ys,
                elapsedTimeMillis = times,
                pressure = pressures,
                tiltRadians = tilts,
                orientationRadians = orientations
            )
            rawStrokes.add(
                RawStroke(
                    brushOrdinal = item.brushKind.ordinal,
                    colorArgb = item.colorArgb,
                    sizePx = item.sizePx,
                    matrix = matrix6,
                    data = points
                )
            )
        }

        val rawTexts = page.texts.map {
            RawText(
                x = it.x,
                y = it.y,
                sizeWorld = it.sizeWorld,
                colorArgb = it.colorArgb,
                widthWorld = it.widthWorld,
                text = it.text
            )
        }

        RawPageCodec.write(out, RawPage(rawStrokes, rawTexts))
    }

    fun read(input: InputStream): PageContent {
        val rawPage = RawPageCodec.read(input)
        val page = PageContent()
        val brushValues = BrushKind.values()
        val matrixValues = FloatArray(9)

        for (rawStroke in rawPage.strokes) {
            if (rawStroke.brushOrdinal !in brushValues.indices) continue
            val kind = brushValues[rawStroke.brushOrdinal]
            val matrix = Matrix()
            matrixValues[Matrix.MSCALE_X] = rawStroke.matrix[0]
            matrixValues[Matrix.MSKEW_X] = rawStroke.matrix[1]
            matrixValues[Matrix.MTRANS_X] = rawStroke.matrix[2]
            matrixValues[Matrix.MSKEW_Y] = rawStroke.matrix[3]
            matrixValues[Matrix.MSCALE_Y] = rawStroke.matrix[4]
            matrixValues[Matrix.MTRANS_Y] = rawStroke.matrix[5]
            matrixValues[Matrix.MPERSP_0] = 0f
            matrixValues[Matrix.MPERSP_1] = 0f
            matrixValues[Matrix.MPERSP_2] = 1f
            matrix.setValues(matrixValues)

            when (val data = rawStroke.data) {
                is RawStrokeData.V1Encoded -> {
                    val item = try {
                        InkBridge.decodeStroke(kind, rawStroke.colorArgb, rawStroke.sizePx, matrix, data.bytes)
                    } catch (t: Throwable) {
                        null
                    }
                    if (item != null) {
                        item.encodedInputs = data.bytes
                        page.strokes.add(item)
                    }
                }
                is RawStrokeData.Points -> {
                    val batch = MutableStrokeInputBatch()
                    val toolType = mapByteToToolType(data.toolType)
                    val count = data.count
                    for (i in 0 until count) {
                        if (data.repeatsPrevious(i)) continue
                        batch.add(
                            toolType,
                            data.x[i],
                            data.y[i],
                            data.elapsedTimeMillis[i],
                            data.strokeUnitLengthCm,
                            data.pressure[i],
                            data.tiltRadians[i],
                            data.orientationRadians[i]
                        )
                    }
                    val item = try {
                        val brush = InkBridge.brushFor(kind, rawStroke.colorArgb, rawStroke.sizePx)
                        val stroke = Stroke(brush, batch)
                        StrokeItem(stroke, kind, rawStroke.colorArgb, rawStroke.sizePx, matrix)
                    } catch (t: Throwable) {
                        null
                    }
                    if (item != null) {
                        page.strokes.add(item)
                    }
                }
            }
        }

        for (rawText in rawPage.texts) {
            page.texts.add(
                TextItem(
                    rawText.x,
                    rawText.y,
                    rawText.sizeWorld,
                    rawText.colorArgb,
                    rawText.widthWorld,
                    rawText.text
                )
            )
        }

        return page
    }
}
