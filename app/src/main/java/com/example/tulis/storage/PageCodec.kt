package com.example.tulis.storage

import android.graphics.Matrix
import com.example.tulis.ink.InkBridge
import com.example.tulis.ink.StrokeItem
import com.example.tulis.model.BrushKind
import com.example.tulis.model.PageContent
import com.example.tulis.model.TextItem
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

object PageCodec {
    private const val MAGIC = 0x494E4B50
    private const val VERSION: Byte = 1
    private const val MAX_INPUT_LEN = 16 * 1024 * 1024

    fun write(out: OutputStream, page: PageContent) {
        val dos = DataOutputStream(out)
        dos.writeInt(MAGIC)
        dos.writeByte(VERSION.toInt())

        val strokes = page.strokes
        dos.writeInt(strokes.size)
        val matrixValues = FloatArray(9)
        for (item in strokes) {
            val encoded = item.encodedInputs ?: InkBridge.encodeInputs(item)
            dos.writeByte(item.brushKind.ordinal)
            dos.writeInt(item.colorArgb)
            dos.writeFloat(item.sizePx)
            item.strokeToWorld.getValues(matrixValues)
            dos.writeFloat(matrixValues[Matrix.MSCALE_X])
            dos.writeFloat(matrixValues[Matrix.MSKEW_X])
            dos.writeFloat(matrixValues[Matrix.MTRANS_X])
            dos.writeFloat(matrixValues[Matrix.MSKEW_Y])
            dos.writeFloat(matrixValues[Matrix.MSCALE_Y])
            dos.writeFloat(matrixValues[Matrix.MTRANS_Y])
            dos.writeInt(encoded.size)
            dos.write(encoded)
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
        dos.flush()
    }

    fun read(input: InputStream): PageContent {
        val dis = DataInputStream(input)
        val magic = dis.readInt()
        if (magic != MAGIC) {
            throw IOException("Invalid magic header")
        }
        val version = dis.readByte()
        if (version != VERSION) {
            throw IOException("Unsupported version: $version")
        }

        val strokeCount = dis.readInt()
        if (strokeCount < 0) {
            throw IOException("Negative stroke count: $strokeCount")
        }

        val brushValues = BrushKind.values()
        val matrixValues = FloatArray(9)
        val page = PageContent()

        for (i in 0 until strokeCount) {
            val kindOrdinal = dis.readByte().toInt()
            val colorArgb = dis.readInt()
            val sizePx = dis.readFloat()
            val a = dis.readFloat()
            val b = dis.readFloat()
            val c = dis.readFloat()
            val d = dis.readFloat()
            val e = dis.readFloat()
            val f = dis.readFloat()
            val inputLen = dis.readInt()

            if (inputLen < 0 || inputLen > MAX_INPUT_LEN) {
                throw IOException("Invalid inputLen: $inputLen")
            }

            val inputs = ByteArray(inputLen)
            dis.readFully(inputs)

            if (kindOrdinal in brushValues.indices) {
                val kind = brushValues[kindOrdinal]
                val matrix = Matrix()
                matrixValues[Matrix.MSCALE_X] = a
                matrixValues[Matrix.MSKEW_X] = b
                matrixValues[Matrix.MTRANS_X] = c
                matrixValues[Matrix.MSKEW_Y] = d
                matrixValues[Matrix.MSCALE_Y] = e
                matrixValues[Matrix.MTRANS_Y] = f
                matrixValues[Matrix.MPERSP_0] = 0f
                matrixValues[Matrix.MPERSP_1] = 0f
                matrixValues[Matrix.MPERSP_2] = 1f
                matrix.setValues(matrixValues)

                val item = try {
                    InkBridge.decodeStroke(kind, colorArgb, sizePx, matrix, inputs)
                } catch (t: Throwable) {
                    null
                }
                if (item != null) {
                    item.encodedInputs = inputs
                    page.strokes.add(item)
                }
            }
        }

        val textCount = dis.readInt()
        if (textCount < 0) {
            throw IOException("Negative text count: $textCount")
        }

        for (i in 0 until textCount) {
            val x = dis.readFloat()
            val y = dis.readFloat()
            val sizeWorld = dis.readFloat()
            val colorArgb = dis.readInt()
            val widthWorld = dis.readFloat()
            val utf8Len = dis.readInt()
            if (utf8Len < 0 || utf8Len > 10 * 1024 * 1024) {
                throw IOException("Invalid text length: $utf8Len")
            }
            val bytes = ByteArray(utf8Len)
            dis.readFully(bytes)
            val text = String(bytes, StandardCharsets.UTF_8)
            page.texts.add(TextItem(x, y, sizeWorld, colorArgb, widthWorld, text))
        }

        return page
    }
}
