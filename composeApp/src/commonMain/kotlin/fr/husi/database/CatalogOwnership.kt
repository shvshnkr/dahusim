package fr.husi.database

object CatalogOwnership {
    const val USER = 0
    const val GH_MANAGED = 1
    const val PROTECTED_RESERVED = 2
}

object ConnectPoolRole {
    const val ANY = 0
    const val WL = 1
    const val OPEN = 2
}

fun ProxyGroup.isCatalogDeletable(): Boolean {
    val sub = subscription ?: return true
    return sub.catalogOwnership != CatalogOwnership.PROTECTED_RESERVED
}
