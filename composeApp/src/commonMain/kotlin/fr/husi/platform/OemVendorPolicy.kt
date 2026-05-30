package fr.husi.platform

/** OEM background limits beyond standard Android battery optimization. */
enum class VendorBackgroundHint {
    None,
    HuaweiLaunchManager,
    XiaomiAutostart,
}

/** Vendor-specific background-restriction hints (unit-testable). */
object OemVendorPolicy {

    fun isHuaweiFamilyManufacturer(manufacturer: String?, brand: String? = null): Boolean {
        val m = manufacturer.orEmpty().trim().lowercase()
        val b = brand.orEmpty().trim().lowercase()
        return m in HUAWEI_FAMILY || b in HUAWEI_FAMILY
    }

    fun isXiaomiFamilyManufacturer(manufacturer: String?, brand: String? = null): Boolean {
        val m = manufacturer.orEmpty().trim().lowercase()
        val b = brand.orEmpty().trim().lowercase()
        return m in XIAOMI_FAMILY || b in XIAOMI_FAMILY
    }

    fun resolveVendorBackgroundHint(
        manufacturer: String?,
        brand: String?,
        batteryOptimizationIgnored: Boolean,
    ): VendorBackgroundHint {
        if (!batteryOptimizationIgnored) return VendorBackgroundHint.None
        if (isHuaweiFamilyManufacturer(manufacturer, brand)) {
            return VendorBackgroundHint.HuaweiLaunchManager
        }
        if (isXiaomiFamilyManufacturer(manufacturer, brand)) {
            return VendorBackgroundHint.XiaomiAutostart
        }
        return VendorBackgroundHint.None
    }

    private val HUAWEI_FAMILY = setOf("huawei", "honor")
    private val XIAOMI_FAMILY = setOf("xiaomi", "redmi", "poco")
}
