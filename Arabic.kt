package com.bahith.pdf

import kotlin.math.max
import kotlin.math.min

enum class SearchMode(val label: String) {
    ALL("كل الكلمات"),
    ANY("أي كلمة"),
    PREFIX("بادئات"),
    PHRASE("عبارة")
}

/** خيارات توحيد الحروف في البحث (تُغيَّر من الإعدادات). */
object ArabicOpts {
    @Volatile var taMarbuta = true
    @Volatile var alefMaksura = true
    @Volatile var hamza = true
}

object Arabic {

    class Norm(val text: String, val map: IntArray)

    private fun fold(c: Char, d: Boolean): Char = when (c) {
        'أ', 'إ', 'آ', 'ٱ' -> if (d || ArabicOpts.hamza) 'ا' else c
        'ئ' -> if (d || ArabicOpts.hamza) 'ي' else c
        'ؤ' -> if (d || ArabicOpts.hamza) 'و' else c
        'ى' -> if (d || ArabicOpts.alefMaksura) 'ي' else c
        'ة' -> if (d || ArabicOpts.taMarbuta) 'ه' else c
        'ۃ', 'ۀ', 'ھ' -> 'ه'
        'ی' -> 'ي'
        'ک' -> 'ك'
        else -> c
    }

    /** يزيل التشكيل والتطويل، يوحّد الهمزات والألف المقصورة والتاء المربوطة، ويحوّل علامات الترقيم إلى مسافات. */
    fun normalizeMapped(s: String, defaults: Boolean = false): Norm {
        val sb = StringBuilder(s.length)
        val map = IntArray(s.length + 1)
        var lastSpace = true
        for (i in s.indices) {
            var c = s[i]
            val type = Character.getType(c)
            if (type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.FORMAT.toInt() ||
                c == 'ـ'
            ) continue
            c = fold(c, defaults)
            if (c in '٠'..'٩') c = '0' + (c - '٠')
            else if (c in '۰'..'۹') c = '0' + (c - '۰')
            if (Character.isLetterOrDigit(c)) {
                sb.append(c.lowercaseChar())
                map[sb.length - 1] = i
                lastSpace = false
            } else if (!lastSpace) {
                sb.append(' ')
                map[sb.length - 1] = i
                lastSpace = true
            }
        }
        if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') sb.setLength(sb.length - 1)
        return Norm(sb.toString(), map.copyOf(sb.length))
    }

    fun normalize(s: String): String = normalizeMapped(s).text

    fun terms(q: String): List<String> =
        normalize(q).split(' ').filter { it.isNotEmpty() }

    /** يزيل التشكيل والتطويل فقط (للعرض). */
    fun stripTashkeel(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt() || c == 'ـ') continue
            sb.append(c)
        }
        return sb.toString()
    }

    fun letterCount(s: String): Int = s.count { it.isLetter() }

    private val COMMON = setOf(
        "في", "من", "علي", "عن", "الي", "الله", "قال", "ان", "ما", "لا", "هذا", "التي", "الذي",
        "بن", "ابي", "النبي", "رسول", "كان", "ثم", "او", "قد", "كل", "اذا", "لم", "انه", "عليه",
        "وسلم", "حدثنا", "هو", "به", "ذلك", "فقال", "وقال", "ابن", "اللهم", "هي", "وهو", "مع"
    )

    /** يكتشف النص التالف المستخرج من ملف PDF (حروف ناقصة أو كلمات معكوسة). */
    fun looksCorrupted(raw: String): Boolean {
        val norm = normalizeMapped(raw, true).text
        if (norm.contains("ملسو هيلع")) return true
        if (raw.length > 120) {
            val punct = ".,:;!?()[]{}\"'-_/*،؛؟«»…%"
            var odd = 0
            for (c in raw) {
                if (c.isLetterOrDigit() || c.isWhitespace() || punct.indexOf(c) >= 0 ||
                    Character.getType(c) == Character.NON_SPACING_MARK.toInt()
                ) continue
                odd++
            }
            if (odd.toDouble() / raw.length > 0.08) return true
        }
        val toks = norm.split(' ').filter { t -> t.any { it in '\u0621'..'\u064A' } }
        if (toks.size < 25) return false
        val singles = toks.count { it.length == 1 }.toDouble() / toks.size
        val common = toks.count { it in COMMON }.toDouble() / toks.size
        return singles > 0.12 || common < 0.025
    }

    fun ftsQuery(terms: List<String>, mode: SearchMode): String = when (mode) {
        SearchMode.ALL -> terms.joinToString(" ")
        SearchMode.ANY -> terms.joinToString(" OR ")
        SearchMode.PREFIX -> terms.joinToString(" ") { "$it*" }
        SearchMode.PHRASE -> "\"" + terms.joinToString(" ") + "\""
    }

    /** مواضع الكلمات المطلوبة داخل النص الأصلي (بعد مطابقة التطبيع). */
    fun findRanges(text: String, terms: List<String>): List<IntRange> {
        if (terms.isEmpty() || text.isEmpty()) return emptyList()
        val n = normalizeMapped(text)
        val out = ArrayList<IntRange>()
        for (t in terms) {
            if (t.isEmpty()) continue
            var i = n.text.indexOf(t)
            var guard = 0
            while (i >= 0 && guard++ < 400) {
                val s = n.map[i]
                val e = n.map[i + t.length - 1]
                out.add(s..e)
                i = n.text.indexOf(t, i + t.length)
            }
        }
        return out.sortedBy { it.first }
    }

    /** مقتطف قصير حول أول موضع للنتيجة مع مواضع التظليل. */
    fun snippet(rawText: String, terms: List<String>, radius: Int = 80): Pair<String, List<IntRange>> {
        val flat = rawText.replace(Regex("\\s+"), " ")
        val n = normalizeMapped(flat)
        var pos = -1
        for (t in terms) {
            val i = n.text.indexOf(t)
            if (i >= 0 && (pos < 0 || i < pos)) pos = i
        }
        val orig = if (pos >= 0 && pos < n.map.size) n.map[pos] else 0
        val start = max(0, orig - radius)
        val end = min(flat.length, orig + radius * 2)
        val body = flat.substring(start, end)
        val snip = (if (start > 0) "… " else "") + body + (if (end < flat.length) " …" else "")
        return snip to findRanges(snip, terms)
    }
}
