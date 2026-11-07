package com.hana.agent.core.bus

import com.hana.agent.core.knowledge.AppTipsStore
import com.hana.agent.core.tool.execute
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RegisterBusAndEvolutionTest {

    @Before
    fun setup() {
        RegisterBus.clear()
        AppTipsStore.clear()
    }

    @Test
    fun testRegisterBusPutAndResolve() {
        RegisterBus.put("step1", "杭州西湖雷峰塔攻略")
        RegisterBus.put("latest", "门票40元，开放时间8:00-17:30")

        assertEquals("杭州西湖雷峰塔攻略", RegisterBus.get("\$reg:step1"))
        assertEquals("门票40元，开放时间8:00-17:30", RegisterBus.get("latest"))

        val template = "查询结果如下：\n景点：\$reg:step1\n详情：\$reg:latest\n备注：\$reg:non_exist"
        val resolved = RegisterBus.resolve(template)

        assertEquals("查询结果如下：\n景点：杭州西湖雷峰塔攻略\n详情：门票40元，开放时间8:00-17:30\n备注：\$reg:non_exist", resolved)
    }

    @Test
    fun testRegisterBusCaptureFromToolContent() {
        val content = buildJsonObject {
            put("ok", true)
            put("extracted", "用户收货地址：浙江省杭州市余杭区文一西路969号")
        }
        RegisterBus.captureFromToolContent(content, step = 3)

        assertEquals("用户收货地址：浙江省杭州市余杭区文一西路969号", RegisterBus.get("\$reg:step3"))
        assertEquals("用户收货地址：浙江省杭州市余杭区文一西路969号", RegisterBus.get("\$reg:latest"))
    }

    @Test
    fun testAppTipsStoreRecordAndRetrieve() {
        val pkg = "com.coolapk.market"
        assertTrue(AppTipsStore.getTips(pkg).isEmpty())

        AppTipsStore.recordTip(pkg, "酷安应用内详情页点击下载若未弹窗，可先轻微滑动刷新视图")
        AppTipsStore.recordTip(pkg, "酷安应用内详情页点击下载若未弹窗，可先轻微滑动刷新视图") // 幂等去重

        val tips = AppTipsStore.getTips(pkg)
        assertEquals(1, tips.size)
        assertEquals("酷安应用内详情页点击下载若未弹窗，可先轻微滑动刷新视图", tips[0])
    }

    @Test
    fun testTypeTextResolvesRegisterBusZeroCopy() = kotlinx.coroutines.test.runTest {
        val typedRecords = mutableListOf<String>()
        val screen = object : com.hana.agent.core.uistate.ScreenPort by com.hana.agent.core.tool.FakeScreen() {
            override suspend fun typeText(ref: String?, snapshotId: String?, text: String): com.hana.agent.core.uistate.ActionResult {
                typedRecords += text
                return com.hana.agent.core.uistate.ActionResult.Ok(verification = com.hana.agent.core.uistate.Verification.MATCH)
            }
        }
        RegisterBus.put("clipboard_token", "AKIAIOSFODNN7EXAMPLE_SECRET_KEY")

        val d = com.hana.agent.core.assembly.toolDispatcher(screen)
        d.execute(com.hana.agent.core.model.Part.ToolCall("t1", "phone_type_text", """{"text":"${'$'}reg:clipboard_token"}"""))

        assertEquals(1, typedRecords.size)
        assertEquals("AKIAIOSFODNN7EXAMPLE_SECRET_KEY", typedRecords[0])
    }
}
