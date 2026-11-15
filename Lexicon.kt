package com.bahith.pdf

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

/**
 * معجم لغوي يُستعمل لتحسين البحث في صفحات الـ OCR فقط.
 * لا يغيّر صورة الصفحة، ولا يغيّر عمود البحث إلا للكلمات غير المعروفة.
 *
 * يتألف من:
 *  - معجم كبير (ملايين الكلمات) يُخزَّن كبصمات 64 بت مرتبة (نحو 8 بايت للكلمة).
 *  - كلمات متعلَّمة من كتبك مع عدد تكرارها (تُستعمل للترجيح بين الاقتراحات).
 */
object Lexicon {
    @Volatile var on = true
    @Volatile var minCount = 3
    @Volatile var minSuggest = 8
    @Volatile var maxWords = 600_000
    @Volatile var learn = true
    @Volatile var learnOcr = true
    @Volatile var guessSalawat = true

    var learnedSize by mutableIntStateOf(0)
    var baseSize by mutableIntStateOf(0)
    var busy by mutableStateOf(false)
    var status by mutableStateOf("")

    private val learned = ConcurrentHashMap<String, Int>()

    @Volatile private var base = LongArray(0)
    @Volatile private var loaded = false
    private val baseLock = Any()
    private var dir: File? = null

    private const val ALPHA = "ابتثجحخدذرزسشصضطظعغفقكلمنهويء"
    private const val BASE_ASSET = "lexicon/arabic-words.txt"

    // مجموعات الحروف المتشابهة في الرسم (التي يخلط بينها الـ OCR كثيراً)
    private val GROUP_OF: Map<Char, String> = HashMap<Char, String>().also { m ->
        for (g in listOf("بتثني", "جحخ", "دذ", "رز", "سش", "صض", "طظ", "عغ", "فق", "وم")) {
            for (c in g) m[c] = g
        }
    }

    // ---------------- أدوات الحروف ----------------

    private fun cf(c: Char): Char = when (c) {
        'أ', 'إ', 'آ', 'ٱ' -> 'ا'
        'ى', 'ئ' -> 'ي'
        'ؤ' -> 'و'
        'ة', 'ۃ', 'ۀ', 'ھ' -> 'ه'
        'ی' -> 'ي'
        'ک' -> 'ك'
        else -> c
    }

    /** صيغة موحّدة للكلمة (لا تعتمد على خيارات البحث). */
    fun canon(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(cf(c))
        return sb.toString()
    }

    private fun hash(s: CharSequence): Long {
        var x = -3750763034362895579L
        for (i in 0 until s.length) x = (x xor s[i].code.toLong()) * 1099511628211L
        return x
    }

    private fun isArabicWord(t: String): Boolean {
        if (t.isEmpty()) return false
        for (c in t) if (c !in '\u0621'..'\u064A') return false
        return true
    }

    private fun baseContains(w: String): Boolean {
        val b = base
        return b.isNotEmpty() && java.util.Arrays.binarySearch(b, hash(w)) >= 0
    }

    val baseReady: Boolean get() = base.isNotEmpty()

    // ---------------- التحميل والحفظ ----------------

    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val d = File(ctx.applicationContext.filesDir, "lexicon")
            d.mkdirs()
            dir = d
            val f = File(d, "learned.tsv")
            if (f.exists()) {
                try {
                    f.forEachLine { line ->
                        val i = line.indexOf('\t')
                        if (i > 0) learned[line.substring(0, i)] = line.substring(i + 1).toIntOrNull() ?: 1
                    }
                } catch (_: Exception) {
                }
            }
            loaded = true
            learnedSize = learned.size
        }
    }

    private fun prune() {
        if (learned.size > maxWords) learned.entries.removeIf { it.value <= 1 }
        if (learned.size > maxWords) learned.entries.removeIf { it.value <= 2 }
    }

    fun saveLearned() {
        val d = dir ?: return
        prune()
        try {
            File(d, "learned.tsv").bufferedWriter().use { w ->
                for ((k, v) in learned) {
                    w.write(k)
                    w.write("\t")
                    w.write(v.toString())
                    w.newLine()
                }
            }
        } catch (_: Exception) {
        }
        learnedSize = learned.size
    }

    fun clearLearned() {
        learned.clear()
        dir?.let { File(it, "learned.tsv").delete() }
        learnedSize = 0
    }

    fun clearBase() {
        synchronized(baseLock) {
            base = LongArray(0)
            baseSize = 0
            dir?.let {
                File(it, "base.bin").delete()
                try { File(it, "base.off").writeText("1") } catch (_: Exception) {}
            }
        }
    }

    // ---------------- المعجم الكبير ----------------

    private fun loadBase(f: File): Boolean {
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(f), 1 shl 16)).use { d ->
                val n = d.readInt()
                val a = LongArray(n)
                for (i in 0 until n) a[i] = d.readLong()
                base = a
                baseSize = n
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** يبني ملف المعجم الكبير من تدفق نصي (كل سطر كلمة؛ أول رمز في السطر). */
    private fun buildBase(input: InputStream, out: File): Int {
        var arr = LongArray(1 shl 20)
        var n = 0
        val sb = StringBuilder(24)
        BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 16).use { r ->
            while (true) {
                val line = r.readLine() ?: break
                sb.setLength(0)
                var ok = true
                for (ch in line) {
                    if (ch == ' ' || ch == '\t' || ch == ',' || ch == ';') {
                        if (sb.isNotEmpty()) break else continue
                    }
                    if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt() || ch == 'ـ') continue
                    val c = cf(ch)
                    if (c !in '\u0621'..'\u064A') {
                        ok = false
                        break
                    }
                    sb.append(c)
                }
                if (!ok || sb.length < 2 || sb.length > 24) continue
                if (n == arr.size) arr = arr.copyOf(arr.size * 2)
                arr[n++] = hash(sb)
                if (n % 250_000 == 0) status = "جارٍ تجهيز المعجم الكبير… $n كلمة"
            }
        }
        java.util.Arrays.sort(arr, 0, n)
        var m = 0
        var last = 0L
        for (i in 0 until n) {
            val v = arr[i]
            if (m == 0 || v != last) {
                arr[m++] = v
                last = v
            }
        }
        val tmp = File(out.parentFile, "base.tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { d ->
            d.writeInt(m)
            for (i in 0 until m) d.writeLong(arr[i])
        }
        out.delete()
        tmp.renameTo(out)
        base = arr.copyOf(m)
        baseSize = m
        return m
    }

    /** يجهّز المعجم الكبير من القائمة المرفقة مع التطبيق (مرة واحدة). */
    fun ensureBase(ctx: Context) {
        ensureLoaded(ctx)
        if (base.isNotEmpty()) return
        synchronized(baseLock) {
            if (base.isNotEmpty()) return
            val d = dir ?: return
            if (File(d, "base.off").exists()) return
            val f = File(d, "base.bin")
            if (f.exists() && loadBase(f)) return
            val has = try {
                ctx.assets.open(BASE_ASSET).close()
                true
            } catch (_: Exception) {
                false
            }
            if (!has) return
            busy = true
            status = "جارٍ تجهيز المعجم الكبير…"
            try {
                ctx.assets.open(BASE_ASSET).use { buildBase(it, f) }
                status = ""
            } catch (e: Exception) {
                status = "تعذّر تجهيز المعجم الكبير: ${e.message ?: "خطأ"}"
            } finally {
                busy = false
            }
        }
    }

    /** يستبدل المعجم الكبير بقائمة يختارها المستخدم (ملف نصي: كلمة في كل سطر). */
    fun importList(ctx: Context, uri: Uri): Int {
        ensureLoaded(ctx)
        val d = dir ?: return 0
        return synchronized(baseLock) {
            File(d, "base.off").delete()
            status = "جارٍ تجهيز المعجم الكبير…"
            val n = ctx.contentResolver.openInputStream(uri)?.use { buildBase(it, File(d, "base.bin")) } ?: 0
            status = ""
            n
        }
    }

    /** يستورد نصاً مرجعياً (مثل المصحف أو كتاب): كلماته تُضاف للمتعلَّم بوزن قوي. */
    fun importText(ctx: Context, uri: Uri): Int {
        ensureLoaded(ctx)
        var added = 0
        ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { r ->
            while (true) {
                val line = r.readLine() ?: break
                val norm = Arabic.normalize(line)
                for (t0 in norm.split(' ')) {
                    val t = canon(t0)
                    if (t.length < 2 || t.length > 24 || !isArabicWord(t)) continue
                    learned.merge(t, 20) { a, b -> a + b }
                    added++
                }
            }
        }
        saveLearned()
        return added
    }

    // ---------------- التعلّم ----------------

    private fun addWord(t0: String, n: Int) {
        val t = canon(t0)
        if (t.length < 2 || t.length > 20 || !isArabicWord(t)) return
        learned.merge(t, n) { a, b -> a + b }
    }

    fun learnNorm(norm: String, force: Boolean = false) {
        if ((!learn && !force) || norm.isEmpty()) return
        for (t in norm.split(' ')) addWord(t, 1)
    }

    fun learnBoxes(boxes: List<WordBox>) {
        if (!learnOcr) return
        for (b in boxes) if (b.conf >= 90) addWord(b.w, 1)
    }

    // ---------------- التصحيح ----------------

    private fun isValid(w: String): Boolean = (learned[w] ?: 0) >= minCount || baseContains(w)

    private class Cand(val w: String, val cost: Int, val lc: Int)

    private fun suggest(w: String): String? {
        val found = ArrayList<Cand>()
        val seen = HashSet<String>()
        fun test(c: String, cost: Int) {
            if (found.size > 400 || c == w || !seen.add(c)) return
            val lc = learned[c] ?: 0
            if (lc >= minCount || baseContains(c)) found.add(Cand(c, cost, lc))
        }
        val n = w.length
        if (n >= 4) {
            for (i in 0 until n) test(w.substring(0, i) + w.substring(i + 1), 1)
            for (i in 0 until n - 1) test(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2), 1)
            for (i in 0 until n) for (ch in ALPHA) if (ch != w[i]) test(w.substring(0, i) + ch + w.substring(i + 1), 1)
            for (i in 0..n) for (ch in ALPHA) test(w.substring(0, i) + ch + w.substring(i), 1)
        }
        // استبدال حروف متشابهة الرسم (نقاط/شكل): حتى 3 حروف للكلمات الطويلة
        val chars = w.toCharArray()
        val maxSubs = if (n >= 5) 3 else 2
        var count = 0
        fun rec(start: Int, subs: Int) {
            for (i in start until chars.size) {
                val g = GROUP_OF[chars[i]] ?: continue
                val orig = chars[i]
                for (alt in g) {
                    if (alt == orig) continue
                    chars[i] = alt
                    count++
                    test(String(chars), subs + 1)
                    if (subs + 1 < maxSubs && count < 6000) rec(i + 1, subs + 1)
                    chars[i] = orig
                    if (count >= 6000) return
                }
            }
        }
        rec(0, 0)

        if (found.isEmpty()) return null
        val strong = found.filter { it.lc >= minSuggest }
        if (strong.isNotEmpty()) return strong.maxByOrNull { it.lc * 4 - it.cost }!!.w
        if (!baseReady) return null
        val minCost = found.minOf { it.cost }
        val best = found.filter { it.cost == minCost }
        return if (best.size == 1) best[0].w else null
    }

    /** كلمة واحدة: تبقى كما هي إن كانت معروفة، وإلا تُستبدل بأقرب كلمة معروفة قوية. */
    fun fixWord(w0: String): String {
        if (!on || w0.length < 3 || !isArabicWord(w0)) return w0
        val w = canon(w0)
        if (isValid(w)) return w0
        return suggest(w) ?: w0
    }

    private fun inferSalawat(toks: List<String>): List<String> {
        val out = ArrayList<String>(toks.size + 4)
        var i = 0
        while (i < toks.size) {
            out.add(toks[i])
            if (i >= 1 && toks[i] == "الله" && toks[i - 1] == "رسول") {
                var j = i + 1
                var k = 0
                while (j < toks.size && k < 3 && toks[j].length <= 8 && isArabicWord(toks[j]) && !isValid(canon(toks[j]))) {
                    j++
                    k++
                }
                if (k >= 1) {
                    out.add("صلى")
                    out.add("الله")
                    out.add("عليه")
                    out.add("وسلم")
                    i = j
                    continue
                }
            }
            i++
        }
        return out
    }

    /** نص بعد التطبيع: تُصحَّح كلماته للبحث فقط. */
    fun fixNorm(norm: String): String {
        if (!on || norm.isEmpty()) return norm
        val toks = norm.split(' ').filter { it.isNotEmpty() }.map { fixWord(it) }
        val res = if (guessSalawat && baseSize > 100_000) inferSalawat(toks) else toks
        return res.joinToString(" ")
    }

    /** مواضع الكلمات: تُصحَّح الكلمات، ويُستدل على رمز «ﷺ» الصغير بعد «رسول الله». */
    fun fixBoxes(boxes: List<WordBox>): List<WordBox> {
        if (!on || boxes.isEmpty()) return boxes
        val out = ArrayList<WordBox>(boxes.size)
        for (b in boxes) out.add(b.copy(w = fixWord(b.w)))
        if (!(guessSalawat && baseSize > 100_000)) return out
        var i = 1
        while (i < out.size) {
            if (out[i].w == "الله" && out[i - 1].w == "رسول") {
                var j = i + 1
                var k = 0
                while (j < out.size && k < 3 && out[j].w.length <= 8 && isArabicWord(out[j].w) && !isValid(canon(out[j].w))) {
                    j++
                    k++
                }
                if (k >= 1) {
                    for (t in i + 1 until j) out[t] = out[t].copy(w = "صلىاللهعليهوسلم")
                    i = j
                    continue
                }
            }
            i++
        }
        return out
    }
}
