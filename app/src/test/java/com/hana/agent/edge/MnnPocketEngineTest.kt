package com.hana.agent.edge

import android.content.ComponentCallbacks2
import com.hana.agent.core.agent.IntentGate
import com.hana.agent.core.edge.EdgeDecisionPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MnnPocketEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun modelStoreManagesFilePathsAndReadiness() {
        val root = tmp.newFolder()
        val store = MnnPocketModelStore(baseDir = root)

        assertFalse("模型文件不存在时 isReady 应为 false", store.isReady())
        val decFile = store.decModelFile
        assertEquals(File(root, MnnPocketModelStore.DEC_MODEL_NAME), decFile)

        // 创建假模型文件测试就绪状态
        decFile.writeText("fake INT4 QAT weights")
        assertTrue("存在模型文件且大于0字节时 isReady 应为 true", store.isReady())
        assertTrue(store.getDiskUsageBytes() > 0)

        // 测试清理
        assertTrue(store.purgeWeights())
        assertFalse(decFile.exists())
    }

    @Test
    @Suppress("DEPRECATION")
    fun engineReflectsMemoryTrimmingLifecycle() {
        val engine = MnnPocketEngine(context = null, dispatcher = Dispatchers.Unconfined)
        engine.forceReadyForTesting = true
        assertTrue("强制设置就绪时 isReady 为 true", engine.isReady)

        // 模拟系统严重内存压力
        engine.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertFalse("收到 TRIM_MEMORY_RUNNING_CRITICAL 时引擎必须立即置为不可用以防 LMK 杀死应用", engine.isReady)

        // 模拟内存压力恢复
        engine.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        assertTrue("内存压力缓解后引擎恢复可用", engine.isReady)

        // 模拟低内存回调
        engine.onLowMemory()
        assertFalse("低内存回调后引擎不可用", engine.isReady)
    }

    @Test
    fun classifiesIntentAccurately() = runTest {
        val engine = MnnPocketEngine(context = null, dispatcher = Dispatchers.Unconfined).apply { forceReadyForTesting = true }

        // 假动作真闲聊
        assertEquals(
            IntentGate.Intent.Discuss,
            engine.classifyIntent("我刚在淘宝看到一件衣服挺好看")
        )
        assertEquals(
            IntentGate.Intent.Discuss,
            engine.classifyIntent("你好，在吗？")
        )

        // 读取意图
        assertEquals(
            IntentGate.Intent.Read,
            engine.classifyIntent("查一下明天的日程安排")
        )
        assertEquals(
            IntentGate.Intent.Read,
            engine.classifyIntent("看一下微信未读消息")
        )

        // 动作意图
        assertEquals(
            IntentGate.Intent.Act,
            engine.classifyIntent("帮我打开微信发给张三")
        )
        assertEquals(
            IntentGate.Intent.Act,
            engine.classifyIntent("去淘宝买个无线耳机")
        )
    }

    @Test
    fun picksObstacleDismissPointerAccurately() = runTest {
        val engine = MnnPocketEngine(context = null, dispatcher = Dispatchers.Unconfined).apply { forceReadyForTesting = true }

        val candidates = listOf(
            EdgeDecisionPort.EdgeCandidate("@e1", "Button", "立即开通VIP"),
            EdgeDecisionPort.EdgeCandidate("@e2", "Button", "先逛逛"),
            EdgeDecisionPort.EdgeCandidate("@e3", "Button", "查看详情")
        )

        val picked = engine.pickObstacleDismissRef(
            userGoal = "去小红书浏览穿搭",
            dialogClues = "开通会员享受折扣",
            candidates = candidates
        )
        assertEquals("@e2", picked)

        // 候选全部是业务按钮时必须返回 null
        val businessCandidates = listOf(
            EdgeDecisionPort.EdgeCandidate("@b1", "Button", "加入购物车"),
            EdgeDecisionPort.EdgeCandidate("@b2", "Button", "立即购买")
        )
        assertNull(
            engine.pickObstacleDismissRef("买东西", "", businessCandidates)
        )
    }

    @Test
    fun matchesReplayTargetSemantically() = runTest {
        val engine = MnnPocketEngine(context = null, dispatcher = Dispatchers.Unconfined).apply { forceReadyForTesting = true }

        val candidates = listOf(
            EdgeDecisionPort.EdgeCandidate("@e1", "TextView", "购物车(2)"),
            EdgeDecisionPort.EdgeCandidate("@e2", "Button", "去买单"),
            EdgeDecisionPort.EdgeCandidate("@e3", "Button", "领券")
        )

        // 历史记录为“结算”，当前 UI 改版为“去买单”
        val matched = engine.matchReplayCandidate(
            stepGoal = "Button",
            recordedLabel = "结算",
            candidates = candidates
        )
        assertEquals("@e2", matched)
    }

    @Test
    fun resolvesAppColloquialAliasesAndCompositeSearch() = runTest {
        val engine = MnnPocketEngine(context = null, dispatcher = Dispatchers.Unconfined).apply { forceReadyForTesting = true }
        val installed = listOf(
            "com.xingin.xhs" to "小红书",
            "com.tencent.mm" to "微信",
            "com.taobao.taobao" to "淘宝",
        )

        // 口语别名 + 复合搜索词提取
        val res1 = engine.resolveAppFastAction("去红薯搜一下秋冬穿搭", installed)
        assertNotNull(res1)
        assertEquals("com.xingin.xhs", res1!!.packageName)
        assertEquals("小红书", res1.appLabel)
        assertEquals("秋冬穿搭", res1.searchQuery)
        assertFalse(res1.isPureOpen)

        // 口语纯打开
        val res2 = engine.resolveAppFastAction("打开绿泡泡", installed)
        assertNotNull(res2)
        assertEquals("com.tencent.mm", res2!!.packageName)
        assertEquals("微信", res2.appLabel)
        assertTrue(res2.isPureOpen)
        assertNull(res2.searchQuery)
    }

    @Test
    fun hardTimeoutFuseProtectsExecution() = kotlinx.coroutines.runBlocking {
        val engine = MnnPocketEngine(context = null, dispatcher = Dispatchers.Default).apply { forceReadyForTesting = true }

        // 模拟超过 80ms 硬熔断时限的慢推理（例如 120ms）
        engine.simulatedDelayMsForTesting = 120L
        val timedOut = engine.classifyIntent("打开微信")
        assertNull("超过 80ms 硬熔断时限必须立即返回 null 兜底", timedOut)

        // 恢复正常延迟（0ms），正常返回
        engine.simulatedDelayMsForTesting = 0L
        val normal = engine.classifyIntent("打开微信")
        assertEquals(IntentGate.Intent.Act, normal)
    }

    @Test
    fun resolvesPureOpenVariationsWithSuffixAndParticles() = runTest {
        val engine = MnnPocketEngine(context = null, dispatcher = Dispatchers.Unconfined).apply { forceReadyForTesting = true }
        val installed = listOf(
            "com.xingin.xhs" to "小红书",
            "com.tencent.mm" to "微信",
            "com.taobao.taobao" to "淘宝",
        )

        val res1 = engine.resolveAppFastAction("打开微信吧", installed)
        assertNotNull(res1)
        assertTrue("带语气词「吧」应被识别为纯打开", res1!!.isPureOpen)

        val res2 = engine.resolveAppFastAction("打开小红书App", installed)
        assertNotNull(res2)
        assertTrue("带后缀「App」应被识别为纯打开", res2!!.isPureOpen)

        val res3 = engine.resolveAppFastAction("请帮我打开淘宝", installed)
        assertNotNull(res3)
        assertTrue("带前缀「请帮我」应被识别为纯打开", res3!!.isPureOpen)
    }

    @Test
    fun rejectsUninstalledAppAliases() = runTest {
        val engine = MnnPocketEngine(context = null, dispatcher = Dispatchers.Unconfined).apply { forceReadyForTesting = true }
        val installed = listOf(
            "com.tencent.mm" to "微信",
        )

        // 京东未安装在当前手机上
        val res = engine.resolveAppFastAction("去东哥那买衣服", installed)
        assertNull("未安装的应用不得通过 FastPath 触发启动", res)
    }
}
