package fr.husi.bg

import fr.husi.libcore.ConnectionOwner
import fr.husi.libcore.StringIterator

internal const val UNKNOWN_OWNER_UID = -1

internal fun buildConnectionOwner(uid: Int, packages: StringIterator?): ConnectionOwner {
    if (uid == UNKNOWN_OWNER_UID) return ConnectionOwner(uid, null)
    return ConnectionOwner(uid, packages)
}
