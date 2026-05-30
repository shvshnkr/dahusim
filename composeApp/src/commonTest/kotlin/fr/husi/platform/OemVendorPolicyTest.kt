package fr.husi.platform

import kotlin.test.Test
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
    fun nonHuaweiManufacturers() {
        assertFalse(OemVendorPolicy.isHuaweiFamilyManufacturer("samsung"))
        assertFalse(OemVendorPolicy.isHuaweiFamilyManufacturer("Xiaomi"))
        assertFalse(OemVendorPolicy.isHuaweiFamilyManufacturer(null, null))
    }
}
