package fr.husi.platform

/** Vendor-specific background-restriction hints (unit-testable). */
object OemVendorPolicy {

    fun isHuaweiFamilyManufacturer(manufacturer: String?, brand: String? = null): Boolean {
        val m = manufacturer.orEmpty().trim().lowercase()
        val b = brand.orEmpty().trim().lowercase()
        return m in HUAWEI_FAMILY || b in HUAWEI_FAMILY
    }

    private val HUAWEI_FAMILY = setOf("huawei", "honor")
}
