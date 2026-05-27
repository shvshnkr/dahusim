package fr.husi.fmt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParseRuleProcessRulesTest {

    @Test
    fun `parseRuleProcessRules should route plain entries to packages on android mode`() {
        val (packageNames, processRules) = parseRuleProcessRules(
            packages = setOf(" com.example.app ", "name:proc-a", "uid:1000"),
            defaultToPackage = true,
        )

        assertEquals(setOf("com.example.app"), packageNames)
        assertEquals(2, processRules.size)
        assertTrue(processRules.any { it.type == RuleItem.TYPE_FLAG_NAME && it.content == "proc-a" })
        assertTrue(processRules.any { it.type == RuleItem.TYPE_USER_ID && it.content == "1000" })
    }

    @Test
    fun `parseRuleProcessRules should route plain entries to process path on desktop mode`() {
        val (packageNames, processRules) = parseRuleProcessRules(
            packages = setOf("com.example.app", "path:/usr/bin/tool", "name:proc-a"),
            defaultToPackage = false,
        )

        assertTrue(packageNames.isEmpty())
        assertTrue(processRules.any { it.type == RuleItem.TYPE_FLAG_PATH && it.content == "com.example.app" })
        assertTrue(processRules.any { it.type == RuleItem.TYPE_FLAG_PATH && it.content == "/usr/bin/tool" })
        assertTrue(processRules.any { it.type == RuleItem.TYPE_FLAG_NAME && it.content == "proc-a" })
    }
}
