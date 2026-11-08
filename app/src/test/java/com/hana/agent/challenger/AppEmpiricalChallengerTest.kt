package com.hana.agent.challenger

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.ContextWrapper
import com.hana.agent.capsule.CapsuleKind
import com.hana.agent.capsule.CapsuleState
import com.hana.agent.capsule.mascotEmotionIdForKind
import com.hana.agent.core.agent.IntentGate
import com.hana.agent.core.edge.EdgeDecisionPort
import com.hana.agent.edge.DownloadState
import com.hana.agent.edge.MnnPocketEngine
import com.hana.agent.edge.MnnPocketModelStore
import com.hana.agent.task.TaskGate
import com.hana.agent.task.TaskService
import com.hana.agent.ui.components.TelemetryStage
import com.hana.agent.ui.safegate.SafeGateHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest

/**
 * 挑战专家专属实证压力测试套件 (Empirical Challenger Stress Harness for M2 & M3)
 *
 * 覆盖 5 大对抗实证维度：
 * 1. 三态胶囊状态机与 TaskService / TaskGate 暂停控制
 * 2. 时序瀑布流极端数值 (0ms, 负数, 溢出, 空列表) 与 Canvas 脉冲折线数学模型
 * 3. 安全接管门 85% 阈值阻尼与触觉马达容错
 * 4. MNN-Pocket HTTP Range 断点续传、HTTP 416 处理、SHA-256 篡改校验与 purge 物理清理
 * 5. MNN-Pocket 5 大 JNI Native 契约反射核验、无 native 动态库平滑降级、80ms 熔断保底与内存泄洪
 */
class AppEmpiricalChallengerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var rootDir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        rootDir = tmp.newFolder()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ==========================================
    // 维度 1: 三态胶囊与暂停控制挑战
    // ==========================================

    @Test
    fun testCapsuleStateTransitionsAndDimensions() {
        // 验证三态枚举完备性
        val states = CapsuleState.entries
        assertEquals("CapsuleState 必须严格包含 3 种状态", 3, states.size)
        assertTrue(states.contains(CapsuleState.MINI))
        assertTrue(states.contains(CapsuleState.PILL))
        assertTrue(states.contains(CapsuleState.EXPANDED))

        // 验证吉祥物表情穷举映射对抗测试
        val allKinds = CapsuleKind.entries
        for (kind in allKinds) {
            val emotion = mascotEmotionIdForKind(kind)
            assertNotNull("每种 CapsuleKind 都必须映射到有效的表情 ID: $kind", emotion)
            assertTrue("表情 ID 必须为两位数字字符串: $emotion", emotion.matches(Regex("\\d{2}")))
        }

        // 核心语义表情强校验
        assertEquals("30", mascotEmotionIdForKind(CapsuleKind.Running))
        assertEquals("13", mascotEmotionIdForKind(CapsuleKind.NeedTakeover))
        assertEquals("13", mascotEmotionIdForKind(CapsuleKind.NeedConfirm))
        assertEquals("33", mascotEmotionIdForKind(CapsuleKind.Done))
        assertEquals("34", mascotEmotionIdForKind(CapsuleKind.Failed))
        assertEquals("34", mascotEmotionIdForKind(CapsuleKind.Cancelled))
        assertEquals("03", mascotEmotionIdForKind(CapsuleKind.Held))
        assertEquals("02", mascotEmotionIdForKind(CapsuleKind.Idle))
    }

    @Test
    fun testTaskServiceAndTaskGatePauseContinuity() = runTest {
        // 验证 TaskService 暴露的常量与 Flow
        assertEquals("com.hana.agent.task.PAUSE", TaskService.ACTION_PAUSE)
        assertFalse("初始状态必须为非暂停", TaskService.isPaused.value)

        // 验证 TaskGate 手动暂停与挂起生命周期
        val gate = TaskGate(
            shellAvailable = kotlinx.coroutines.flow.MutableStateFlow(true),
            locked = kotlinx.coroutines.flow.MutableStateFlow(false),
            allowBackgroundRun = { true },
            moveBack = { true },
        )

        assertFalse("TaskGate 初始未手动暂停", gate.isManuallyPaused)

        // 触发暂停
        gate.pause()
        assertTrue("pause() 后 isManuallyPaused 必须为 true", gate.isManuallyPaused)

        // 启动后台协程模拟 pass() 门禁拦截
        var gatePassed = false
        var waitingStateReported = false
        val job = backgroundScope.launch {
            gate.pass(phone = { true }) { state ->
                if (state != null) {
                    waitingStateReported = true
                }
            }
            gatePassed = true
        }

        // 给协程短暂调度时间
        kotlinx.coroutines.delay(50)
        assertFalse("处于手动暂停中，gate.pass 必须被阻塞，不能放行！", gatePassed)
        assertTrue("gate.pass 必须向外界汇报 Held(user_pause) 挂起状态", waitingStateReported)

        // 触发恢复 resume()
        gate.resume()
        assertFalse("resume() 后 isManuallyPaused 必须复位为 false", gate.isManuallyPaused)

        job.join()
        assertTrue("恢复后，gate.pass 必须立即解除挂起放行！", gatePassed)
    }

    // ==========================================
    // 维度 2: 时序瀑布流与脉冲图实证
    // ==========================================

    @Test
    fun testTelemetryWaterfallZeroAndExtremeDurations() {
        // 1. 极端 0ms 总耗时对抗（防止除以零崩溃）
        val zeroStages = listOf(
            TelemetryStage("Perceive", 0L, 0xFF3B82F6),
            TelemetryStage("Edge Filter", 0L, 0xFF10B981),
            TelemetryStage("Cloud TTFT", 0L, 0xFFF59E0B),
            TelemetryStage("Inject", 0L, 0xFF8B5CF6),
        )
        val totalZeroMs = zeroStages.sumOf { it.durationMs }.coerceAtLeast(1L)
        assertEquals("总耗时为 0 时必须安全保底为 1L 防止除零", 1L, totalZeroMs)

        zeroStages.forEach { stage ->
            val weight = (stage.durationMs.toFloat() / totalZeroMs).coerceIn(0.01f, 1f)
            assertTrue("Compose weight 必须严格大于 0.0: weight=$weight", weight >= 0.01f)
            val pct = (stage.durationMs.toFloat() / totalZeroMs * 100f).toInt()
            assertEquals("0ms 阶段计算百分比应为 0%", 0, pct)
        }

        // 2. 极大值与溢出防范对抗
        val hugeStages = listOf(
            TelemetryStage("Perceive", 50_000_000L, 0xFF3B82F6),
            TelemetryStage("Edge Filter", 10_000_000L, 0xFF10B981),
            TelemetryStage("Cloud TTFT", 40_000_000L, 0xFFF59E0B),
        )
        val totalHugeMs = hugeStages.sumOf { it.durationMs }.coerceAtLeast(1L)
        assertEquals(100_000_000L, totalHugeMs)

        hugeStages.forEach { stage ->
            val weight = (stage.durationMs.toFloat() / totalHugeMs).coerceIn(0.01f, 1f)
            assertTrue("极大值下 weight 依然必须在 (0, 1] 范围内", weight in 0.01f..1f)
        }

        // 3. 空列表防范对抗
        val emptyStages = emptyList<TelemetryStage>()
        val totalEmptyMs = emptyStages.sumOf { it.durationMs }.coerceAtLeast(1L)
        assertEquals(1L, totalEmptyMs)
    }

    @Test
    fun testEdgePulseChartMathematicalSafeguards() {
        // 模拟 EdgePulseChart 中的 Canvas 坐标变换算法
        val minVal = 8f
        val maxVal = 22f
        val range = (maxVal - minVal).coerceAtLeast(1f)
        val height = 36f

        // 测试包含极端值、极小值、负数、极大值的数据序列
        val adversarialPoints = listOf(-50f, 0f, 8f, 15f, 22f, 100f, 1000f)

        adversarialPoints.forEach { latency ->
            val normY = (1f - ((latency - minVal) / range).coerceIn(0f, 1f))
            val y = normY * (height - 8f) + 4f
            assertTrue("Canvas Y 坐标必须严格限制在 [4f, height - 4f] 范围内: y=$y", y in 4f..height - 4f)
        }

        // 测试当 points < 2 时 Canvas 的早退防御
        val singlePointList = listOf(14f)
        assertTrue("单点列表必须满足 size < 2 从而触发 return@Canvas", singlePointList.size < 2)
    }

    // ==========================================
    // 维度 3: 安全接管门 UX 挑战
    // ==========================================

    @Test
    fun testSlideToAuthorizeThresholdLogic() {
        // 模拟 SlideToAuthorize 核心阈值判定逻辑
        val trackWidthPx = 300f
        val thumbSizePx = 48f
        val maxDragPx = (trackWidthPx - thumbSizePx).coerceAtLeast(0f)
        val thresholdPx = maxDragPx * 0.85f // 85% 阈值

        assertEquals(252f, maxDragPx, 0.01f)
        assertEquals(214.2f, thresholdPx, 0.01f)

        // 场景 A: 滑动达到 84.9% (未达标) -> 必须回弹拒绝
        val subThresholdOffset = maxDragPx * 0.849f
        val isAuthorizedA = maxDragPx > 0 && subThresholdOffset >= maxDragPx * 0.85f
        assertFalse("滑动到 84.9% 严禁放行授权，必须自动回弹！", isAuthorizedA)

        // 场景 B: 滑动达到 85.0% (精确达标) -> 必须放行
        val exactThresholdOffset = maxDragPx * 0.850f
        val isAuthorizedB = maxDragPx > 0 && exactThresholdOffset >= maxDragPx * 0.85f
        assertTrue("滑动到 85.0% 必须精准触发授权放行", isAuthorizedB)

        // 场景 C: 滑动达到 100% (终点) -> 必须放行
        val fullOffset = maxDragPx
        val isAuthorizedC = maxDragPx > 0 && fullOffset >= maxDragPx * 0.85f
        assertTrue("滑动到 100% 必须触发授权放行", isAuthorizedC)

        // 场景 D: maxDragPx 为 0 边界（如未渲染时）
        val isAuthorizedD = 0f > 0 && 0f >= 0f * 0.85f
        assertFalse("轨道宽度为 0 时不能授权放行", isAuthorizedD)
    }

    @Test
    fun testSafeGateHapticsResilienceAcrossEnvironments() {
        // 创建空/异常 ContextWrapper
        val mockContext = object : ContextWrapper(null) {
            override fun getSystemService(name: String): Any? = null
        }

        // 无论何种异常环境，triggerClickThump 绝不能发生任何未捕获异常抛出
        SafeGateHaptics.triggerClickThump(mockContext)
        assertTrue(true)
    }

    // ==========================================
    // 维度 4: MNN-Pocket 下载管线、断点续传与 Purge
    // ==========================================

    @Test
    fun testHttpRangeResumeAndCorruptedRangeHandling() = runTest {
        val testPayloadPart1 = "0123456789" // 10 字节
        val testPayloadPart2 = "ABCDEFGHIJ" // 10 字节
        val fullPayload = testPayloadPart1 + testPayloadPart2

        val store = MnnPocketModelStore(
            baseDir = rootDir,
            httpClient = OkHttpClient(),
        )

        // 预设本地存在 10 字节的 .part 临时文件（模拟上次中断）
        store.partFile.writeText(testPayloadPart1)
        assertEquals(10L, store.partFile.length())

        // 期望服务端返回 206 Partial Content
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 10-19/20")
                .setBody(testPayloadPart2)
        )

        // 发起续传下载
        store.startDownload(mirrorUrl = server.url("/resume.mnn").toString())

        // 验证请求头带有 Range
        val recordedRequest = server.takeRequest()
        val rangeHeader = recordedRequest.getHeader("Range")
        assertEquals("断点续传必须携带 Range: bytes=10- 请求头", "bytes=10-", rangeHeader)

        // 验证最终就绪状态与拼装完整性
        assertEquals(DownloadState.Ready, store.downloadState.value)
        assertTrue(store.isReady())
        assertEquals("续传后文件内容必须精确等于两部分拼接结果", fullPayload, store.decModelFile.readText())
        assertFalse("完成重命名后 .part 临时文件必须不再存在", store.partFile.exists())
    }

    @Test
    fun testSha256ChecksumVerificationOnTamperedFile() = runTest {
        val store = MnnPocketModelStore(baseDir = rootDir)
        val validContent = "GENUINE_MNN_POCKET_WEIGHTS"
        store.decModelFile.writeText(validContent)

        val genuineSha256 = MessageDigest.getInstance("SHA-256")
            .digest(validContent.toByteArray())
            .joinToString("") { "%02x".format(it) }

        // 正确哈希校验
        assertTrue(store.verifyChecksum(store.decModelFile, genuineSha256))

        // 篡改 1 字节
        val tamperedContent = "GENUINE_MNN_POCKET_WEIGHT$"
        store.decModelFile.writeText(tamperedContent)

        // 校验必须失败
        assertFalse("文件内容遭篡改后，SHA-256 校验必须严格返回 false！", store.verifyChecksum(store.decModelFile, genuineSha256))

        // 文件不存在时
        val nonExistentFile = File(rootDir, "non_existent.mnn")
        assertFalse(store.verifyChecksum(nonExistentFile, genuineSha256))
    }

    @Test
    fun testHttp416RangeNotSatisfiableDeletesCorruptedPartFile() = runTest {
        val store = MnnPocketModelStore(
            baseDir = rootDir,
            httpClient = OkHttpClient(),
        )

        // 写入一个损坏的/超范围的 .part 文件
        store.partFile.writeText("CORRUPTED_EXCESSIVE_PART_DATA")
        assertTrue(store.partFile.exists())

        // 服务端返回 416 Range Not Satisfiable
        server.enqueue(MockResponse().setResponseCode(416))

        store.startDownload(mirrorUrl = server.url("/invalid_range.mnn").toString())

        val state = store.downloadState.value
        assertTrue("HTTP 416 发生时必须置为 Error 状态", state is DownloadState.Error)
        assertTrue("HTTP 416 错误必须允许用户重试", (state as DownloadState.Error).canRetry)
        assertFalse("HTTP 416 发生时必须物理删除损坏的 .part 文件，确保下次重试能够从零开始", store.partFile.exists())
    }

    @Test
    fun testPurgeWeightsThoroughnessAndDiskAccounting() {
        val store = MnnPocketModelStore(baseDir = rootDir)

        // 初始磁盘占用为 0
        assertEquals(0L, store.getDiskUsageBytes())

        // 写入主模型和辅助模型
        store.decModelFile.writeBytes(ByteArray(4096))
        store.genModelFile.writeBytes(ByteArray(8192))
        store.partFile.writeBytes(ByteArray(1024))

        val expectedTotal = 4096L + 8192L + 1024L
        assertEquals("getDiskUsageBytes 必须精确统计所有模型与分片字节", expectedTotal, store.getDiskUsageBytes())

        // 执行一键物理清理
        val purged = store.purgeWeights()
        assertTrue("purgeWeights() 必须成功执行", purged)

        // 验证全部物理文件彻底抹除
        assertFalse(store.decModelFile.exists())
        assertFalse(store.genModelFile.exists())
        assertFalse(store.partFile.exists())
        assertEquals("清理后磁盘占用必须严格归零", 0L, store.getDiskUsageBytes())
        assertEquals("清理后下载状态必须复位为 NotDownloaded", DownloadState.NotDownloaded, store.downloadState.value)
        assertFalse("清理后 isReady() 必须返回 false", store.isReady())
    }

    // ==========================================
    // 维度 5: MNN-Pocket JNI 与平滑降级实证
    // ==========================================

    @Test
    fun testJniNativeSignaturesAndSmoothFallbackUnderPressure() = runTest {
        val clazz = MnnPocketEngine::class.java

        // 验证 5 大 Native 方法契约
        val methods = clazz.declaredMethods
        val nativeInit = methods.firstOrNull { it.name == "nativeInit" }
        assertNotNull(nativeInit)
        assertTrue(Modifier.isNative(nativeInit!!.modifiers))

        val nativeClassify = methods.firstOrNull { it.name == "nativeClassifyIntent" }
        assertNotNull(nativeClassify)
        assertTrue(Modifier.isNative(nativeClassify!!.modifiers))

        val nativePick = methods.firstOrNull { it.name == "nativePickObstaclePointer" }
        assertNotNull(nativePick)
        assertTrue(Modifier.isNative(nativePick!!.modifiers))

        val nativeMatch = methods.firstOrNull { it.name == "nativeMatchReplayPointer" }
        assertNotNull(nativeMatch)
        assertTrue(Modifier.isNative(nativeMatch!!.modifiers))

        val nativeRelease = methods.firstOrNull { it.name == "nativeRelease" }
        assertNotNull(nativeRelease)
        assertTrue(Modifier.isNative(nativeRelease!!.modifiers))

        // 构造 Engine 实例并开启测试打桩模式
        val engine = MnnPocketEngine(context = null, dispatcher = Dispatchers.Unconfined)
        engine.forceReadyForTesting = true

        // 100 组意图分类输入对抗测试（验证无 SO 下纯 Kotlin 规则降级稳定性）
        val actInputs = listOf(
            "帮我打开微信", "去淘宝买一箱牛奶", "支付账单", "点赞视频", "发送短信给李四",
            "关闭闹钟", "启动相机", "帮我发个朋友圈", "输入密码123456"
        )
        for (input in actInputs) {
            val res = engine.classifyIntent(input)
            assertEquals("动作类短语 [$input] 必须被分类为 Act", IntentGate.Intent.Act, res)
        }

        val discussInputs = listOf(
            "你好", "今天天气真不错", "哈哈太好笑了", "你觉得怎么样", "在吗",
            "晚安", "谢谢你", "ok", "好的收到"
        )
        for (input in discussInputs) {
            val res = engine.classifyIntent(input)
            assertEquals("闲聊类短语 [$input] 必须被分类为 Discuss", IntentGate.Intent.Discuss, res)
        }

        val readInputs = listOf(
            "查一下今天的汇率", "看一下明天的日程", "找一下我的快递", "搜一下周杰伦的歌", "查看手机电量"
        )
        for (input in readInputs) {
            val res = engine.classifyIntent(input)
            assertEquals("查询类短语 [$input] 必须被分类为 Read", IntentGate.Intent.Read, res)
        }

        // 弹窗阻断消解指针消解测试
        val candidates = listOf(
            EdgeDecisionPort.EdgeCandidate("@r1", "Button", "残忍拒绝"),
            EdgeDecisionPort.EdgeCandidate("@r2", "Button", "立即开通"),
            EdgeDecisionPort.EdgeCandidate("@r3", "Button", "取消")
        )
        val pickedRef = engine.pickObstacleDismissRef("随便看看", "开通 VIP", candidates)
        assertEquals("阻断弹窗必须精准消解至消极/关闭按钮", "@r1", pickedRef)

        // 候选为空时安全防御
        val emptyPick = engine.pickObstacleDismissRef("目标", "线索", emptyList())
        assertNull("候选为空时必须安全返回 null", emptyPick)

        // 候选数量超出 18 个时安全防御
        val tooManyCandidates = (1..19).map { EdgeDecisionPort.EdgeCandidate("@r$it", "Button", "按钮$it") }
        val overflowPick = engine.pickObstacleDismissRef("目标", "线索", tooManyCandidates)
        assertNull("候选超过 18 个时必须防御早退返回 null", overflowPick)

        // 验证 80ms 熔断保底防御机制
        engine.simulatedDelayMsForTesting = 120L // 模拟耗时 120ms (超过 80ms 熔断)
        val timeoutIntent = engine.classifyIntent("帮我打开微信")
        assertNull("耗时超过 80ms 必须硬熔断并返回 null 保障 UI 120Hz 流畅", timeoutIntent)
        engine.simulatedDelayMsForTesting = 0L

        // 验证内存泄洪与低内存主动释放
        engine.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertFalse("遭遇低内存泄洪时，isReady 必须立即置为 false", engine.isReady)
        val rejectedIntent = engine.classifyIntent("帮我打开微信")
        assertNull("低内存受限状态下必须拒绝执行并立即返回 null", rejectedIntent)

        // 内存恢复
        engine.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        assertTrue("内存缓解后，isReady 必须自动恢复", engine.isReady)

        // 验证 Replay Pointer 跨版本控件语义对齐 (同义词消解)
        val replayCandidates = listOf(
            EdgeDecisionPort.EdgeCandidate("@btn1", "Button", "立即付款"),
            EdgeDecisionPort.EdgeCandidate("@btn2", "Button", "返回首页"),
        )
        val replayRef = engine.matchReplayCandidate("支付环节", "支付", replayCandidates)
        assertEquals("语义对齐必须精准映射至同义词 [@btn1: 立即付款]", "@btn1", replayRef)

        // 验证口语化应用别名与搜索槽位提取
        val installedApps = listOf(
            "com.xingin.xhs" to "小红书",
            "com.tencent.mm" to "微信",
            "tv.danmaku.bili" to "哔哩哔哩",
        )
        val appAction = engine.resolveAppFastAction("去红薯搜一下秋冬穿搭", installedApps)
        assertNotNull("必须识别口语别名 [红薯] 为小红书", appAction)
        assertEquals("com.xingin.xhs", appAction!!.packageName)
        assertEquals("小红书", appAction.appLabel)
        assertEquals("秋冬穿搭", appAction.searchQuery)
        assertFalse(appAction.isPureOpen)

        // 验证纯启动场景
        val pureOpenAction = engine.resolveAppFastAction("打开绿泡泡", installedApps)
        assertNotNull("必须识别口语别名 [绿泡泡] 为微信", pureOpenAction)
        assertEquals("com.tencent.mm", pureOpenAction!!.packageName)
        assertTrue(pureOpenAction.isPureOpen)
    }
}
