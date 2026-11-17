package com.wangzan101.mika.personal.live

/**
 * 语音模式单行字幕分句递进格式器：
 * - 将流式增长的长段转写按自然分句标点切分，始终提取当前正在播报的最新分句；
 * - 若开头语气词过短（如「好的，」「嗯，」<= 4 字），自动与紧随其后的首句合并，避免语气词单独闪跳；
 * - 若单个无标点分句超出单行可视长度上限 [maxUnits]，平滑截取尾部窗口并冠以省略号，确保最新说出的字词永远不被右侧截断遮蔽。
 */
object LiveSubtitleFormatter {
    private val CLAUSE_DELIMITERS = setOf(
        '。', '！', '？', '；', '，', '…',
        '.', '!', '?', ';', ',', '\n', '\r',
    )

    private const val SHORT_INTERJECTION_UNITS = 5

    fun extractActiveClause(rawText: String, maxUnits: Int = 28): String {
        val normalized = rawText.replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) return ""

        val clauses = splitClauses(normalized)
        if (clauses.isEmpty()) return tailWindow(normalized, maxUnits)

        val merged = mergeShortLeadingInterjection(clauses)
        val latest = merged.last().trim()
        return tailWindow(latest, maxUnits)
    }

    private fun splitClauses(text: String): List<String> {
        val result = ArrayList<String>(8)
        val buf = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val isAsciiDotInsideToken = ch == '.' &&
                i > 0 && i + 1 < text.length &&
                text[i - 1].isLetterOrDigit() && text[i + 1].isLetterOrDigit()
            val isNumericComma = ch == ',' &&
                i > 0 && i + 1 < text.length &&
                text[i - 1].isDigit() && text[i + 1].isDigit()

            if (!isAsciiDotInsideToken && !isNumericComma && ch in CLAUSE_DELIMITERS) {
                // Keep trailing terminal punctuation (?, !, ？, ！) attached for natural tone, drop commas/periods at split
                if (ch == '？' || ch == '！' || ch == '?' || ch == '!') {
                    buf.append(ch)
                }
                val seg = buf.toString().trim()
                if (seg.isNotEmpty()) {
                    result += seg
                }
                buf.setLength(0)
                // Consume consecutive delimiters
                while (i + 1 < text.length && text[i + 1] in CLAUSE_DELIMITERS) {
                    i++
                }
            } else {
                buf.append(ch)
            }
            i++
        }
        val tail = buf.toString().trim()
        if (tail.isNotEmpty()) {
            result += tail
        }
        return result
    }

    private fun mergeShortLeadingInterjection(clauses: List<String>): List<String> {
        if (clauses.size < 2) return clauses
        val first = clauses[0]
        if (visualUnits(first) <= SHORT_INTERJECTION_UNITS) {
            val second = clauses[1]
            val lastChar = first.last()
            val sep = when {
                lastChar == '？' || lastChar == '！' -> ""
                lastChar == '?' || lastChar == '!' -> " "
                lastChar.code > 127 || second.first().code > 127 -> "，"
                else -> ", "
            }
            val combined = "$first$sep$second"
            if (clauses.size == 2) {
                return listOf(combined)
            }
            val out = ArrayList<String>(clauses.size - 1)
            out += combined
            for (idx in 2 until clauses.size) out += clauses[idx]
            return out
        }
        return clauses
    }

    private fun tailWindow(clause: String, maxUnits: Int): String {
        if (maxUnits <= 4 || visualUnits(clause) <= maxUnits) return clause
        var units = 0
        var startIdx = clause.length
        while (startIdx > 0) {
            val prev = Character.offsetByCodePoints(clause, startIdx, -1)
            val cp = clause.codePointAt(prev)
            val w = if (cp > 0x2E7F) 2 else 1
            if (units + w > (maxUnits - 1) * 2) break
            units += w
            startIdx = prev
        }
        // If mid-ASCII word, align forward to next space if nearby so English words aren't sliced in half
        if (startIdx > 0 && startIdx < clause.length && clause[startIdx].isLetterOrDigit() && clause[startIdx - 1].isLetterOrDigit()) {
            val nextSpace = clause.indexOf(' ', startIdx)
            if (nextSpace in (startIdx + 1)..(startIdx + 10) && nextSpace + 1 < clause.length) {
                startIdx = nextSpace + 1
            }
        }
        return "…" + clause.substring(startIdx).trimStart()
    }

    /** Approximate CJK char = 1 unit, 2 ASCII chars = 1 unit. */
    private fun visualUnits(s: String): Int {
        var halfUnits = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            halfUnits += if (cp > 0x2E7F) 2 else 1
            i += Character.charCount(cp)
        }
        return (halfUnits + 1) / 2
    }
}
