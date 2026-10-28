package com.dan.readaloud

object TextUtil {
    private const val MAX_CARRY = 400

    private val whitespace = Regex("\\s+")
    private val spaceBeforePunct = Regex(" ([,.;:!?)\\]”’])")
    private val endsWithTerminator = Regex("[.!?…][\"'”’)\\]]*$")
    private val sentenceEnd = Regex("[.!?…][\"'”’)\\]]*\\s")
    private val sentenceSplit = Regex("(?<=[.!?…])\\s+")

    fun clean(s: String): String =
        s.replace("­", "")
            .replace(whitespace, " ")
            .replace(spaceBeforePunct, "$1")
            .trim()

    /**
     * Splits off a trailing incomplete sentence.
     * Returns (text to speak now, fragment to prepend to the next page).
     */
    fun splitTrailingFragment(s: String): Pair<String, String> {
        val t = s.trim()
        if (t.isEmpty() || endsWithTerminator.containsMatchIn(t)) return Pair(t, "")
        val last = sentenceEnd.findAll(t).lastOrNull()
        if (last == null) {
            // Whole page is one unfinished sentence; carry it only if short.
            return if (t.length <= MAX_CARRY) Pair("", t) else Pair(t, "")
        }
        val cut = last.range.last + 1
        val frag = t.substring(cut).trim()
        if (frag.length > MAX_CARRY) return Pair(t, "")
        return Pair(t.substring(0, cut).trim(), frag)
    }

    /** Packs sentences into chunks no longer than maxLen for the TTS engine. */
    fun chunks(s: String, maxLen: Int): List<String> {
        if (s.isBlank()) return emptyList()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (sentence in s.split(sentenceSplit)) {
            var piece = sentence.trim()
            if (piece.isEmpty()) continue
            while (piece.length > maxLen) {
                if (sb.isNotEmpty()) {
                    out.add(sb.toString()); sb.setLength(0)
                }
                var cut = piece.lastIndexOf(' ', maxLen)
                if (cut <= 0) cut = maxLen
                out.add(piece.substring(0, cut))
                piece = piece.substring(cut).trim()
            }
            if (piece.isEmpty()) continue
            if (sb.isNotEmpty() && sb.length + 1 + piece.length > maxLen) {
                out.add(sb.toString()); sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(piece)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out.filter { it.isNotBlank() }
    }
}
