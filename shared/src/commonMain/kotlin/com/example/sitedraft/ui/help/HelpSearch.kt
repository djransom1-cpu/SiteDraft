package com.example.sitedraft.ui.help

/** Finds help topics for what someone types in the Help panel's search box. */
object HelpSearch {
    /** The query split into lower-case words; blank when nothing is typed. */
    fun words(query: String): List<String> =
        query.lowercase().split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun haystack(topic: HelpTopic): String =
        (topic.title + " " + topic.keywords + " " + topic.lines.joinToString(" ")).lowercase()

    /**
     * Topics where EVERY word typed appears somewhere (title, keywords or text), so "door height"
     * finds the page that has both. Pages with the words in their title come first. A blank
     * query returns every topic in guide order.
     */
    fun search(query: String, topics: List<HelpTopic> = HelpContent.topics): List<HelpTopic> {
        val w = words(query)
        if (w.isEmpty()) return topics
        return topics
            .filter { t -> haystack(t).let { h -> w.all { h.contains(it) } } }
            .sortedByDescending { t -> w.count { t.title.lowercase().contains(it) } }
    }

    /** Where each typed word appears in [text] (start, end), for highlighting; overlaps merged. */
    fun matchRanges(text: String, words: List<String>): List<IntRange> {
        val lower = text.lowercase()
        val ranges = mutableListOf<IntRange>()
        for (w in words) {
            var i = lower.indexOf(w)
            while (i >= 0) {
                ranges += i until i + w.length
                i = lower.indexOf(w, i + w.length)
            }
        }
        val merged = mutableListOf<IntRange>()
        for (r in ranges.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + 1) merged[merged.size - 1] = last.first..maxOf(last.last, r.last)
            else merged += r
        }
        return merged
    }
}
