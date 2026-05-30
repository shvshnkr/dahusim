package fr.husi.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OemVendorPolicyTest {

    @Test
    fun huaweiFamilyManufacturers() {
        assertTrue(OemVendorPolicy.isHuaweiFamilyManufacturer("HUAWEI"))
        assertTrue(OemVendorPolicy.isHuaweiFamilyManufacturer("Huawei"))
        assertTrue(OemVendorPolicy.isHuaweiFamilyManufacturer("HONOR"))
        assertTrue(OemVendorPolicy.isHuaweiFamilyManufacturer(manufacturer = "unknown", brand = "honor"))
    }

    @Test
    fun xiaomiFamilyManufacturers() {
        assertTrue(OemVendorPolicy.isXiaomiFamilyManufacturer("Xiaomi"))
        assertTrue(OemVendorPolicy.isXiaomiFamilyManufacturer("Redmi"))
        assertTrue(OemVendorPolicy.isXiaomiFamilyManufacturer(manufacturer = "unknown", brand = "POCO"))
    }

    @Test
    fun nonVendorManufacturers() {
        assertFalse(OemVendorPolicy.isHuaweiFamilyManufacturer("samsung"))
        assertFalse(OemVendorPolicy.isHuaweiFamilyManufacturer("Xiaomi"))
        assertFalse(OemVendorPolicy.isXiaomiFamilyManufacturer("Huawei"))
        assertFalse(OemVendorPolicy.isHuaweiFamilyManufacturer(null, null))
    }

    @Test
    fun resolveHintRequiresBatteryOptimizationOff() {
        assertEquals(
            VendorBackgroundHint.None,
            OemVendorPolicy.resolveVendorBackgroundHint("Huawei", "Huawei", batteryOptimizationIgnored = false),
        )
    }

    @Test
    fun resolveHintForHuaweiAndXiaomi() {
        assertEquals(
            VendorBackgroundHint.HuaweiLaunchManager,
            OemVendorPolicy.resolveVendorBackgroundHint("Huawei", "Huawei", batteryOptimizationIgnored = true),
        )
        assertEquals(
            VendorBackgroundHint.XiaomiAutostart,
            OemVendorPolicy.resolveVendorBackgroundHint("Xiaomi", "Redmi", batteryOptimizationIgnored = true),
        )
    }
}
