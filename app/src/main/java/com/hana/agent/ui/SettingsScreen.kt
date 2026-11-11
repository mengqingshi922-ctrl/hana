package com.hana.agent.ui

import com.hana.agent.R
import com.hana.agent.i18n.tr
import com.hana.agent.i18n.trPlural
import androidx.compose.ui.res.stringResource
import com.hana.agent.settings.ProviderConnection
import android.app.ActivityManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hana.agent.ui.components.cardSurface
import com.hana.agent.ui.theme.HanaTheme
import com.hana.agent.ui.theme.HanaType
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.hana.agent.BuildConfig
import com.hana.agent.cap.sandbox.SandboxState
import com.hana.agent.capsule.CapsuleMode
import com.hana.agent.core.agent.SpeedFlags
import com.hana.agent.core.cap.sandbox.SandboxStatus
import com.hana.agent.core.model.ModelRole
import com.hana.agent.core.shell.BuiltinShellStatus
import com.hana.agent.record.RunStore
import com.hana.agent.replay.FileRouteStore
import com.hana.agent.settings.AccessibilityState
import com.hana.agent.settings.FeatureFlags
import com.hana.agent.settings.Prerequisites
import com.hana.agent.settings.Problem
import com.hana.agent.settings.ProviderStore
import com.hana.agent.settings.NetProxyStore
import com.hana.agent.cap.web.WebProxy
import com.hana.agent.settings.SystemStatus
import com.hana.agent.settings.notificationOverview
import com.hana.agent.task.ParallelCapability
import com.hana.agent.ui.components.ButtonKind
import com.hana.agent.ui.components.ButtonSize
import com.hana.agent.ui.components.GroupCard
import com.hana.agent.ui.components.GroupLabel
import com.hana.agent.ui.components.ListRow
import com.hana.agent.ui.components.HanaButton
import com.hana.agent.ui.components.PageScaffold
import com.hana.agent.ui.components.PillTone
import com.hana.agent.ui.components.RowChevron
import com.hana.agent.ui.components.RowValue
import com.hana.agent.ui.components.StateCard
import com.hana.agent.ui.components.StatusPill
import com.hana.agent.ui.components.TileTone
import com.hana.agent.ui.components.ValueTone
import com.hana.agent.ui.theme.HanaIcons
import com.hana.agent.core.usage.UsageQuery
import com.hana.agent.core.usage.UsageRange
import com.hana.agent.i18n.AppLanguage
import com.hana.agent.wechat.WechatConnectionState
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置里能进入的子页面;[Home] 是设置首页。 */
private enum class SettingsPage { Home, AppAccess, Usage, PhoneOps, Wechat, DuringWork, Capsule, Language, Notifications, WebSites, Sandbox, Routes, Recovery, SelfCheck, ComputerAccess, Developer, RunStats }

/**
 * 设置页:总状态卡 + 基本 / 隐私与数据 / 高级 / 关于四组。各子页面在这里切换(返回键先回首页),
 * 模型页由 [onOpenModels]、关于页由 [onOpenAbout] 交给 Activity。修复动作由 [onFix] 交给 Activity 去做(请求权限、跳转系统设置)。
 */
@Composable
fun SettingsScreen(
    store: ProviderStore,
    status: SystemStatus,
    androidRelease: String,
    onFix: (Fix) -> Unit,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenMemory: (() -> Unit)? = null,
    webSites: WebSitesState? = null,
    /** 开发者选项里的「自主模式」:当前值和换它的办法。 */
    autonomous: Boolean = false,
    onAutonomous: (Boolean) -> Unit = {},
    /** 查到的新版本号;有它时「关于 Hana」一行写「x 可更新」带红点。 */
    updateVersion: String? = null,
    onOpenAbout: () -> Unit = {},
) {
    val configs by store.configs.collectAsState()
    val roles by store.roles.collectAsState()
    // 开发者选项里的「看图定位已验证」跟着看图模型(看图用主模型时是主模型那条配置)
    val visionConfig = remember(configs, roles) { store.configFor(ModelRole.Vision) }
    var page by rememberSaveable { mutableStateOf(SettingsPage.Home) }
    var explaining by remember { mutableStateOf<Problem?>(null) }

    fun fix(f: Fix) = if (f == Fix.AddModel) onOpenModels() else onFix(f)
    val onProblem: (Problem) -> Unit = { p -> StatusCopy.directFix(p)?.let(::fix) ?: run { explaining = p } }

    // 并行任务的上限只在实验开关 parallelTasks 打开时出现;设备能力按此刻算,第二块屏建失败过就按失败算
    val context = LocalContext.current
    val wechat = remember { (context.applicationContext as com.hana.agent.HanaApp).wechat }
    val wechatState by wechat.state.collectAsState()
    val flags = remember { FeatureFlags.get(context) }
    var parallelMax by remember { mutableIntStateOf(flags.parallelMax()) }
    var runInBackground by remember { mutableStateOf(flags.runInBackground()) }
    var keepScreenOn by remember { mutableStateOf(flags.keepScreenOn()) }
    var muteTarget by remember { mutableStateOf(flags.muteTarget()) }
    var operatingPace by remember { mutableStateOf(flags.operatingPace()) }
    var selfConfig by remember { mutableStateOf(flags.selfConfig()) }
    var capsuleMode by remember { mutableStateOf(flags.capsuleMode()) }
    // 换语言时界面会重建,这里每次进来读一次即可
    val language = remember { AppLanguage.current(context) }
    val runtimeLimit by (context.applicationContext as com.hana.agent.HanaApp).taskState.parallelLimit.collectAsState()
    var speed by remember { mutableStateOf(flags.speedFlags()) }
    val routeStore = remember { FileRouteStore.shared(context) }
    var routeCount by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { routeCount = withContext(Dispatchers.IO) { runCatching { routeStore.count() }.getOrNull() } }
    val sandboxStatus by remember { SandboxState.get(context) }.status.collectAsState()
    // 网络代理:只在这里改;读设置要解密、看浏览器支不支持要加载 WebView,都放到后台
    val proxies = remember { NetProxyStore.get(context) }
    var proxy by remember { mutableStateOf<String?>(null) }
    var browserProxy by remember { mutableStateOf<WebProxy.Mode?>(null) }
    var editingProxy by remember { mutableStateOf(false) }
    LaunchedEffect(proxy) {
        if (proxy == null) proxy = withContext(Dispatchers.IO) { proxies.get() }
        browserProxy = withContext(Dispatchers.IO) { WebProxy.mode() }
    }
    val mcp = remember { (context.applicationContext as com.hana.agent.HanaApp).mcp }
    val mcpNetworkOn by mcp.settings.enabled.collectAsState()
    val mcpUsbOn by mcp.usb.enabled.collectAsState()
    val mcpOn = mcpNetworkOn || mcpUsbOn
    // 「Token 用量」一行的值:近 7 天的合计,账本每记一笔就重算
    val usage = remember { (context.applicationContext as com.hana.agent.HanaApp).usage }
    val usageVersion by usage.version.collectAsState()
    var usageWeek by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(usageVersion) {
        usageWeek = withContext(Dispatchers.Default) {
            UsageQuery.report(usage.snapshot(), UsageRange.LastDays(7), Instant.now(), ZoneId.systemDefault(), emptyMap()).totals.tokens
        }
    }
    val parallel = if (!speed.parallelTasks) null else ParallelSetting(
        parallelMax,
        ParallelCapability.detect(
            status.sdkInt, context.getSystemService(ActivityManager::class.java).isLowRamDevice, parallelMax,
            secondDisplayFailed = runtimeLimit.reason == ParallelCapability.SECOND_DISPLAY_FAILED,
        ),
    )
    // 开发者选项只在调试版和性能测试版里列出:发布版的各开关按评测结果统一定默认值
    val showDeveloper = com.hana.agent.record.DetailedTraces.supportedBuild
    val toHome = { page = SettingsPage.Home }
    // 通知页可以从首页或手机操作页进,返回回到进来的那一页
    var notificationsFrom by rememberSaveable { mutableStateOf(SettingsPage.Home) }
    val back = {
        page = when (page) {
            SettingsPage.RunStats -> SettingsPage.Developer
            SettingsPage.SelfCheck -> SettingsPage.Recovery
            SettingsPage.Notifications -> notificationsFrom
            else -> SettingsPage.Home
        }
    }

    if (page != SettingsPage.Home) BackHandler { back() }

    val visiblePage = if (!showDeveloper && (page == SettingsPage.Developer || page == SettingsPage.RunStats)) SettingsPage.Home else page
    when (visiblePage) {
        SettingsPage.Home -> SettingsContent(
            status = status, androidRelease = androidRelease,
            mainModel = roles.main?.let { id -> configs.firstOrNull { it.id == id } }?.let(ProviderConnection::displayName),
            onBack = onBack, onProblem = onProblem, onOpenModels = onOpenModels,
            proxy = proxy, proxyNote = proxy?.let { NetProxyCopy.subtitle(it, browserProxy) }, onOpenProxy = { editingProxy = true },
            usageTokens = usageWeek, onOpenUsage = { page = SettingsPage.Usage },
            wechatState = wechatState, wechatBound = wechat.store.account() != null,
            onOpenWechat = { page = SettingsPage.Wechat },
            onOpenPhoneOps = { page = SettingsPage.PhoneOps },
            onOpenAppAccess = { page = SettingsPage.AppAccess },
            onOpenDuringWork = { page = SettingsPage.DuringWork },
            capsuleMode = capsuleMode, onOpenCapsule = { page = SettingsPage.Capsule },
            onOpenNotifications = { notificationsFrom = SettingsPage.Home; page = SettingsPage.Notifications },
            language = language, onOpenLanguage = { page = SettingsPage.Language },
            onOpenMemory = onOpenMemory,
            webSitesCount = webSites?.sites?.size, onOpenWebSites = { page = SettingsPage.WebSites },
            sandbox = sandboxStatus, onOpenSandbox = { page = SettingsPage.Sandbox },
            routes = routeCount, onOpenRoutes = { page = SettingsPage.Routes },
            onOpenRecovery = { page = SettingsPage.Recovery },
            computerAccess = mcpOn, onOpenComputerAccess = { page = SettingsPage.ComputerAccess },
            showDeveloper = showDeveloper, onOpenDeveloper = { page = SettingsPage.Developer },
            versionName = BuildConfig.VERSION_NAME, updateVersion = updateVersion, onOpenAbout = onOpenAbout,
        )
        SettingsPage.AppAccess -> AppAccessScreen(onBack = toHome)
        SettingsPage.Wechat -> WechatSettingsScreen(wechat, onBack = toHome)
        SettingsPage.Usage -> UsageScreen(usage, store.providers, onBack = toHome)
        SettingsPage.PhoneOps -> PhoneOpsScreen(
            status, androidRelease, onProblem,
            onOpenNotifications = { notificationsFrom = SettingsPage.PhoneOps; page = SettingsPage.Notifications },
            onRepair = { onFix(Fix.RepairPhoneHelper) }, onUnpair = { onFix(Fix.UnpairPhoneHelper) }, onBack = toHome,
        )
        SettingsPage.Notifications -> NotificationsScreen(status, onFix = ::fix, onBack = back)
        SettingsPage.DuringWork -> DuringWorkScreen(
            keepScreenOn, onKeepScreenOn = { flags.setKeepScreenOn(it); keepScreenOn = it },
            muteTarget, onMuteTarget = { flags.setMuteTarget(it); muteTarget = it },
            parallel, onParallelMax = { flags.setParallelMax(it); parallelMax = it }, onBack = toHome,
            operatingPace = operatingPace, onOperatingPace = { flags.setOperatingPace(it); operatingPace = it },
            selfConfig = selfConfig, onSelfConfig = { flags.setSelfConfig(it); selfConfig = it },
            runInBackground = runInBackground, onRunInBackground = { flags.setRunInBackground(it); runInBackground = it },
        )
        SettingsPage.Language -> LanguageScreen(language, onLanguage = { AppLanguage.set(context, it) }, onBack = toHome)
        SettingsPage.Capsule -> CapsuleScreen(capsuleMode, onMode = { flags.setCapsuleMode(it); capsuleMode = it }, onBack = toHome)
        // 没有数据时首页不显示入口,走不到这一页
        SettingsPage.WebSites -> webSites?.let { WebSitesScreen(it, onBack = toHome) }
        SettingsPage.Sandbox -> SandboxScreen(onBack = toHome)
        SettingsPage.Routes -> RoutesScreen(
            routeCount,
            onClear = { scope.launch { routeCount = withContext(Dispatchers.IO) { routeStore.clear(); routeStore.count() } } },
            onBack = toHome,
        )
        SettingsPage.Recovery -> RecoveryScreen(onBack = toHome, onOpenSelfCheck = { page = SettingsPage.SelfCheck })
        SettingsPage.SelfCheck -> SelfCheckScreen(status, onBack = back)
        SettingsPage.ComputerAccess -> McpScreen(mcp, onBack = toHome)
        SettingsPage.Developer -> DeveloperOptionsScreen(
            speed, onSpeed = { flags.setSpeed(it); speed = it }, onBack = toHome,
            autonomous = autonomous, onAutonomous = onAutonomous,
            grounding = visionConfig?.let { GroundingSetting(it.model, it.capabilities.groundingVerified) },
            onGrounding = { verified -> scope.launch { withContext(Dispatchers.IO) { store.setVisionGroundingVerified(verified) } } },
            onOpenRunStats = { page = SettingsPage.RunStats },
        )
        SettingsPage.RunStats -> RunStatsScreen(remember { RunStore(context).metrics }, onBack = back)
    }

    if (editingProxy) NetProxyDialog(
        initial = proxy.orEmpty(),
        onDismiss = { editingProxy = false },
        onSave = { v ->
            scope.launch {
                withContext(Dispatchers.IO) { proxies.set(v) }
                proxy = v.trim()
                editingProxy = false
            }
        },
    )

    explaining?.let { p ->
        val e = StatusCopy.explain(p, androidRelease)
        AlertDialog(
            onDismissRequest = { explaining = null },
            title = { Text(e.title) },
            text = { Text(e.body) },
            confirmButton = {
                if (e.action != null && e.fix != null) {
                    TextButton({ explaining = null; fix(e.fix) }) { Text(e.action) }
                } else {
                    TextButton({ explaining = null }) { Text(stringResource(R.string.ok_got_it)) }
                }
            },
            dismissButton = if (e.fix != null) ({ TextButton({ explaining = null }) { Text(stringResource(R.string.cancel)) } }) else null,
        )
    }
}

/**
 * 不带状态的设置首页,便于测试。可选的入口([usageTokens]、[onOpenMemory]、[webSitesCount]、[grantsCount]、[routes]、[sandbox])
 * 为空时对应那一行不显示;[showDeveloper] 为假(发布版)时没有开发者选项。
 */
@Composable
fun SettingsContent(
    status: SystemStatus,
    androidRelease: String,
    /** 当前主模型的名字;没有可用的主模型时为空。 */
    mainModel: String?,
    onBack: () -> Unit,
    onProblem: (Problem) -> Unit,
    onOpenModels: () -> Unit,
    /** 网络代理(空串表示不用);为空(还没读出来)时不显示这一行。 */
    proxy: String? = null,
    /** 网络代理那一行下面的说明。 */
    proxyNote: String? = null,
    onOpenProxy: () -> Unit = {},
    /** 近 7 天用了多少 token;为空(还没统计出来)时不显示这一行。 */
    usageTokens: Long? = null,
    onOpenUsage: () -> Unit = {},
    wechatState: WechatConnectionState = WechatConnectionState.NeedsBinding,
    wechatBound: Boolean = false,
    onOpenWechat: () -> Unit = {},
    onOpenPhoneOps: () -> Unit = {},
    onOpenAppAccess: () -> Unit = {},
    onOpenDuringWork: () -> Unit = {},
    /** 悬浮胶囊此刻的选择,写在「悬浮胶囊」一行的右边。 */
    capsuleMode: CapsuleMode = CapsuleMode.DEFAULT,
    onOpenCapsule: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    /** 此刻选的界面语言,写在「语言」一行的右边。 */
    language: AppLanguage = AppLanguage.System,
    onOpenLanguage: () -> Unit = {},
    onOpenMemory: (() -> Unit)? = null,
    webSitesCount: Int? = null,
    onOpenWebSites: () -> Unit = {},
    sandbox: SandboxStatus? = null,
    onOpenSandbox: () -> Unit = {},
    /** 已记住的操作路线条数;为空(还没读出来)时不显示这一行。 */
    routes: Int? = null,
    onOpenRoutes: () -> Unit = {},
    onOpenRecovery: () -> Unit = {},
    /** 电脑连接(MCP)开着没有;为空时不显示这一行。 */
    computerAccess: Boolean? = null,
    onOpenComputerAccess: () -> Unit = {},
    showDeveloper: Boolean = false,
    onOpenDeveloper: () -> Unit = {},
    versionName: String = "",
    updateVersion: String? = null,
    onOpenAbout: () -> Unit = {},
) {
    val check = Prerequisites.check(status, hasMainModel = mainModel != null)
    val c = HanaTheme.colors
    PageScaffold(stringResource(R.string.settings_title), onBack) {
        StatusSummary(check, onProblem)

        // 1. 核心 Bento 双磁贴:AI模型与手机操作独立卡片
        SettingsBentoHero(mainModel, status, onOpenModels, onOpenPhoneOps)

        // 2. 连接与数据 Bento 双磁贴:微信联动与 Token 统计
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val wechatConnected = wechatBound && wechatState == WechatConnectionState.Connected
            BentoSettingTile(
                icon = HanaIcons.Chats,
                iconBg = if (wechatConnected) c.okSoft else c.fill,
                iconTint = if (wechatConnected) c.ok else c.ink,
                title = stringResource(R.string.wechat_title),
                subtitle = if (wechatConnected) "已连接 · 微信发任务" else "微信发任务",
                modifier = Modifier.weight(1f),
                badge = {
                    WechatEntryStatus(wechatState, wechatBound)
                },
                onClick = onOpenWechat,
            )

            val tokenText = usageTokens?.let { UsageCopy.settingsValue(it) } ?: "查看明细"
            BentoSettingTile(
                icon = HanaIcons.Chart,
                iconBg = c.accentSoft,
                iconTint = c.accent,
                title = UsageCopy.TITLE,
                subtitle = tokenText,
                modifier = Modifier.weight(1f),
                badge = {
                    Icon(HanaIcons.Chevron, null, Modifier.size(13.dp), tint = c.ink3)
                },
                onClick = onOpenUsage,
            )
        }

        // 3. 智能体交互与运行偏好卡片组
        GroupLabel("智能体偏好")
        GroupCard {
            ListRow(
                HanaIcons.Moon, stringResource(R.string.settings_during_work), stringResource(R.string.settings_during_work_sub),
                first = true, tone = TileTone.Warn, onClick = onOpenDuringWork,
            ) { RowChevron() }

            ListRow(
                HanaIcons.Message, CapsuleSettingsCopy.TITLE, CapsuleSettingsCopy.SUBTITLE,
                tone = TileTone.Accent, onClick = onOpenCapsule,
            ) {
                RowValue(CapsuleSettingsCopy.label(capsuleMode))
                RowChevron()
            }

            ListRow(
                HanaIcons.Shield, stringResource(R.string.appaccess_title), stringResource(R.string.appaccess_row_sub),
                tone = TileTone.Neutral, onClick = onOpenAppAccess,
            ) { RowChevron() }
        }

        // 4. 系统与通用卡片组
        GroupLabel("系统与通用")
        GroupCard {
            var first = true
            fun take(): Boolean = first.also { first = false }

            proxy?.let {
                ListRow(HanaIcons.Link, NetProxyCopy.TITLE, proxyNote, first = take(), tone = TileTone.Accent, onClick = onOpenProxy) {
                    RowValue(NetProxyCopy.value(it))
                    RowChevron()
                }
            }

            ListRow(HanaIcons.Globe, stringResource(R.string.language_title), first = take(), tone = TileTone.Neutral, onClick = onOpenLanguage) {
                RowValue(languageLabel(language))
                RowChevron()
            }

            val (notifText, notifTone) = StatusCopy.notificationSummary(status.notificationOverview().summary)
            val notifTileTone = if (notifTone == ValueTone.Warn || notifTone == ValueTone.Bad) TileTone.Warn else TileTone.Neutral
            ListRow(HanaIcons.Bell, StatusCopy.NOTIFICATIONS, stringResource(R.string.settings_notif_sub), first = take(), tone = notifTileTone, onClick = onOpenNotifications) {
                RowValue(notifText, notifTone)
                RowChevron()
            }
        }

        val privacyRows = onOpenMemory != null || webSitesCount != null
        if (privacyRows) {
            GroupLabel(stringResource(R.string.settings_privacy))
            GroupCard {
                var first = true
                fun take(): Boolean = first.also { first = false }
                onOpenMemory?.let {
                    ListRow(HanaIcons.Heart, stringResource(R.string.memory_and_skills), stringResource(R.string.settings_memory_sub), first = take(), onClick = it) { RowChevron() }
                }
                webSitesCount?.let {
                    ListRow(HanaIcons.Star, stringResource(R.string.settings_web_logins), first = take(), onClick = onOpenWebSites) { RowValue(trPlural(R.plurals.settings_count_sites, it)); RowChevron() }
                }
            }
        }

        val sandboxRow = sandbox?.takeIf { it !is SandboxStatus.Unsupported }
        val recoveryRow = status.sdkInt >= Prerequisites.MIN_SDK
        if (sandboxRow != null || routes != null || recoveryRow || computerAccess != null || showDeveloper) {
            GroupLabel(stringResource(R.string.settings_advanced))
            GroupCard {
                var first = true
                fun take(): Boolean = first.also { first = false }
                if (sandboxRow != null) {
                    ListRow(HanaIcons.Terminal, stringResource(R.string.settings_sandbox), first = take(), onClick = onOpenSandbox) {
                        RowValue(sandboxValue(sandboxRow)); RowChevron()
                    }
                }
                routes?.let {
                    ListRow(HanaIcons.Route, stringResource(R.string.settings_routes), first = take(), onClick = onOpenRoutes) { RowValue(trPlural(R.plurals.settings_count_routes, it)); RowChevron() }
                }
                if (recoveryRow) {
                    ListRow(HanaIcons.Wrench, stringResource(R.string.settings_recovery), first = take(), onClick = onOpenRecovery) { RowChevron() }
                }
                computerAccess?.let { on ->
                    ListRow(HanaIcons.Desktop, stringResource(R.string.mcp_title), first = take(), onClick = onOpenComputerAccess) {
                        RowValue(stringResource(if (on) R.string.state_on else R.string.state_off)); RowChevron()
                    }
                }
                if (showDeveloper) {
                    ListRow(HanaIcons.Code, stringResource(R.string.settings_developer), first = take(), onClick = onOpenDeveloper) { RowChevron() }
                }
            }
        }

        GroupCard {
            ListRow(HanaIcons.Info, stringResource(R.string.settings_about), first = true, onClick = onOpenAbout) {
                TrailingValue(AboutCopy.settingsValue(updateVersion, versionName))
                RowChevron()
            }
        }
    }
}

/** 核心两列 Bento 磁贴卡片:模型与手机操作 */
@Composable
private fun SettingsBentoHero(
    mainModel: String?,
    status: SystemStatus,
    onOpenModels: () -> Unit,
    onOpenPhoneOps: () -> Unit,
) {
    val c = HanaTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 模型 Bento 磁贴
        BentoSettingTile(
            icon = HanaIcons.Spark,
            title = stringResource(R.string.settings_model),
            subtitle = mainModel ?: stringResource(R.string.settings_not_set),
            modifier = Modifier.weight(1f),
            badge = {
                Icon(HanaIcons.Chevron, null, Modifier.size(14.dp), tint = c.ink3)
            },
            onClick = onOpenModels,
        )

        // 手机操作 Bento 磁贴
        val phoneReady = status.sdkInt >= Prerequisites.MIN_SDK &&
            status.builtinShell == BuiltinShellStatus.Running &&
            status.accessibility == AccessibilityState.On
        BentoSettingTile(
            icon = HanaIcons.Phone,
            title = stringResource(R.string.settings_phone_ops),
            subtitle = if (phoneReady) stringResource(R.string.pill_available) else stringResource(R.string.pill_needs_attention),
            modifier = Modifier.weight(1f),
            badge = {
                if (status.sdkInt < Prerequisites.MIN_SDK) {
                    StatusPill(stringResource(R.string.pill_unsupported), PillTone.Neutral)
                } else if (phoneReady) {
                    StatusPill(stringResource(R.string.pill_available), PillTone.Ok)
                } else {
                    StatusPill(stringResource(R.string.pill_needs_attention), PillTone.Warn)
                }
            },
            onClick = onOpenPhoneOps,
        )
    }
}

/** 现代 Bento 风格的设置入口磁贴卡片 */
@Composable
private fun BentoSettingTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    iconBg: Color = HanaTheme.colors.fill,
    iconTint: Color = HanaTheme.colors.ink,
    badge: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = HanaTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .cardSurface(c, shape)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .background(iconBg, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(15.dp), tint = iconTint)
            }
            if (badge != null) badge()
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = HanaType.BodyStrong.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = HanaType.Caption.copy(fontSize = 12.5.sp),
                color = c.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 「手机操作」一行尾部的状态胶囊:无障碍和内置助手都就绪才是「可用」。 */
@Composable
private fun PhoneOpsPill(status: SystemStatus) {
    when {
        status.sdkInt < Prerequisites.MIN_SDK -> StatusPill(stringResource(R.string.pill_unsupported), PillTone.Neutral)
        status.builtinShell == BuiltinShellStatus.Running && status.accessibility == AccessibilityState.On -> StatusPill(stringResource(R.string.pill_available), PillTone.Ok)
        else -> StatusPill(stringResource(R.string.pill_needs_attention), PillTone.Warn)
    }
}

private fun sandboxValue(s: SandboxStatus): String = when (s) {
    is SandboxStatus.Ready -> tr(R.string.sandbox_ready)
    is SandboxStatus.Installing -> tr(R.string.sandbox_preparing)
    is SandboxStatus.Broken -> tr(R.string.pill_needs_attention)
    else -> tr(R.string.sandbox_not_prepared)
}

/**
 * 页面顶上的总状态:一切正常是「一切就绪」;否则变成第一个问题的提示,带修复按钮。
 * 焦点提醒改不了,只在手机操作页的「系统版本」一行里提示,不占这里。
 */
@Composable
private fun StatusSummary(check: Prerequisites.Check, onProblem: (Problem) -> Unit) {
    val problem = check.problems.firstOrNull { it != Problem.FocusMayLeak }
    if (problem == null) {
        StateCard(HanaIcons.Check, stringResource(R.string.all_ready), stringResource(R.string.settings_all_ready_detail), TileTone.Ok, Modifier.testTag("settings-status"))
    } else {
        val copy = StatusCopy.banner(problem)
        StateCard(
            HanaIcons.Exclaim, copy.title, copy.detail,
            if (problem in check.blockers) TileTone.Bad else TileTone.Warn,
            Modifier.testTag("settings-status"),
        ) {
            HanaButton(copy.link, { onProblem(problem) }, kind = ButtonKind.Tonal, size = ButtonSize.Small)
        }
    }
}
