package com.hana.agent.core.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityListTest {
    private val hana = PermissionCommands.accessibilityService("com.hana.agent")
    private val hanaLong = "com.hana.agent/com.hana.agent.a11y.HanaAccessibilityService"
    private val other = "com.example.reader/.ReaderService"
    private val other2 = "org.sample.keys/org.sample.keys.KeyService"

    @Test
    fun emptyOrUnsetValues() {
        listOf(null, "", "   ", "null", "null\n", ":", " : ").forEach { raw ->
            assertEquals("raw=$raw", emptyList<String>(), AccessibilityList.entries(raw))
            assertFalse(AccessibilityList.contains(raw, hana))
            assertEquals(hana, AccessibilityList.with(raw, hana))
            assertEquals("", AccessibilityList.without(raw, hana))
        }
    }

    @Test
    fun appendKeepsOtherServicesInOrder() {
        assertEquals("$other:$hana", AccessibilityList.with(other, hana))
        assertEquals("$other:$other2:$hana", AccessibilityList.with("$other:$other2\n", hana))
    }

    @Test
    fun alreadyPresentIsNotDuplicated() {
        assertEquals("$other:$hana", AccessibilityList.with("$other:$hana", hana))
        // 系统里存的是完整类名写法,也算已经有了
        assertEquals("$hanaLong:$other", AccessibilityList.with("$hanaLong:$other", hana))
        assertTrue(AccessibilityList.contains(hanaLong, hana))
    }

    @Test
    fun removeKeepsOthersAndHandlesBothSpellings() {
        assertEquals("$other:$other2", AccessibilityList.without("$other:$hana:$other2", hana))
        assertEquals(other, AccessibilityList.without("$hanaLong:$other", hana))
        assertEquals(other, AccessibilityList.without(other, hana))
        assertEquals("", AccessibilityList.without(hana, hana))
    }

    @Test
    fun strayColonsAndSpacesAreCleanedUp() {
        assertEquals("$other:$other2:$hana", AccessibilityList.with(":$other:: $other2 :", hana))
        assertEquals("$other:$other2", AccessibilityList.without("$other::$hana:$other2:", hana))
    }

    @Test
    fun differentServiceOfSamePackageIsNotConfused() {
        val sibling = "com.hana.agent/.a11y.OtherService"
        assertFalse(AccessibilityList.contains(sibling, hana))
        assertEquals(sibling, AccessibilityList.without("$sibling:$hana", hana))
    }
}
