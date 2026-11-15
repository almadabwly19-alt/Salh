package com.bahith.pdf

/**
 * يصلح صيغ الثناء والترضّي المعكوسة في النص المستخرج من ملفات PDF
 * (مثل «ملسو هيلع هللا ىلص» ← «صلى الله عليه وسلم»). قاعدة قاطعة لا تعتمد على معجم.
 */
object Formulas {
    @Volatile var on = true

    private val FORMS = listOf(
        "صلى الله عليه وسلم",
        "صلى الله عليه وآله وسلم",
        "عليه الصلاة والسلام",
        "عليه السلام",
        "عليهم السلام",
        "رضي الله عنه",
        "رضي الله عنها",
        "رضي الله عنهم",
        "رضي الله عنهما",
        "رحمه الله تعالى",
        "رحمه الله",
        "رحمها الله",
        "رحمهم الله",
        "تبارك وتعالى",
        "سبحانه وتعالى",
        "عز وجل",
        "جل جلاله"
    )

    private class Rule(val re: Regex, val to: String)

    private fun letterClass(c: Char): String = when (c) {
        'ا' -> "[اأإآٱ]"
        'ي', 'ى' -> "[يى]"
        'ه', 'ة' -> "[هة]"
        else -> Regex.escape(c.toString())
    }

    private fun pattern(s: String): String {
        val marks = "[\\u064B-\\u065F\\u0670\\u0640]*"
        val sb = StringBuilder("(?<![\\u0621-\\u064A])")
        for (c in s) {
            if (c == ' ') {
                sb.append("\\s+")
            } else {
                sb.append(letterClass(c))
                sb.append(marks)
            }
        }
        sb.append("(?![\\u0621-\\u064A])")
        return sb.toString()
    }

    private val rules: List<Rule> by lazy {
        val out = ArrayList<Rule>()
        val seen = HashSet<String>()
        for (f in FORMS) {
            val words = f.split(' ')
            val variants = listOf(
                f.reversed(),
                words.joinToString(" ") { it.reversed() },
                words.reversed().joinToString(" ")
            )
            for (v in variants) {
                if (v == f || !seen.add(v)) continue
                try {
                    out.add(Rule(Regex(pattern(v)), f))
                } catch (_: Exception) {
                }
            }
        }
        out
    }

    fun fix(text: String): String {
        if (!on || text.length < 8) return text
        var t = text
        for (r in rules) t = r.re.replace(t, r.to)
        return t
    }
}
