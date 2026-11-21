package io.github.fadhil_sabar.tulis.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PageMigrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun header(version: Byte, payload: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use {
            it.writeInt(RawPageCodec.MAGIC)
            it.writeByte(version.toInt())
            it.writeInt(payload)
        }
        return bos.toByteArray()
    }

    private fun payloadOf(file: File): Int = file.readBytes().let {
        java.io.DataInputStream(it.inputStream()).run { readInt(); readByte(); readInt() }
    }

    @Test
    fun concurrentMigrationKeepsEveryPageAndConvertsOnce() {
        val dir = tmp.newFolder("nb")
        val pageCount = 20
        for (i in 0 until pageCount) File(dir, "page_$i.bin").writeBytes(header(RawPageCodec.VERSION_1, i))
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val threads = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                PageMigration.migrateDir(dir) { f ->
                    calls.getOrPut(f.name) { AtomicInteger() }.incrementAndGet()
                    Thread.sleep(2)
                    header(RawPageCodec.VERSION_2, payloadOf(f))
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))

        for (i in 0 until pageCount) {
            val f = File(dir, "page_$i.bin")
            assertTrue("page_$i hilang", f.exists())
            assertFalse(PageMigration.isV1Page(f))
            assertEquals(i, payloadOf(f))
            assertEquals(1, calls["page_$i.bin"]?.get())
        }
        assertEquals(pageCount, dir.listFiles()!!.size)
    }

    @Test
    fun failedConversionLeavesV1PageUntouched() {
        val dir = tmp.newFolder("nb")
        val original = header(RawPageCodec.VERSION_1, 7)
        val page = File(dir, "page_0.bin").apply { writeBytes(original) }
        PageMigration.migrateDir(dir) { null }
        PageMigration.migrateDir(dir) { throw IllegalStateException("rusak") }
        assertTrue(original.contentEquals(page.readBytes()))
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test
    fun repeatsPreviousDetectsSamePositionAndTime() {
        val p = RawStrokeData.Points(
            toolType = 1,
            strokeUnitLengthCm = 0.01f,
            x = floatArrayOf(1f, 1f, 1f, 2f),
            y = floatArrayOf(5f, 5f, 5f, 5f),
            elapsedTimeMillis = longArrayOf(100, 100, 110, 110),
            pressure = FloatArray(4) { -1f },
            tiltRadians = FloatArray(4) { -1f },
            orientationRadians = FloatArray(4) { -1f }
        )
        assertFalse(p.repeatsPrevious(0))
        assertTrue(p.repeatsPrevious(1))
        assertFalse(p.repeatsPrevious(2))
        assertFalse(p.repeatsPrevious(3))
    }
}
