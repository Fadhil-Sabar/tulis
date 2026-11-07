package io.github.fadhil_sabar.tulis.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import io.github.fadhil_sabar.tulis.model.NoteMode
import io.github.fadhil_sabar.tulis.model.PageContent
import io.github.fadhil_sabar.tulis.model.PageOrientation
import io.github.fadhil_sabar.tulis.model.PaperStyle
import io.github.fadhil_sabar.tulis.model.pageH
import io.github.fadhil_sabar.tulis.model.pageW
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class ExportFormat { PDF, PNG }

class ExportOptions(
    val format: ExportFormat,
    val allPages: Boolean,
    val withPaper: Boolean,
    val pxPerUnit: Float = 2f
)

class ExportJob(
    val notebookId: String,
    val mode: NoteMode,
    val orientation: PageOrientation,
    val paperStyle: PaperStyle,
    val paperSpacing: Float,
    val options: ExportOptions,
    val pageIndices: List<Int>,
    val snapshots: Map<Int, PageContent>,
    val infinitePage: PageContent?
)

class ExportCancelled : Exception()

object Exporter {
    private const val PDF_PT_PER_UNIT = 0.595f
    private const val PDF_MAX_PT = 14400f
    private const val PNG_MAX_PIXELS = 16_000_000f
    private const val PNG_MAX_SIDE = 8192f
    private const val INFINITE_MARGIN = 40f

    fun run(
        job: ExportJob,
        out: OutputStream,
        cancel: AtomicBoolean,
        loadPage: (Int) -> PageContent,
        progress: (Int, Int) -> Unit
    ) {
        val renderer = PageRenderer(job.paperStyle, job.paperSpacing, job.options.withPaper)
        when (job.options.format) {
            ExportFormat.PDF -> writePdf(job, renderer, out, cancel, loadPage, progress)
            ExportFormat.PNG -> writePng(job, renderer, out, cancel, loadPage)
        }
    }

    private fun checkCancel(cancel: AtomicBoolean) {
        if (cancel.get()) throw ExportCancelled()
    }

    private fun infiniteBounds(renderer: PageRenderer, page: PageContent): RectF? {
        val r = RectF()
        if (!renderer.contentBounds(page, r)) return null
        r.inset(-INFINITE_MARGIN, -INFINITE_MARGIN)
        return r
    }

    private fun writePdf(
        job: ExportJob,
        renderer: PageRenderer,
        out: OutputStream,
        cancel: AtomicBoolean,
        loadPage: (Int) -> PageContent,
        progress: (Int, Int) -> Unit
    ) {
        val doc = PdfDocument()
        try {
            if (job.mode == NoteMode.INFINITE) {
                val page = job.infinitePage ?: PageContent()
                val bounds = infiniteBounds(renderer, page) ?: throw IllegalStateException("Tidak ada konten untuk diekspor")
                val maxSide = max(bounds.width(), bounds.height())
                val scale = min(PDF_PT_PER_UNIT, PDF_MAX_PT / maxSide)
                val wPt = max(1, ceilInt(bounds.width() * scale))
                val hPt = max(1, ceilInt(bounds.height() * scale))
                val pdfPage = doc.startPage(PdfDocument.PageInfo.Builder(wPt, hPt, 1).create())
                val m = Matrix().apply {
                    setTranslate(-bounds.left, -bounds.top)
                    postScale(scale, scale)
                }
                renderer.renderInfinite(pdfPage.canvas, page, bounds, m)
                doc.finishPage(pdfPage)
                progress(1, 1)
            } else {
                val pw = job.orientation.pageW
                val ph = job.orientation.pageH
                val wPt = Math.round(pw * PDF_PT_PER_UNIT)
                val hPt = Math.round(ph * PDF_PT_PER_UNIT)
                val m = Matrix().apply { setScale(PDF_PT_PER_UNIT, PDF_PT_PER_UNIT) }
                val total = job.pageIndices.size
                for ((n, idx) in job.pageIndices.withIndex()) {
                    checkCancel(cancel)
                    progress(n + 1, total)
                    val content = job.snapshots[idx] ?: loadPage(idx)
                    val pdfPage = doc.startPage(PdfDocument.PageInfo.Builder(wPt, hPt, n + 1).create())
                    renderer.renderPaged(pdfPage.canvas, content, pw, ph, m)
                    doc.finishPage(pdfPage)
                }
            }
            checkCancel(cancel)
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private fun writePng(
        job: ExportJob,
        renderer: PageRenderer,
        out: OutputStream,
        cancel: AtomicBoolean,
        loadPage: (Int) -> PageContent
    ) {
        val world: RectF
        val page: PageContent
        if (job.mode == NoteMode.INFINITE) {
            page = job.infinitePage ?: PageContent()
            world = infiniteBounds(renderer, page) ?: throw IllegalStateException("Tidak ada konten untuk diekspor")
        } else {
            val idx = job.pageIndices.first()
            page = job.snapshots[idx] ?: loadPage(idx)
            world = RectF(0f, 0f, job.orientation.pageW, job.orientation.pageH)
        }
        checkCancel(cancel)

        var scale = job.options.pxPerUnit
        scale = min(scale, sqrt(PNG_MAX_PIXELS / (world.width() * world.height())))
        scale = min(scale, PNG_MAX_SIDE / max(world.width(), world.height()))

        var bmp: Bitmap? = null
        while (bmp == null) {
            val w = max(1, ceilInt(world.width() * scale))
            val h = max(1, ceilInt(world.height() * scale))
            try {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                if (scale < 0.25f) throw e
                scale /= 2f
            }
        }
        try {
            val m = Matrix().apply {
                setTranslate(-world.left, -world.top)
                postScale(scale, scale)
            }
            val canvas = Canvas(bmp)
            if (job.mode == NoteMode.INFINITE) {
                renderer.renderInfinite(canvas, page, world, m)
            } else {
                renderer.renderPaged(canvas, page, world.width(), world.height(), m)
            }
            checkCancel(cancel)
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw java.io.IOException("Gagal menulis PNG")
            }
        } finally {
            bmp.recycle()
        }
    }
}
