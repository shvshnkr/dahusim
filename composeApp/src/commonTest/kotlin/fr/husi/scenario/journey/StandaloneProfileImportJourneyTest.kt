package fr.husi.scenario.journey

import fr.husi.database.DataStore
import fr.husi.database.SagerDatabase
import fr.husi.database.isBuiltinRelayGroup
import fr.husi.fmt.FmtTestConstant
import fr.husi.fmt.trojan.parseTrojan
import fr.husi.ui.ImportLinkInteractor
import fr.husi.ui.ImportTargetResolver.isUserImportTarget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandaloneProfileImportJourneyTest : FeatureJourneyTest() {

    override suspend fun postStartKoin() {
        super.postStartKoin()
        DataStore.configurationStore.reset()
        FeatureJourneyHarness.clear()
    }

    @Test
    fun standaloneShareLandsInUserBasicGroup() = runBlocking {
        val bean = parseTrojan(FmtTestConstant.TROJAN_URL)
        val count = ImportLinkInteractor().importStandaloneProfiles(listOf(bean))
        assertTrue(count == 1)

        val groupId = DataStore.selectedGroup
        val group = SagerDatabase.groupDao.getById(groupId).first()
            ?: error("import target group missing")
        assertTrue(group.isUserImportTarget())
        assertFalse(group.isBuiltinRelayGroup())

        val profiles = SagerDatabase.proxyDao.getByGroup(groupId).first()
        assertTrue(profiles.isNotEmpty())
    }
}
