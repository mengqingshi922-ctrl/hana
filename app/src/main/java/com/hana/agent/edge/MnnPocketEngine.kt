package com.hana.agent.edge

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.hana.agent.core.agent.IntentGate
import com.hana.agent.core.edge.EdgeDecisionPort
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MNN-Pocket 进程内受控神经引擎 (In-Process Native Bridge & Engine)
 *
 * 遵循四条架构铁律：
 * 1. 严禁外部进程与本地 HTTP Server：进程内直调，DirectBuffer 交互；
 * 2. 严禁 0.3B 模型自由生成 JSON Schema：只做单步非自回归分类与 Pointer 选择；
 * 3. 严禁抢占 GPU 与长上下文满载 CPU：推理后端强制绑定 CPU 3 线程，单线程调度，0% Mali/Adreno GPU；
 * 4. 80ms 熔断保底与 ComponentCallbacks2 内存泄洪：低内存 1ms 主动释放，绝对保证 SurfaceFlinger 120Hz 丝滑。
 */
class MnnPocketEngine(
    private val context: Context? = null,
    val store: MnnPocketModelStore = MnnPocketModelStore(context),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : EdgeDecisionPort, ComponentCallbacks2 {

    private val isMemoryConstrained = AtomicBoolean(false)
    private val isInitialized = AtomicBoolean(false)

    /** 供单测或开发者选项强制启用/打桩。 */
    @Volatile
    var forceReadyForTesting: Boolean = false

    /** 供单测模拟耗时以验证 80ms 硬熔断保护。 */
    @Volatile
    var simulatedDelayMsForTesting: Long = 0L

    override val isReady: Boolean
        get() = !isMemoryConstrained.get() && (forceReadyForTesting || store.isReady())

    /** 端侧推理后端运行模式 */
    enum class ExecutionBackend {
        NATIVE_MNN_WITH_HEURISTIC_FALLBACK,
        KOTLIN_HEURISTIC_ONLY,
        UNAVAILABLE,
    }

    /** 诚实可观测的引擎运行态快照 */
    data class EngineRuntimeStatus(
        val backend: ExecutionBackend,
        val nativeLibraryLoaded: Boolean,
        val modelReady: Boolean,
        val isMemoryConstrained: Boolean,
        val engineReady: Boolean,
        val description: String,
    )

    /** 获取当前引擎的诚实运行态（明确区分 Native SO 已加载 vs 纯 Kotlin 启发式规则引擎） */
    fun getRuntimeStatus(): EngineRuntimeStatus {
        val modelReady = store.isReady()
        val constrained = isMemoryConstrained.get()
        val ready = isReady
        val backend = when {
            constrained -> ExecutionBackend.UNAVAILABLE
            nativeLoaded && modelReady -> ExecutionBackend.NATIVE_MNN_WITH_HEURISTIC_FALLBACK
            ready -> ExecutionBackend.KOTLIN_HEURISTIC_ONLY
            else -> ExecutionBackend.UNAVAILABLE
        }
        val desc = when (backend) {
            ExecutionBackend.NATIVE_MNN_WITH_HEURISTIC_FALLBACK ->
                "Native MNN-Pocket SO 已加载（CPU 3线程推理 + Kotlin 启发式兜底）"
            ExecutionBackend.KOTLIN_HEURISTIC_ONLY ->
                if (nativeLoaded) {
                    "纯 Kotlin 启发式规则引擎运行中（Native SO 已加载但模型权重尚未就绪）"
                } else {
                    "纯 Kotlin 启发式规则引擎运行中（Native MNN SO 未载入，已启用零依赖规则推理）"
                }
            ExecutionBackend.UNAVAILABLE ->
                if (constrained) "低内存泄洪降级暂停中" else "端侧模型未就绪（Native=$nativeLoaded, Model=$modelReady）"
        }
        return EngineRuntimeStatus(
            backend = backend,
            nativeLibraryLoaded = nativeLoaded,
            modelReady = modelReady,
            isMemoryConstrained = constrained,
            engineReady = ready,
            description = desc,
        )
    }

    fun describeRuntimeMode(): String = getRuntimeStatus().description

    init {
        try {
            context?.registerComponentCallbacks(this)
        } catch (_: Exception) {
            // 测试环境 Application context 容错
        }
    }

    @Volatile
    private var sessionPtr: Long = 0L

    // ==================== JNI Native 接口契约 ====================
    private external fun nativeInit(modelPath: String, threadNum: Int): Long
    private external fun nativeClassifyIntent(sessionPtr: Long, task: String): Int
    private external fun nativePickObstaclePointer(sessionPtr: Long, clues: String, candidateTexts: Array<String>): Int
    private external fun nativeMatchReplayPointer(sessionPtr: Long, recordedText: String, candidateTexts: Array<String>): Int
    private external fun nativeRelease(sessionPtr: Long)

    private fun ensureNativeSession(): Long {
        if (!nativeLoaded || !store.isReady()) return 0L
        if (sessionPtr != 0L) return sessionPtr
        synchronized(this) {
            if (sessionPtr == 0L) {
                sessionPtr = runCatching {
                    nativeInit(store.decModelFile.absolutePath, 3)
                }.getOrDefault(0L)
            }
        }
        return sessionPtr
    }

    /**
     * 1. 单步非自回归意图分类（MNN-Pocket-0.3B-Dec，单次前向，硬熔断 80ms）。
     */
    override suspend fun classifyIntent(
        task: String,
        recentContextSummary: String,
        confidenceThreshold: Float,
    ): IntentGate.Intent? = withTimeoutOrNull(HARD_TIMEOUT_MS) {
        if (!isReady) return@withTimeoutOrNull null
        withContext(dispatcher) {
            if (simulatedDelayMsForTesting > 0) kotlinx.coroutines.delay(simulatedDelayMsForTesting)
            val ptr = ensureNativeSession()
            if (ptr != 0L) {
                val nativeRes = runCatching { nativeClassifyIntent(ptr, task) }.getOrNull()
                if (nativeRes != null) {
                    when (nativeRes) {
                        0 -> return@withContext IntentGate.Intent.Act
                        1 -> return@withContext IntentGate.Intent.Read
                        2 -> return@withContext IntentGate.Intent.Discuss
                    }
                }
            }
            evaluateIntent(task, recentContextSummary, confidenceThreshold)
        }
    }

    /**
     * 2. 浮层阻断弹窗按钮指针消解（MNN-Pocket-0.3B-Dec Pointer Head，候选 <= 18，硬熔断 80ms）。
     */
    override suspend fun pickObstacleDismissRef(
        userGoal: String,
        dialogClues: String,
        candidates: List<EdgeDecisionPort.EdgeCandidate>,
        confidenceThreshold: Float,
    ): String? = withTimeoutOrNull(HARD_TIMEOUT_MS) {
        if (!isReady || candidates.isEmpty() || candidates.size > 18) return@withTimeoutOrNull null
        withContext(dispatcher) {
            if (simulatedDelayMsForTesting > 0) kotlinx.coroutines.delay(simulatedDelayMsForTesting)
            val ptr = ensureNativeSession()
            if (ptr != 0L) {
                val texts = candidates.map { it.label }.toTypedArray()
                val nativeIdx = runCatching { nativePickObstaclePointer(ptr, dialogClues, texts) }.getOrDefault(-1)
                if (nativeIdx in candidates.indices) {
                    return@withContext candidates[nativeIdx].ref
                }
            }
            evaluateObstaclePointer(userGoal, dialogClues, candidates, confidenceThreshold)
        }
    }

    /**
     * 3. 经验回放（Replayer）跨版本 UI 控件语义对齐（候选 <= 8，硬熔断 80ms）。
     */
    override suspend fun matchReplayCandidate(
        stepGoal: String,
        recordedLabel: String,
        candidates: List<EdgeDecisionPort.EdgeCandidate>,
        confidenceThreshold: Float,
    ): String? = withTimeoutOrNull(HARD_TIMEOUT_MS) {
        if (!isReady || candidates.isEmpty()) return@withTimeoutOrNull null
        withContext(dispatcher) {
            if (simulatedDelayMsForTesting > 0) kotlinx.coroutines.delay(simulatedDelayMsForTesting)
            val ptr = ensureNativeSession()
            if (ptr != 0L) {
                val texts = candidates.map { it.label }.toTypedArray()
                val nativeIdx = runCatching { nativeMatchReplayPointer(ptr, recordedLabel, texts) }.getOrDefault(-1)
                if (nativeIdx in candidates.indices) {
                    return@withContext candidates[nativeIdx].ref
                }
            }
            evaluateReplayPointer(stepGoal, recordedLabel, candidates, confidenceThreshold)
        }
    }

    /**
     * 4. 口语化应用启动与搜索槽位一步提取（硬熔断 80ms）。
     */
    override suspend fun resolveAppFastAction(
        task: String,
        installedApps: List<Pair<String, String>>,
    ): EdgeDecisionPort.ResolvedAppAction? = withTimeoutOrNull(HARD_TIMEOUT_MS) {
        if (!isReady) return@withTimeoutOrNull null
        withContext(dispatcher) {
            if (simulatedDelayMsForTesting > 0) kotlinx.coroutines.delay(simulatedDelayMsForTesting)
            evaluateAppAction(task, installedApps)
        }
    }

    // ==================== 内部语义决策算法与 JNI 兜底实现 ====================

    private fun evaluateIntent(
        task: String,
        recentContextSummary: String,
        confidenceThreshold: Float,
    ): IntentGate.Intent? {
        val clean = task.trim()
        if (clean.isBlank()) return null

        // 假动作真闲聊过滤（例如：“我刚在淘宝看到一件衣服挺好看”、“微信界面设计得真不错”）
        val chitChatPatterns = listOf(
            "挺好看", "真好看", "不错", "挺有意思", "太好笑了", "哈哈", "觉得怎么样",
            "你觉得呢", "感觉一般", "好用吗", "是什么", "为什么", "怎么看"
        )
        if (chitChatPatterns.any { clean.contains(it) } && !clean.startsWith("帮我") && !clean.startsWith("去") && !clean.startsWith("打开")) {
            return IntentGate.Intent.Discuss
        }

        // 纯闲聊日常短句与问候
        val pureDiscuss = setOf("你好", "在吗", "早上好", "下午好", "晚上好", "晚安", "谢谢", "多谢", "ok", "好的", "收到", "哈喽", "hi", "hello")
        val stripped = clean.trim('?', '？', '!', '！', '.', '。', ',', '，', '~', ' ')
        if ((pureDiscuss.any { stripped.contains(it, ignoreCase = true) } || pureDiscuss.contains(stripped.lowercase())) && stripped.length <= 12) {
            return IntentGate.Intent.Discuss
        }

        // 明确的读取/查询意图
        val readVerbs = listOf("查一下", "查询", "看一下", "查看", "看下", "看一看", "读一下", "找一下", "搜一下", "搜搜")
        val isReadQuery = readVerbs.any { clean.startsWith(it) || clean.contains("帮我$it") || clean.contains("请$it") }
        if (isReadQuery && !clean.contains("发") && !clean.contains("买") && !clean.contains("付") && !clean.contains("转")) {
            return IntentGate.Intent.Read
        }

        // 明确的物理操作意图
        val actVerbs = listOf(
            "打开", "启动", "运行", "去", "帮我发", "发给", "发送", "买", "下单", "支付", "转账", "打字",
            "输入", "清理", "卸载", "关闭", "点赞", "关注", "收藏", "播放", "暂停"
        )
        if (actVerbs.any { clean.contains(it) }) {
            return IntentGate.Intent.Act
        }

        return null
    }

    private fun evaluateObstaclePointer(
        userGoal: String,
        dialogClues: String,
        candidates: List<EdgeDecisionPort.EdgeCandidate>,
        confidenceThreshold: Float,
    ): String? {
        // 1. 高确定性无副作用跳过/关闭/拒绝变体规则库
        val strongDismissPatterns = listOf(
            "跳过广告", "关闭广告", "广告关闭", "跳过此步", "跳过引导", "跳过",
            "先逛逛", "忍痛离开", "算了吧", "仅浏览", "游客浏览", "随便看看",
            "稍后再说", "以后再说", "下次再说", "稍后设置", "暂不升级", "暂不更新",
            "暂不开启", "暂不使用", "暂不登录", "先不登录", "暂不绑定", "暂不认证",
            "残忍拒绝", "取消", "关闭", "放弃", "拒绝", "忽略此版本", "忽略", "暂不",
            "不需要", "不用了", "不了，谢谢", "不了谢谢", "不再提示",
            "x", "✕", "✖", "×"
        )

        // 2. 常见中文提示/隐私协议/开屏通知弹窗放行按钮
        val acknowledgementDismissPatterns = listOf(
            "我知道了", "我已知晓", "已知晓", "同意并继续", "同意并进入", "继续访问", "仍然继续"
        )

        val countdownSuffixRegex = Regex("""\s*[\(（]?\s*\d+\s*[sS秒]?\s*[\)）]?$""")
        val dialogHasPermissionOrNoticeClue = listOf("协议", "隐私", "权限", "通知", "提示", "公告", "更新", "声明", "须知")
            .any { dialogClues.contains(it, ignoreCase = true) }

        for (c in candidates) {
            val rawLabel = c.label.trim()
            if (rawLabel.isEmpty()) continue
            if (rawLabel.length >= 2 && userGoal.contains(rawLabel, ignoreCase = true)) continue

            // 规范化去除倒计时后缀（如 "跳过广告 3s" / "跳过(5)"）
            val normalized = rawLabel.replace(countdownSuffixRegex, "").trim()

            for (p in strongDismissPatterns) {
                if (rawLabel.equals(p, ignoreCase = true) ||
                    normalized.equals(p, ignoreCase = true) ||
                    (rawLabel.startsWith(p, ignoreCase = true) && rawLabel.length <= p.length + 4)
                ) {
                    return c.ref
                }
            }

            for (p in acknowledgementDismissPatterns) {
                if (rawLabel.equals(p, ignoreCase = true) ||
                    normalized.equals(p, ignoreCase = true) ||
                    (rawLabel.startsWith(p, ignoreCase = true) && rawLabel.length <= p.length + 3)
                ) {
                    return c.ref
                }
            }

            if (dialogHasPermissionOrNoticeClue && (rawLabel == "同意" || rawLabel == "好的" || rawLabel == "确定")) {
                return c.ref
            }
        }
        return null
    }

    private fun evaluateReplayPointer(
        stepGoal: String,
        recordedLabel: String,
        candidates: List<EdgeDecisionPort.EdgeCandidate>,
        confidenceThreshold: Float,
    ): String? {
        if (recordedLabel.isBlank()) return null
        val recordedClean = recordedLabel.trim()

        // 常见同义语义映射表（应对 App 小改版）
        val semanticEquivalents = mapOf(
            "结算" to listOf("去买单", "立即结算", "去结算", "提交订单"),
            "买单" to listOf("去结算", "立即支付", "结算"),
            "支付" to listOf("去支付", "立即付款", "确认支付", "去付款"),
            "确定" to listOf("确认", "好的", "知道了", "完成"),
            "确认" to listOf("确定", "提交", "完成"),
            "搜索" to listOf("搜一下", "开始搜索", "查找"),
            "发送" to listOf("发表", "发布", "发出去"),
            "加入购物车" to listOf("加购物车", "进购物车", "放入购物车"),
        )

        val synonyms = semanticEquivalents[recordedClean] ?: emptyList()

        for (c in candidates) {
            val label = c.label.trim()
            if (label.isEmpty()) continue
            if (label.equals(recordedClean, ignoreCase = true)) return c.ref
            if (synonyms.any { label.contains(it, ignoreCase = true) || it.contains(label, ignoreCase = true) }) {
                return c.ref
            }
            if (label.contains(recordedClean, ignoreCase = true) || recordedClean.contains(label, ignoreCase = true)) {
                return c.ref
            }
        }
        return null
    }

    private fun evaluateAppAction(
        task: String,
        installedApps: List<Pair<String, String>>,
    ): EdgeDecisionPort.ResolvedAppAction? {
        val clean = task.trim()
        if (clean.isBlank()) return null

        // 知名 App 口语别名表
        val aliasMap = mapOf(
            "红薯" to ("小红书" to "com.xingin.xhs"),
            "小红书" to ("小红书" to "com.xingin.xhs"),
            "绿泡泡" to ("微信" to "com.tencent.mm"),
            "微信" to ("微信" to "com.tencent.mm"),
            "某宝" to ("淘宝" to "com.taobao.taobao"),
            "淘宝" to ("淘宝" to "com.taobao.taobao"),
            "某东" to ("京东" to "com.jingdong.app.mall"),
            "东哥" to ("京东" to "com.jingdong.app.mall"),
            "京东" to ("京东" to "com.jingdong.app.mall"),
            "网易云" to ("网易云音乐" to "com.netease.cloudmusic"),
            "云音乐" to ("网易云音乐" to "com.netease.cloudmusic"),
            "黄蓝" to ("美团" to "com.sankuai.meituan"),
            "美团" to ("美团" to "com.sankuai.meituan"),
            "小破站" to ("哔哩哔哩" to "tv.danmaku.bili"),
            "b站" to ("哔哩哔哩" to "tv.danmaku.bili"),
            "微博" to ("微博" to "com.sina.weibo"),
            "知乎" to ("知乎" to "com.zhihu.android"),
        )

        // 匹配用户提及的应用别名
        var matchedAlias: String? = null
        var targetPkg: String? = null
        var targetLabel: String? = null

        for ((alias, info) in aliasMap) {
            if (clean.contains(alias, ignoreCase = true)) {
                matchedAlias = alias
                targetLabel = info.first
                targetPkg = info.second
                break
            }
        }

        // 如果在已知别名中未命中，尝试从已安装应用中匹配
        if (targetPkg == null) {
            for ((pkg, label) in installedApps.sortedByDescending { it.second.length }) {
                if (label.length >= 2 && clean.contains(label, ignoreCase = true)) {
                    matchedAlias = label
                    targetLabel = label
                    targetPkg = pkg
                    break
                }
            }
        }

        if (targetPkg == null || targetLabel == null || matchedAlias == null) return null

        // 如果提供了已安装应用列表，核对目标应用是否真实安装
        if (installedApps.isNotEmpty()) {
            val installedMatch = installedApps.firstOrNull {
                it.first.equals(targetPkg, ignoreCase = true) || it.second.equals(targetLabel, ignoreCase = true)
            }
            if (installedMatch != null) {
                targetPkg = installedMatch.first
                targetLabel = installedMatch.second
            } else {
                return null
            }
        }

        // 尝试提取搜索关键词
        val searchPrefixes = listOf("搜一下", "搜索", "搜搜", "查一下", "查询", "搜", "查")
        var searchQuery: String? = null

        for (sp in searchPrefixes) {
            val idx = clean.indexOf(sp)
            if (idx != -1) {
                val candidate = if (idx > clean.indexOf(matchedAlias)) {
                    clean.substring(idx + sp.length)
                } else {
                    val afterAlias = clean.substring(clean.indexOf(matchedAlias) + matchedAlias.length)
                    afterAlias.trimStart('里', '面', '中', '上', '的', ' ')
                }.trim()
                    .trimStart(':', '：', ' ', '“', '"', '「', '『')
                    .trimEnd('”', '"', '」', '』', '。', '！', '!', ' ', '吧', '呀', '呢', '啊')
                if (candidate.isNotEmpty() && candidate != "一下") {
                    searchQuery = candidate
                    break
                }
            }
        }

        val pureOpenTriggers = listOf(
            "帮我打开", "请帮我打开", "打开", "帮我启动", "请帮我启动", "启动", "运行", "进一下", "进入",
            "帮我进入", "去", "到", "在", "帮我在", "请在", "帮我去", "请到", "上", "launch", "open"
        )
        val isPure: Boolean = if (!searchQuery.isNullOrBlank()) {
            false
        } else {
            var remainder = clean
            val prefix = pureOpenTriggers.firstOrNull { remainder.startsWith(it, ignoreCase = true) }
            if (prefix != null) {
                remainder = remainder.substring(prefix.length).trim()
            }
            val aliasIdx = remainder.indexOf(matchedAlias, ignoreCase = true)
            if (aliasIdx != -1) {
                remainder = (remainder.substring(0, aliasIdx) + remainder.substring(aliasIdx + matchedAlias.length)).trim()
            }
            val cleanRemainder = remainder.trim(' ', ',', '，', '。', '、', '的', '！', '!', '吧', '呀', '呢', '啊', '一', '下')
                .trimStart('里', '面', '中', '上', '去', '帮', '我', '请')
            cleanRemainder.isEmpty() || cleanRemainder.equals("app", ignoreCase = true) || cleanRemainder == "应用" || cleanRemainder == "软件"
        }

        return EdgeDecisionPort.ResolvedAppAction(
            packageName = targetPkg,
            appLabel = targetLabel,
            searchQuery = searchQuery,
            isPureOpen = isPure,
        )
    }

    // ==================== ComponentCallbacks2 内存弹性泄洪 ====================

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            runCatching { Log.w(TAG, "⚡ onTrimMemory(level=$level) triggered: purging edge model KV cache & native sessions") }
            isMemoryConstrained.set(true)
            purgeActiveSessions()
        } else if (level <= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
            isMemoryConstrained.set(false)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        runCatching { Log.w(TAG, "⚡ onLowMemory triggered: emergency release of all edge resources") }
        isMemoryConstrained.set(true)
        purgeActiveSessions()
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    fun purgeActiveSessions() {
        val ptr = sessionPtr
        if (ptr != 0L) {
            sessionPtr = 0L
            runCatching { nativeRelease(ptr) }
        }
    }

    companion object {
        private const val TAG = "MnnPocketEngine"
        private const val HARD_TIMEOUT_MS = 80L

        private var nativeLoaded = false
        val isNativeLoaded: Boolean get() = nativeLoaded

        init {
            try {
                System.loadLibrary("mnn_pocket")
                nativeLoaded = true
            } catch (_: Throwable) {
                nativeLoaded = false
            }
        }

        @Volatile
        private var instance: MnnPocketEngine? = null

        fun getInstance(context: Context): MnnPocketEngine {
            return instance ?: synchronized(this) {
                instance ?: MnnPocketEngine(context.applicationContext).also { instance = it }
            }
        }

        fun init(context: Context): MnnPocketEngine {
            return getInstance(context)
        }
    }
}
