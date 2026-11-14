package com.hana.agent.core.gate

import com.hana.agent.core.risk.IrrevAction
import com.hana.agent.core.uistate.ScreenSnapshot
import com.hana.agent.core.uistate.UiElement
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三级风险门禁分类、TOCTOU 窗口焦点漂移锁、向后兼容性与事后审计回执单单元测试。
 */
class RiskTierGateAndReceiptTest {

    private fun el(name: String, editable: Boolean = false, top: Int = 0) =
        UiElement("@e1", "v", name, 0, 0, listOf(0, top, 999, top + 60), null, true, editable, false)

    private fun chatScreen(app: String = "com.chat", peer: String = "张三", typed: String = "明天见") =
        ScreenSnapshot(
            snapshotId = "s1",
            packageName = app,
            elements = listOf(
                el(peer, top = 30),
                el("¥25", top = 800),
                el(typed, editable = true, top = 900),
                el("发送", top = 900),
            ),
            width = 1080,
            height = 2400,
        )

    @Test
    fun classifyRiskTierCoversL1L2L3AndEdgeCases() {
        // L1_AUTO：浏览、搜索、加购、草稿填写等低风险动作
        assertEquals(RiskTier.L1_AUTO, classifyRiskTier(IrrevAction.other, "浏览商品详情"))
        assertEquals(RiskTier.L1_AUTO, classifyRiskTier(IrrevAction.other, "搜索蓝牙耳机"))
        assertEquals(RiskTier.L1_AUTO, classifyRiskTier(IrrevAction.other, "加入购物车"))
        assertEquals(RiskTier.L1_AUTO, classifyRiskTier(IrrevAction.other, "草稿填写"))
        assertEquals(RiskTier.L1_AUTO, classifyRiskTier(IrrevAction.other, ""))
        assertEquals(RiskTier.L1_AUTO, IrreversibleDetector.classifyRiskTier(null))
        // 避免英文词根误匹配：display / replay 不应误触发 pay
        assertEquals(RiskTier.L1_AUTO, classifyRiskTier(IrrevAction.other, "display settings preview"))
        assertEquals(RiskTier.L1_AUTO, classifyRiskTier(IrrevAction.other, "replay history"))

        // L2_SOFT_COUNTDOWN：发送消息、发帖/评论、新建或修改闹钟/日历
        assertEquals(RiskTier.L2_SOFT_COUNTDOWN, classifyRiskTier(IrrevAction.send))
        assertEquals(RiskTier.L2_SOFT_COUNTDOWN, classifyRiskTier(IrrevAction.send, "发送微信消息给张三"))
        assertEquals(RiskTier.L2_SOFT_COUNTDOWN, classifyRiskTier(IrrevAction.submit, "提交岗位投递"))
        assertEquals(RiskTier.L2_SOFT_COUNTDOWN, classifyRiskTier(IrrevAction.other, "发帖分享"))
        assertEquals(RiskTier.L2_SOFT_COUNTDOWN, classifyRiskTier(IrrevAction.other, "发表评论"))
        assertEquals(RiskTier.L2_SOFT_COUNTDOWN, classifyRiskTier(IrrevAction.other, "新建闹钟 07:30"))
        assertEquals(RiskTier.L2_SOFT_COUNTDOWN, classifyRiskTier(IrrevAction.other, "修改日历日程"))

        // L3_HARD_SLIDE：付款、转账、下单、删除、退群、修改安全设置
        assertEquals(RiskTier.L3_HARD_SLIDE, classifyRiskTier(IrrevAction.pay))
        assertEquals(RiskTier.L3_HARD_SLIDE, classifyRiskTier(IrrevAction.order, "提交订单"))
        assertEquals(RiskTier.L3_HARD_SLIDE, classifyRiskTier(IrrevAction.delete, "删除聊天记录"))
        assertEquals(RiskTier.L3_HARD_SLIDE, classifyRiskTier(IrrevAction.other, "向李四转账 500 元"))
        assertEquals(RiskTier.L3_HARD_SLIDE, classifyRiskTier(IrrevAction.other, "退群"))
        assertEquals(RiskTier.L3_HARD_SLIDE, classifyRiskTier(IrrevAction.other, "退出群聊"))
        assertEquals(RiskTier.L3_HARD_SLIDE, classifyRiskTier(IrrevAction.other, "修改安全设置"))
        assertEquals(RiskTier.L3_HARD_SLIDE, classifyRiskTier(IrrevAction.other, "删除闹钟"))
        assertEquals(RiskTier.L3_HARD_SLIDE, classifyRiskTier(IrrevAction.other, "change password"))
        assertEquals(
            RiskTier.L3_HARD_SLIDE,
            IrreversibleDetector.classifyRiskTier(Irreversible(IrrevAction.pay, "确认支付")),
        )
    }

    @Test
    fun confirmationRequestPreservesBackwardCompatibleDefaults() {
        val legacy = ConfirmationRequest(IrrevAction.send, "张三", "明天见", "¥25", "com.chat")
        assertEquals(RiskTier.L3_HARD_SLIDE, legacy.riskTier)
        assertEquals(null, legacy.windowGeneration)
        assertFalse(legacy.fromUntrustedSource)

        val enriched = ConfirmationRequest(
            action = IrrevAction.send,
            target = "张三",
            content = "明天见",
            amount = "¥25",
            app = "com.chat",
            riskTier = RiskTier.L2_SOFT_COUNTDOWN,
            windowGeneration = 42L,
            fromUntrustedSource = true,
        )
        assertEquals(RiskTier.L2_SOFT_COUNTDOWN, enriched.riskTier)
        assertEquals(42L, enriched.windowGeneration)
        assertTrue(enriched.fromUntrustedSource)
    }

    @Test
    fun toctouWindowGenerationMismatchBlocksAndConsumesGrant() {
        val gate = ConfirmationGate()
        val req = ConfirmationRequest(
            action = IrrevAction.send,
            target = "张三",
            content = "明天见",
            amount = "¥25",
            app = "com.chat",
            riskTier = RiskTier.L2_SOFT_COUNTDOWN,
            windowGeneration = 100L,
            fromUntrustedSource = true,
        )
        assertEquals(DeclareResult.Pending, gate.declare(req, "com.chat"))
        gate.decide(approved = true, step = 1)

        // 窗口代次发生跳变（例如底层弹出新窗口或切换会话）：拒绝放行并明确返回 TOCTOU 原因
        val blocked = gate.permits(
            hit = Irreversible(IrrevAction.send, "发送"),
            screen = chatScreen(),
            step = 2,
            currentWindowGeneration = 101L,
        )
        assertEquals(Permit.Mismatch("window changed during authorization (TOCTOU)"), blocked)
        // 授权已被消费作废，不可重复使用
        assertEquals(
            Permit.NoGrant,
            gate.permits(Irreversible(IrrevAction.send, "发送"), chatScreen(), 2, currentWindowGeneration = 100L),
        )
    }

    @Test
    fun toctouWindowGenerationMatchingAndNullFallbackAllow() {
        // 窗口代次一致时放行
        val matchedGate = ConfirmationGate()
        val reqWithGen = ConfirmationRequest(
            action = IrrevAction.send,
            target = "张三",
            content = "明天见",
            amount = "¥25",
            app = "com.chat",
            riskTier = RiskTier.L2_SOFT_COUNTDOWN,
            windowGeneration = 77L,
        )
        assertEquals(DeclareResult.Pending, matchedGate.declare(reqWithGen, "com.chat"))
        matchedGate.decide(approved = true, step = 1)
        assertEquals(
            Permit.Allowed,
            matchedGate.permits(
                hit = Irreversible(IrrevAction.send, "发送"),
                screen = chatScreen(),
                step = 2,
                currentWindowGeneration = 77L,
            ),
        )

        // 请求或当前代次任一方为 null 时保持向后兼容
        val legacyGate = ConfirmationGate()
        val legacyReq = ConfirmationRequest(IrrevAction.send, "张三", "明天见", "¥25", "com.chat")
        assertEquals(DeclareResult.Pending, legacyGate.declare(legacyReq, "com.chat"))
        legacyGate.decide(approved = true, step = 1)
        assertEquals(
            Permit.Allowed,
            legacyGate.permits(
                hit = Irreversible(IrrevAction.send, "发送"),
                screen = chatScreen(),
                step = 2,
                currentWindowGeneration = 88L,
            ),
        )

        val nullableCurrentGate = ConfirmationGate()
        assertEquals(DeclareResult.Pending, nullableCurrentGate.declare(reqWithGen, "com.chat"))
        nullableCurrentGate.decide(approved = true, step = 1)
        assertEquals(
            Permit.Allowed,
            nullableCurrentGate.permits(
                hit = Irreversible(IrrevAction.send, "发送"),
                screen = chatScreen(),
                step = 2,
            ),
        )
    }

    @Test
    fun actionReceiptBuilderTracksStepsGatesUndoHintsAndSerialization() {
        var now = 1000L
        val builder = ActionReceiptBuilder(
            taskId = "task-receipt-01",
            goal = "比价加购蓝牙耳机并设置明早闹钟",
            startedAtMs = now,
            clock = { now },
        )

        now = 1500L
        val cartUndo = UndoActionHint.cartItem(
            label = "从购物车移除蓝牙耳机",
            itemName = "降噪蓝牙耳机",
            targetApp = "com.shop.app",
            itemId = "sku-99",
        )
        builder.recordStep(
            actionSummary = "搜索并把降噪蓝牙耳机加入购物车",
            targetApp = "com.shop.app",
            riskTier = RiskTier.L1_AUTO,
            durationMs = 500L,
            undoHint = cartUndo,
        )

        now = 2800L
        val alarmUndo = UndoActionHint.alarm(
            label = "撤销 07:30 闹钟",
            hour = 7,
            minute = 30,
            alarmId = "alarm-730",
        )
        builder.recordStep(
            actionSummary = "设置明早 07:30 闹钟",
            targetApp = "com.android.deskclock",
            riskTier = RiskTier.L2_SOFT_COUNTDOWN,
            gateTriggered = true,
            gateDecision = "countdown_approved",
            durationMs = 1300L,
            undoHint = alarmUndo,
        )

        now = 4200L
        val untrustedOrder = ConfirmationRequest(
            action = IrrevAction.order,
            target = "旗舰店",
            content = "降噪蓝牙耳机 x1",
            amount = "¥199",
            app = "com.shop.app",
            riskTier = RiskTier.L3_HARD_SLIDE,
            windowGeneration = 9L,
            fromUntrustedSource = true,
        )
        builder.recordGateRequest(untrustedOrder, approved = true, durationMs = 1400L)

        // 追加系统开关与日历撤销提示，并验证重复提示去重
        val calendarUndo = UndoActionHint.calendarEvent(
            label = "删除日程：比价复核",
            title = "比价复核",
            startTimeMs = 999999L,
            eventId = "evt-1",
        )
        val settingUndo = UndoActionHint.systemSetting(
            label = "还原蓝牙开关为关闭",
            settingKey = "bluetooth_on",
            previousValue = "0",
        )
        builder.addUndoHint(calendarUndo).addUndoHint(settingUndo).addUndoHint(cartUndo)

        now = 5000L
        val receipt = builder.build(succeeded = true)

        assertEquals("task-receipt-01", receipt.taskId)
        assertEquals(4000L, receipt.totalDurationMs)
        assertEquals(3, receipt.steps.size)
        assertEquals(listOf("com.shop.app", "com.android.deskclock"), receipt.touchedApps)
        assertEquals(1, receipt.l2GateCount)
        assertEquals(1, receipt.l3GateCount)
        assertEquals(1, receipt.untrustedSourceSteps)
        assertTrue(receipt.hasGateEvents)
        assertTrue(receipt.hasUndoActions)
        assertEquals(4, receipt.undoHints.size)
        assertEquals(UndoActionType.REMOVE_FROM_CART, receipt.undoHints[0].type)
        assertEquals(UndoActionType.CANCEL_ALARM, receipt.undoHints[1].type)
        assertEquals(UndoActionType.DELETE_CALENDAR_EVENT, receipt.undoHints[2].type)
        assertEquals(UndoActionType.RESTORE_SYSTEM_SETTING, receipt.undoHints[3].type)
        assertFalse(receipt.steps[0].gateTriggered)
        assertTrue(receipt.steps[1].gateTriggered)
        assertEquals("slide_approved", receipt.steps[2].gateDecision)
        assertTrue(receipt.steps[2].fromUntrustedSource)

        val summary = receipt.formatSummary()
        assertTrue(summary.contains("task-receipt-01"))
        assertTrue(summary.contains("L2 倒计时 1 次 / L3 强授权 1 次 / 不可信源 1 次"))
        assertTrue(summary.contains("撤销 07:30 闹钟"))

        // 验证可安全序列化/反序列化（供持久化与跨进程/UI 传递）
        val encoded = Json.encodeToString(receipt)
        val decoded = Json.decodeFromString<ActionReceipt>(encoded)
        assertEquals(receipt, decoded)
    }
}
