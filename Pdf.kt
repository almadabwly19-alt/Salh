package com.bahith.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.googlecode.tesseract.android.TessBaseAPI
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.text.Normalizer

/** عرض صفحات PDF كصور (يُستخدم في القارئ وفي الـ OCR). */
class PdfSource(file: File) : Closeable {
    private val pfd: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try {
        PdfRenderer(pfd)
    } catch (e: Exception) {
        pfd.close()
        throw e
    }
    private val mutex = Mutex()

    val pageCount: Int get() = renderer.pageCount

    suspend fun render(index: Int, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val page = renderer.openPage(index)
                try {
                    val h = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                } finally {
                    page.close()
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun close() {
        try { renderer.close() } catch (_: Exception) {}
        try { pfd.close() } catch (_: Exception) {}
    }

    /** إغلاق آمن ينتظر انتهاء أي رسم جارٍ. */
    suspend fun closeSafely() {
        mutex.withLock { close() }
    }
}

/**
 * extractMode: 0 = تلقائي (يكتشف النص التالف ويستخدم OCR)، 1 = OCR لكل الصفحات، 2 = نص PDF فقط.
 * workers: عدد معالجات OCR المتوازية. ocrWidth: عرض الصورة بالبكسل للـ OCR.
 * bestModel: استعمال نموذج OCR الأدق (أبطأ). ignoreMarks: تجاهل التشكيل أثناء القراءة لدقة الحروف.
 */
data class IndexOptions(
    val extractMode: Int,
    val workers: Int,
    val ocrWidth: Int,
    val bestModel: Boolean,
    val ignoreMarks: Boolean,
    val saveBoxes: Boolean
)

private const val MARKS = "\u064B\u064C\u064D\u064E\u064F\u0650\u0651\u0652\u0670"

/** مجموعة محركات Tesseract للعمل المتوازي. */
class OcrPool(ctx: Context, size: Int, best: Boolean, ignoreMarks: Boolean) {
    private val channel = Channel<TessBaseAPI>(Channel.UNLIMITED)
    private val all = ArrayList<TessBaseAPI>()
    val available: Boolean

    private fun hasAsset(ctx: Context, name: String): Boolean =
        try {
            ctx.assets.open(name).close()
            true
        } catch (e: Exception) {
            false
        }

    init {
        var ok = false
        try {
            val useBest = best && hasAsset(ctx, "tessdata/ara_best.traineddata")
            val folder = if (useBest) "tess_best" else "tess"
            val assetName = if (useBest) "tessdata/ara_best.traineddata" else "tessdata/ara.traineddata"
            val root = File(ctx.filesDir, folder)
            val td = File(root, "tessdata")
            td.mkdirs()
            val f = File(td, "ara.traineddata")
            if (!f.exists() || f.length() == 0L) {
                ctx.assets.open(assetName).use { i ->
                    f.outputStream().use { o -> i.copyTo(o) }
                }
            }
            repeat(size) {
                val api = TessBaseAPI()
                if (api.init(root.absolutePath, "ara")) {
                    if (ignoreMarks) api.setVariable("tessedit_char_blacklist", MARKS)
                    all.add(api)
                    channel.trySend(api)
                } else {
                    api.recycle()
                }
            }
            ok = all.isNotEmpty()
        } catch (e: Exception) {
            ok = all.isNotEmpty()
        }
        available = ok
    }

    /** يعيد النص ومواضع الكلمات (إن طُلبت). */
    suspend fun recognize(bmp: Bitmap, wantBoxes: Boolean): Pair<String, List<WordBox>> {
        val api = channel.receive()
        return try {
            api.setImage(bmp)
            val text = api.getUTF8Text() ?: ""
            val boxes = if (wantBoxes && text.isNotBlank()) {
                try {
                    parseHocr(api.getHOCRText(0) ?: "", bmp.width, bmp.height)
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
            Pair(text, boxes)
        } catch (e: Exception) {
            Pair("", emptyList())
        } finally {
            channel.trySend(api)
        }
    }

    fun close() {
        for (a in all) {
            try { a.recycle() } catch (_: Exception) {}
        }
        all.clear()
    }
}

class Indexer(private val ctx: Context, private val dao: BookDao) {

    init {
        PDFBoxResourceLoader.init(ctx.applicationContext)
    }

    private suspend fun ocrPage(
        s: PdfSource,
        index: Int,
        width: Int,
        pool: OcrPool,
        wantBoxes: Boolean
    ): Pair<String, List<WordBox>> {
        val bmp = s.render(index, width) ?: return Pair("", emptyList())
        return try {
            pool.recognize(bmp, wantBoxes)
        } finally {
            bmp.recycle()
        }
    }

    private fun readPending(f: File): MutableList<Int> =
        try {
            if (f.exists()) f.readText().split(",").mapNotNull { it.trim().toIntOrNull() }.toMutableList()
            else mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }

    private fun writePending(f: File, list: List<Int>) {
        try { f.writeText(list.joinToString(",")) } catch (_: Exception) {}
    }

    private suspend fun waitIfPaused() {
        while (Work.paused) {
            delay(400)
            currentCoroutineContext().ensureActive()
        }
    }

    /** قراءة صفحة واحدة بأدق إعدادات (للإصلاح اليدوي). تعيد النص ومواضع الكلمات. */
    suspend fun ocrOne(bookId: Long, pageNo: Int, ignoreMarks: Boolean): Pair<String, List<WordBox>> {
        val file = File(ctx.filesDir, "books/$bookId.pdf")
        if (!file.exists()) return Pair("", emptyList())
        val s = PdfSource(file)
        val pool = OcrPool(ctx, 1, true, ignoreMarks)
        return try {
            if (!pool.available) Pair("", emptyList()) else ocrPage(s, pageNo - 1, 2400, pool, true)
        } finally {
            pool.close()
            s.close()
        }
    }

    /**
     * المرحلة 1: فهرسة سريعة من نص الملف (يصبح البحث متاحاً خلال دقائق) مع حفظ مواضع الكلمات.
     * المرحلة 2: تحسين الصفحات التالفة بالـ OCR في الخلفية.
     */
    suspend fun index(bookId: Long, forceOcr: Boolean, restart: Boolean, opts: IndexOptions) {
        val book = dao.getBook(bookId) ?: return
        Lexicon.ensureBase(ctx)
        if (book.status == ST_DONE && !forceOcr && !restart) return
        val file = File(ctx.filesDir, "books/$bookId.pdf")
        if (!file.exists()) {
            Work.errors[bookId] = "ملف الكتاب غير موجود"
            dao.setStatus(bookId, ST_ERROR)
            return
        }
        Work.errors.remove(bookId)
        val pendingFile = File(ctx.filesDir, "books/$bookId.pending")
        val ocrFile = File(ctx.filesDir, "books/$bookId.ocr")
        val reset = forceOcr || restart
        val resumePhase2 = !reset && book.status == ST_REFINING
        val workers = opts.workers.coerceIn(1, 6)

        var src: PdfSource? = null
        var doc: PDDocument? = null
        var pool: OcrPool? = null
        var poolTried = false
        var phase1Done = resumePhase2

        fun ensurePool(): OcrPool? {
            if (!poolTried) {
                poolTried = true
                val p = OcrPool(ctx, workers, opts.bestModel, opts.ignoreMarks)
                if (p.available) pool = p else p.close()
            }
            return pool
        }

        try {
            val s = PdfSource(file)
            src = s
            val total = s.pageCount
            val textOnly = !forceOcr && opts.extractMode == 2
            val allOcr = forceOcr || opts.extractMode == 1

            if (!resumePhase2) {
                if (reset) {
                    dao.deletePages(bookId)
                    pendingFile.delete()
                    ocrFile.delete()
                    WordStore.deleteBook(ctx, bookId)
                }
                val done = if (reset) 0 else book.indexedPages
                dao.setProgress(bookId, done, ST_INDEXING)
                doc = try {
                    PDDocument.load(file, MemoryUsageSetting.setupMixed(64L * 1024 * 1024))
                } catch (e: Exception) {
                    null
                }
                val stripper = GlyphStripper()
                stripper.setSortByPosition(true)
                val pending = readPending(pendingFile)
                var p = done + 1
                val chunk = 25
                while (p <= total) {
                    currentCoroutineContext().ensureActive()
                    waitIfPaused()
                    val end = minOf(total, p + chunk - 1)
                    val list = ArrayList<PageEntity>(end - p + 1)
                    for (i in p..end) {
                        var t = ""
                        var words: List<WordBox> = emptyList()
                        val d = doc
                        if (d != null) {
                            try {
                                stripper.glyphs.clear()
                                stripper.setStartPage(i)
                                stripper.setEndPage(i)
                                t = Formulas.fix(Normalizer.normalize(stripper.getText(d), Normalizer.Form.NFKC))
                                if (opts.saveBoxes) {
                                    val box = d.getPage(i - 1).cropBox
                                    words = buildWords(stripper.glyphs, box.width, box.height)
                                }
                            } catch (e: Exception) {
                                t = ""
                                words = emptyList()
                            }
                        }
                        val flagged = allOcr ||
                            (!textOnly && (Arabic.letterCount(t) < 15 || Arabic.looksCorrupted(t)))
                        val tt = t.trim()
                        val nn = Arabic.normalize(tt)
                        if (flagged) {
                            pending.add(i)
                        } else {
                            if (opts.saveBoxes) WordStore.save(ctx, bookId, i, words)
                            Lexicon.learnNorm(nn)
                        }
                        list.add(PageEntity(bookId = bookId, pageNo = i, text = tt, norm = nn))
                    }
                    dao.insertPages(list)
                    writePending(pendingFile, pending)
                    dao.setProgress(bookId, end, ST_INDEXING)
                    p = end + 1
                }
                try { doc?.close() } catch (_: Exception) {}
                doc = null
                phase1Done = true
                Lexicon.saveLearned()
            }

            val queue = readPending(pendingFile).distinct().sorted().toMutableList()
            if (textOnly || queue.isEmpty()) {
                pendingFile.delete()
                dao.setProgress(bookId, total, ST_DONE)
                return
            }

            dao.setProgress(bookId, total, ST_REFINING)
            Work.refineLeft[bookId] = queue.size
            Work.refineStartLeft[bookId] = queue.size
            Work.refineStartMs[bookId] = System.currentTimeMillis()
            val sem = Semaphore(workers)
            val batchSize = maxOf(4, workers * 2)
            while (queue.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                waitIfPaused()
                val pl = ensurePool() ?: break
                val batch = queue.take(batchSize)
                val results = coroutineScope {
                    batch.map { page ->
                        async(Dispatchers.Default) {
                            sem.withPermit {
                                page to ocrPage(s, page - 1, opts.ocrWidth, pl, opts.saveBoxes)
                            }
                        }
                    }.awaitAll()
                }
                val ents = ArrayList<PageEntity>()
                val okPages = ArrayList<Int>()
                for ((page, res) in results) {
                    val r = res.first
                    if (r.isNotBlank()) {
                        val t = Formulas.fix(r.trim())
                        Lexicon.learnBoxes(res.second)
                        // التصحيح للبحث فقط: النص المعروض يبقى كما قرأه الـ OCR
                        val nn = Lexicon.fixNorm(Arabic.normalize(t))
                        ents.add(PageEntity(bookId = bookId, pageNo = page, text = t, norm = nn))
                        okPages.add(page)
                        if (opts.saveBoxes) {
                            WordStore.save(ctx, bookId, page, Lexicon.fixBoxes(res.second))
                        }
                    }
                }
                if (ents.isNotEmpty()) dao.updateMany(ents)
                if (okPages.isNotEmpty()) {
                    try { ocrFile.appendText(okPages.joinToString(",", postfix = ",")) } catch (_: Exception) {}
                }
                repeat(batch.size) { queue.removeAt(0) }
                writePending(pendingFile, queue)
                Work.refineLeft[bookId] = queue.size
            }
            pendingFile.delete()
            Work.refineLeft.remove(bookId)
            Work.refineStartLeft.remove(bookId)
            Work.refineStartMs.remove(bookId)
            Lexicon.saveLearned()
            dao.setProgress(bookId, total, ST_DONE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Work.refineLeft.remove(bookId)
            Work.refineStartLeft.remove(bookId)
            Work.refineStartMs.remove(bookId)
            val msg = (e.message ?: e.javaClass.simpleName)
            if (phase1Done) {
                // النص مفهرس ويعمل البحث؛ تبقى صفحات التحسين المعلّقة للمحاولة لاحقاً
                Work.errors[bookId] = "تعذّر إكمال تحسين النص: $msg"
                dao.setProgress(bookId, book.pageCount.coerceAtLeast(1), ST_DONE)
            } else {
                Work.errors[bookId] = msg
                dao.setStatus(bookId, ST_ERROR)
            }
        } finally {
            try { doc?.close() } catch (_: Exception) {}
            src?.close()
            pool?.close()
        }
    }
}
