package com.example.sitedraft.cloud

/**
 * The rules for changing who a project is shared with, as pure functions (SharingTest).
 * The Firestore security rules enforce the same shape server-side.
 */
object Sharing {
    private val emailPattern = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun normalize(email: String): String = email.trim().lowercase()

    fun isValidEmail(email: String): Boolean = emailPattern.matches(normalize(email))

    /**
     * Adds [email] (or changes its role). Throws [IllegalArgumentException] with a message for
     * the user when the address is not usable.
     */
    fun add(sharing: ProjectSharing, email: String, canEdit: Boolean): ProjectSharing {
        val e = normalize(email)
        require(isValidEmail(e)) { "That does not look like an email address." }
        require(e != sharing.ownerEmail?.let(::normalize)) { "That is your own account." }
        val members = (sharing.memberEmails.map(::normalize) + e).distinct().sorted()
        val editors = sharing.editorEmails.map(::normalize).filter { it != e && it in members }
            .let { if (canEdit) it + e else it }.distinct().sorted()
        return sharing.copy(memberEmails = members, editorEmails = editors)
    }

    fun remove(sharing: ProjectSharing, email: String): ProjectSharing {
        val e = normalize(email)
        return sharing.copy(
            memberEmails = sharing.memberEmails.map(::normalize).filter { it != e },
            editorEmails = sharing.editorEmails.map(::normalize).filter { it != e },
        )
    }

    fun canEdit(sharing: ProjectSharing, email: String): Boolean = normalize(email) in sharing.editorEmails.map(::normalize)
}

/**
 * The local name for a project someone shared with this user: "<name> (<owner>)", made unique
 * against [taken] so it never collides with the user's own projects or another share.
 */
fun sharedLocalName(cloudName: String, ownerEmail: String?, taken: Set<String>): String {
    val owner = ownerEmail?.substringBefore('@')?.takeIf { it.isNotBlank() } ?: "shared"
    val base = "$cloudName ($owner)"
    if (base !in taken) return base
    var n = 2
    while ("$base $n" in taken) n++
    return "$base $n"
}

/**
 * The merge rule for a project shared with this user. An editor follows plain last-write-wins.
 * A viewer never pushes: any difference from the cloud copy (including local edits, which
 * cannot be saved back) is resolved by pulling.
 */
fun chooseSharedSyncAction(canEdit: Boolean, localUpdatedAt: Long?, remoteUpdatedAt: Long?): SyncAction {
    if (canEdit) return chooseSyncAction(localUpdatedAt, remoteUpdatedAt)
    if (remoteUpdatedAt == null) return SyncAction.NONE
    return if (localUpdatedAt == remoteUpdatedAt) SyncAction.NONE else SyncAction.PULL
}
