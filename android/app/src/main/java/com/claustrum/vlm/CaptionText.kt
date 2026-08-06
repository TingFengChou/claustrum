package com.claustrum.vlm

/**
 * Pure text post-processing for L1 captions — host-testable (no Android deps).
 *
 * The small Gemma 3n E2B sometimes slips into English or emits emoji/pictographic
 * symbols despite a Traditional-Chinese prompt. We defend at the output boundary:
 * strip symbols, keep one sentence, and reject output that isn't real Chinese.
 */
internal object CaptionText {

    private val TERMINATORS = charArrayOf('。', '！', '？', '!', '?', '\n')

    /** True once [buf] holds a full sentence past [softMin], or reaches [max] chars. */
    fun reachedEnd(buf: CharSequence, softMin: Int, max: Int): Boolean {
        val n = buf.length
        if (n >= max) return true
        if (n < softMin) return false
        return buf[n - 1] in TERMINATORS
    }

    /**
     * Tidy a caption: strip symbols/emoji, keep the first sentence. Returns "" if what
     * remains isn't real Chinese (pure emoji / latin / punctuation) so the caller can
     * show a fallback instead of garbage.
     */
    fun clean(raw: String): String {
        var t = stripSymbols(raw).trim()
        val end = t.indexOfFirst { it in TERMINATORS }
        if (end >= 0) t = t.substring(0, end + 1)
        return if (hasHan(t)) t else ""
    }

    /** Strip emoji / pictographic symbols the model sometimes emits. */
    fun stripSymbols(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val type = Character.getType(cp)
            val isSymbol = type == Character.OTHER_SYMBOL.toInt() ||
                cp in 0x1F000..0x1FAFF ||   // emoji / pictographs / supplemental symbols
                cp in 0x2600..0x27BF ||     // misc symbols + dingbats
                cp in 0x2B00..0x2BFF ||     // misc symbols and arrows
                cp == 0x200D ||             // zero-width joiner
                cp in 0xFE00..0xFE0F        // variation selectors
            if (!isSymbol) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** True if the text contains at least one Han (CJK) ideograph. */
    fun hasHan(s: String): Boolean = s.any { it.code in 0x4E00..0x9FFF }
}
