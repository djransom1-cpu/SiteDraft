package com.example.sitedraft

import com.example.sitedraft.ui.help.HelpContent
import com.example.sitedraft.ui.help.HelpSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpSearchTest {
    @Test
    fun blankQueryShowsEveryTopicInOrder() {
        assertEquals(HelpContent.topics, HelpSearch.search("   "))
    }

    @Test
    fun titleMatchesComeFirst() {
        val r = HelpSearch.search("elevation")
        assertTrue(r.isNotEmpty())
        assertEquals("Elevations", r.first().title)
    }

    @Test
    fun everyWordMustMatchAndCaseIsIgnored() {
        val r = HelpSearch.search("DOOR Height")
        assertTrue(r.isNotEmpty())
        assertTrue(r.all { t ->
            val h = (t.title + " " + t.keywords + " " + t.lines.joinToString(" ")).lowercase()
            "door" in h && "height" in h
        })
    }

    @Test
    fun keywordsFindTopicsTheTextDoesNotNameExactly() {
        assertEquals("Laser measuring (tablet)", HelpSearch.search("glm").first().title)
        assertEquals("Getting started", HelpSearch.search("login").first().title)
    }

    @Test
    fun nothingFound() {
        assertTrue(HelpSearch.search("xylophone").isEmpty())
    }

    @Test
    fun matchRangesMergeOverlaps() {
        assertEquals(listOf(0..3), HelpSearch.matchRanges("Door", listOf("do", "door")))
        assertEquals(listOf(4..7, 9..12), HelpSearch.matchRanges("the door door", listOf("door")))
    }

    @Test
    fun everyTopicHasText() {
        assertEquals(14, HelpContent.topics.size)
        assertTrue(HelpContent.topics.all { it.lines.isNotEmpty() && it.title.isNotBlank() })
        assertEquals(HelpContent.topics.size, HelpContent.topics.map { it.title }.toSet().size)
    }
}
