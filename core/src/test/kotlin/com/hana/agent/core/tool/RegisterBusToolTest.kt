package com.hana.agent.core.tool

import com.hana.agent.core.bus.RegisterBus
import com.hana.agent.core.bus.RegisterResolveTool
import com.hana.agent.core.bus.RegisterStoreTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RegisterBusToolTest {

    @Before
    fun setUp() {
        RegisterBus.clear()
    }

    @After
    fun tearDown() {
        RegisterBus.clear()
    }

    @Test
    fun testRegisterStoreToolExecution() = runBlocking {
        val args = buildJsonObject {
            put("key", "flight_no")
            put("value", "MU5137")
        }

        val outcome = RegisterStoreTool.execute(args)
        val content = outcome.content
        assertEquals("true", content["ok"]?.jsonPrimitive?.content)
        assertEquals("\$reg:flight_no", content["key"]?.jsonPrimitive?.content)
        assertEquals("6", content["length"]?.jsonPrimitive?.content)

        assertEquals("MU5137", RegisterBus.get("\$reg:flight_no"))
        assertEquals("MU5137", RegisterBus.get("flight_no"))
    }

    @Test
    fun testRegisterResolveToolExecution() = runBlocking {
        RegisterBus.put("order_id", "20261108998877")

        val args = buildJsonObject {
            put("key", "order_id")
        }

        val outcome = RegisterResolveTool.execute(args)
        val content = outcome.content
        assertEquals("true", content["ok"]?.jsonPrimitive?.content)
        assertEquals("\$reg:order_id", content["key"]?.jsonPrimitive?.content)
        assertEquals("20261108998877", content["value"]?.jsonPrimitive?.content)

        // 测试查询不存在的 key
        val missingArgs = buildJsonObject {
            put("key", "not_exist_key")
        }
        val missingOutcome = RegisterResolveTool.execute(missingArgs)
        assertEquals("false", missingOutcome.content["ok"]?.jsonPrimitive?.content)
        assertEquals("register_not_found", missingOutcome.content["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun testTypeTextTransparentZeroCopyExpansion() {
        RegisterBus.put("flight_no", "MU5137")
        RegisterBus.put("seat", "12A")

        val template = "您的航班是\$reg:flight_no，座位为\$reg:seat，请提前登机"
        val resolved = RegisterBus.resolve(template)
        assertEquals("您的航班是MU5137，座位为12A，请提前登机", resolved)

        // 测试带语义标签形式 <航班号:$reg:flight_no>
        val tagged = "预订信息：<航班号:\$reg:flight_no>"
        val resolvedTag = RegisterBus.resolve(tagged)
        assertEquals("预订信息：MU5137", resolvedTag)
    }

    @Test
    fun testRegisterKeySanitizationAndCapEnforcement() = runBlocking {
        val dirtyKey = "key\r\n\t_test\u0000"
        val hugeValue = "A".repeat(15000)

        val args = buildJsonObject {
            put("key", dirtyKey)
            put("value", hugeValue)
        }

        val outcome = RegisterStoreTool.execute(args)
        val content = outcome.content
        assertEquals("true", content["ok"]?.jsonPrimitive?.content)
        assertEquals("\$reg:key_test", content["key"]?.jsonPrimitive?.content)
        assertEquals("12000", content["length"]?.jsonPrimitive?.content)

        val stored = RegisterBus.get("key_test")
        assertNotNull(stored)
        assertEquals(12000, stored?.length)
    }

    @Test
    fun testToolMissingArgumentsValidation() = runBlocking {
        val emptyArgs = buildJsonObject {}
        val storeResult = RegisterStoreTool.execute(emptyArgs)
        assertEquals("false", storeResult.content["ok"]?.jsonPrimitive?.content)
        assertEquals("missing_key", storeResult.content["error"]?.jsonPrimitive?.content)

        val resolveResult = RegisterResolveTool.execute(emptyArgs)
        assertEquals("false", resolveResult.content["ok"]?.jsonPrimitive?.content)
        assertEquals("missing_key", resolveResult.content["error"]?.jsonPrimitive?.content)
    }
}
