package com.bahith.pdf

import android.content.Context
import android.graphics.Bitmap
import android.text.Html
import android.util.LruCache
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** كلمة على الصفحة: نصها بعد التطبيع، ومستطيلها نسبةً إلى الصفحة (0..1). */
data class WordBox(val w: String, val x: Float, val y: Float, val ww: Float, val hh: Float, val conf: Int = 100)

/** تخزين مواضع كلمات كل صفحة في ملف صغير (دون تغيير قاعدة البيانات). */
object WordStore {
    private fun dir(ctx: Context, bookId: Long) = File(ctx.filesDir, "words/$bookId")

    fun save(ctx: Context, bookId: Long, pageNo: Int, boxes: List<WordBox>) {
        try {
            val d = dir(ctx, bookId)
            d.mkdirs()
            val f = File(d, "$pageNo.txt")
            if (boxes.isEmpty()) {
                f.delete()
                return
            }
            val sb = StringBuilder(boxes.size * 24)
            for (b in boxes) {
                sb.append(b.w).append('\t')
                    .append(String.format(Locale.US, "%.4f\t%.4f\t%.4f\t%.4f", b.x, b.y, b.ww, b.hh))
                    .append('\n')
            }
            f.writeText(sb.toString())
        } catch (_: Exception) {
        }
    }

    fun load(ctx: Context, bookId: Long, pageNo: Int): List<WordBox> {
        return try {
            val f = File(dir(ctx, bookId), "$pageNo.txt")
            if (!f.exists()) return emptyList()
            val out = ArrayList<WordBox>()
            f.forEachLine { line ->
                val p = line.split('\t')
                if (p.size >= 5) {
                    val x = p[1].toFloatOrNull()
                    val y = p[2].toFloatOrNull()
                    val w = p[3].toFloatOrNull()
                    val h = p[4].toFloatOrNull()
                    if (x != null && y != null && w != null && h != null) out.add(WordBox(p[0], x, y, w, h))
                }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun deleteBook(ctx: Context, bookId: Long) {
        try { dir(ctx, bookId).deleteRecursively() } catch (_: Exception) {}
    }

    fun deleteAll(ctx: Context) {
        try { File(ctx.filesDir, "words").deleteRecursively() } catch (_: Exception) {}
    }
}

/** يحدد الكلمات المطابقة للبحث على الصفحة (تطابق جزئي كما في تظليل النص). */
fun matchBoxes(boxes: List<WordBox>, terms: List<String>): List<WordBox> {
    if (terms.isEmpty() || boxes.isEmpty()) return emptyList()
    val phrase = terms.size == 1 && terms[0].contains(' ')
    if (!phrase) {
        val out = ArrayList<WordBox>()
        for (b in boxes) {
            if (terms.any { t -> t.isNotEmpty() && b.w.contains(t) }) out.add(b)
        }
        return out
    }
    val toks = terms[0].split(' ').filter { it.isNotEmpty() }
    if (toks.isEmpty()) return emptyList()
    fun scan(list: List<WordBox>): List<WordBox> {
        val out = ArrayList<WordBox>()
        var i = 0
        while (i + toks.size <= list.size) {
            var ok = true
            for (j in toks.indices) {
                val w = list[i + j].w
                val t = toks[j]
                val good = when {
                    toks.size == 1 -> w.contains(t)
                    j == 0 -> w.endsWith(t)
                    j == toks.size - 1 -> w.startsWith(t)
                    else -> w == t
                }
                if (!good) {
                    ok = false
                    break
                }
            }
            if (ok) {
                for (j in toks.indices) out.add(list[i + j])
                i += toks.size
            } else {
                i++
            }
        }
        return out
    }
    val fwd = scan(boxes)
    return if (fwd.isNotEmpty()) fwd else scan(boxes.reversed())
}

// ---------------------------------------------------------------------------------
// استخراج مواضع الكلمات من طبقة النص الأصلية (في نفس تمريرة استخراج النص)
// ---------------------------------------------------------------------------------

class Glyph(val ch: String, val x: Float, val y: Float, val w: Float, val h: Float)

class GlyphStripper : PDFTextStripper() {
    val glyphs = ArrayList<Glyph>()

    override fun processTextPosition(text: TextPosition) {
        super.processTextPosition(text)
        val u = text.unicode ?: return
        if (u.isEmpty()) return
        glyphs.add(Glyph(u, text.xDirAdj, text.yDirAdj, text.widthDirAdj, text.heightDir))
    }
}

/** يجمّع المحارف إلى كلمات بمواضعها، بترتيب منطقي للعربية (من اليمين لليسار). */
fun buildWords(glyphs: List<Glyph>, pageW: Float, pageH: Float): List<WordBox> {
    if (glyphs.isEmpty() || pageW <= 1f || pageH <= 1f) return emptyList()
    val base = ArrayList<Glyph>(glyphs.size)
    for (g in glyphs) {
        val s = java.text.Normalizer.normalize(g.ch, java.text.Normalizer.Form.NFKC)
        if (s.isBlank()) continue
        var markOnly = true
        for (c in s) {
            if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) {
                markOnly = false
                break
            }
        }
        if (markOnly) continue
        base.add(Glyph(s, g.x, g.y, g.w, g.h))
    }
    if (base.isEmpty()) return emptyList()

    val sorted = base.sortedBy { it.y }
    val lines = ArrayList<MutableList<Glyph>>()
    for (g in sorted) {
        val last = lines.lastOrNull()
        if (last != null && abs(last[0].y - g.y) <= max(last[0].h, g.h) * 0.6f) last.add(g) else lines.add(mutableListOf(g))
    }

    val out = ArrayList<WordBox>()
    for (line in lines) {
        val ordered = line.sortedBy { it.x }
        var arabic = 0
        for (g in ordered) for (c in g.ch) if (c in '\u0600'..'\u06FF') arabic++
        val rtl = arabic * 2 > ordered.size

        var cur = ArrayList<Glyph>()
        fun flush() {
            if (cur.isEmpty()) return
            val letters = if (rtl) cur.reversed() else cur
            val sb = StringBuilder()
            for (g in letters) sb.append(g.ch)
            val norm = Arabic.normalize(sb.toString()).replace(" ", "")
            if (norm.isNotEmpty()) {
                var x0 = Float.MAX_VALUE
                var x1 = 0f
                var top = Float.MAX_VALUE
                var hmax = 0f
                for (g in cur) {
                    x0 = min(x0, g.x)
                    x1 = max(x1, g.x + g.w)
                    top = min(top, g.y - g.h)
                    hmax = max(hmax, g.h)
                }
                val bx = (x0 / pageW).coerceIn(0f, 1f)
                val by = (top / pageH).coerceIn(0f, 1f)
                val bw = ((x1 - x0) / pageW).coerceIn(0f, 1f)
                val bh = (hmax * 1.35f / pageH).coerceIn(0f, 1f)
                out.add(WordBox(norm, bx, by, bw, bh))
            }
            cur = ArrayList()
        }
        for (g in ordered) {
            if (cur.isNotEmpty()) {
                val prev = cur[cur.size - 1]
                val gap = g.x - (prev.x + prev.w)
                if (gap > g.h * 0.22f) flush()
            }
            cur.add(g)
        }
        flush()
    }
    return out
}

// ---------------------------------------------------------------------------------
// مواضع الكلمات من نتيجة OCR (hOCR)
// ---------------------------------------------------------------------------------

private val SPAN = Regex("(?is)<span\\b([^>]*class=['\"][^'\"]*ocrx_word[^'\"]*['\"][^>]*)>(.*?)</span>")
private val BBOX = Regex("bbox\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)")
private val TAGS = Regex("(?is)<[^>]+>")
private val CONF = Regex("x_wconf\\s+(\\d+)")

fun parseHocr(hocr: String, imgW: Int, imgH: Int): List<WordBox> {
    if (hocr.isBlank() || imgW <= 0 || imgH <= 0) return emptyList()
    val out = ArrayList<WordBox>()
    for (m in SPAN.findAll(hocr)) {
        val bb = BBOX.find(m.groupValues[1]) ?: continue
        val raw = TAGS.replace(m.groupValues[2], " ")
        val word = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
        val norm = Arabic.normalize(word).replace(" ", "")
        if (norm.isEmpty()) continue
        val x0 = bb.groupValues[1].toFloatOrNull() ?: continue
        val y0 = bb.groupValues[2].toFloatOrNull() ?: continue
        val x1 = bb.groupValues[3].toFloatOrNull() ?: continue
        val y1 = bb.groupValues[4].toFloatOrNull() ?: continue
        val conf = CONF.find(m.groupValues[1])?.groupValues?.get(1)?.toIntOrNull() ?: 0
        out.add(
            WordBox(
                norm,
                (x0 / imgW).coerceIn(0f, 1f),
                (y0 / imgH).coerceIn(0f, 1f),
                ((x1 - x0) / imgW).coerceIn(0f, 1f),
                ((y1 - y0) / imgH).coerceIn(0f, 1f),
                conf
            )
        )
    }
    return out
}

// ---------------------------------------------------------------------------------
// ذاكرة مؤقتة لصور الصفحات (تُستعمل لمقاطع نتائج البحث)
// ---------------------------------------------------------------------------------

object PageCache {
    private val mutex = Mutex()
    private val sources = object : LinkedHashMap<Long, PdfSource>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, PdfSource>): Boolean {
            if (size > 6) {
                val v = eldest.value
                Work.scope.launch { v.closeSafely() }
                return true
            }
            return false
        }
    }
    private val bitmaps = object : LruCache<String, Bitmap>(48 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    suspend fun page(ctx: Context, bookId: Long, index: Int, width: Int): Bitmap? {
        val key = "$bookId:$index:$width"
        bitmaps.get(key)?.let { return it }
        val src = try {
            mutex.withLock {
                sources.getOrPut(bookId) { PdfSource(File(ctx.filesDir, "books/$bookId.pdf")) }
            }
        } catch (e: Exception) {
            return null
        }
        val b = src.render(index, width)
        if (b != null) bitmaps.put(key, b)
        return b
    }

    suspend fun drop(bookId: Long) {
        mutex.withLock {
            sources.remove(bookId)?.closeSafely()
        }
        val prefix = "$bookId:"
        for (k in bitmaps.snapshot().keys) if (k.startsWith(prefix)) bitmaps.remove(k)
    }

    suspend fun dropAll() {
        mutex.withLock {
            for (s in sources.values) s.closeSafely()
            sources.clear()
        }
        bitmaps.evictAll()
    }
}
