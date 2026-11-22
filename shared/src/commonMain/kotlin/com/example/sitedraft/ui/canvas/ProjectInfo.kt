package com.example.sitedraft.ui.canvas

import kotlinx.serialization.Serializable

/** One revision of the drawings: its number (1, 2, A...), date and what changed. */
@Serializable
data class Revision(
    val number: String = "",
    val date: String = "",
    val description: String = ""
)

/**
 * The job's details for the title block on every sheet (Dan, Nov 22 2026): who it's for, where,
 * when the drawings were started, who drew them, and the revisions since. Entered once under
 * Projects / Jobs; every sheet reads it, so a change shows on the whole set. New in 1.0.36.
 */
@Serializable
data class ProjectInfo(
    val clientName: String = "",
    val address: String = "",
    /** The date the drawings were started, as typed (e.g. 11/22/2026). */
    val startDate: String = "",
    val drawnBy: String = "",
    /** The company the drawings are by, across the top of the title block (e.g. Ransom Carpentry). */
    val company: String = "",
    val revisions: List<Revision> = emptyList()
) {
    /** The next revision number: one more than the highest number so far, or 1. */
    fun nextRevisionNumber(): String =
        ((revisions.mapNotNull { it.number.trim().toIntOrNull() }.maxOrNull() ?: 0) + 1).toString()
}
