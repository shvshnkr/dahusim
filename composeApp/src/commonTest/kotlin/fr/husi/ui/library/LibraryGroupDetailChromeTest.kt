package fr.husi.ui.library

import fr.husi.GroupType
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryGroupDetailChromeTest {

    @Test
    fun `subscription group uses subscription action strip`() {
        assertEquals(
            LibraryGroupDetailActionMode.Subscription,
            libraryGroupDetailActionMode(GroupType.SUBSCRIPTION),
        )
    }

    @Test
    fun `basic group uses basic action strip`() {
        assertEquals(
            LibraryGroupDetailActionMode.Basic,
            libraryGroupDetailActionMode(GroupType.BASIC),
        )
    }
}
