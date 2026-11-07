package com.wangzan101.hana.ui

import com.wangzan101.hana.i18n.tr
import com.wangzan101.hana.i18n.trPlural
import com.wangzan101.hana.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.wangzan101.hana.settings.FeatureFlags
import com.wangzan101.hana.settings.ThemeMode
import com.wangzan101.hana.ui.components.MikaSwitch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.wangzan101.hana.i18n.AppLanguage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.hana.capsule.CapsuleMode
import com.wangzan101.hana.core.shell.BuiltinShellStatus
import com.wangzan101.hana.settings.AccessibilityState
import com.wangzan101.hana.settings.ChannelRow
import com.wangzan101.hana.settings.Prerequisites
import com.wangzan101.hana.settings.Problem
import com.wangzan101.hana.settings.SystemStatus
import com.wangzan101.hana.settings.notificationOverview
import com.wangzan101.hana.task.ParallelLimit
import com.wangzan101.hana.ui.components.FooterNote
import com.wangzan101.hana.ui.components.GroupCard
import com.wangzan101.hana.ui.components.GroupLabel
import com.wangzan101.hana.ui.components.ListRow
import com.wangzan101.hana.ui.components.PageScaffold
import com.wangzan101.hana.ui.components.RadioRow
import com.wangzan101.hana.ui.components.RowChevron
import com.wangzan101.hana.ui.components.RowValue
import com.wangzan101.hana.ui.components.TileTone
import com.wangzan101.hana.ui.components.ValueTone
import com.wangzan101.hana.ui.theme.MikaIcons
import com.wangzan101.hana.ui.theme.MikaTheme
import com.wangzan101.hana.ui.theme.MikaType
import com.wangzan101.hana.core.cap.phone.OperatingPace

// ---------- 手机操作 ----------

/** 设置 › 手机操作:权限与配对的状态,以及重新配对、解除配对。点哪一项的处理方式和从前一样,交给 [onProblem]。 */
@Composable
fun PhoneOpsScreen(
    status: SystemStatus,
    androidRelease: String,
    onProblem: (Problem) -> Unit,
    onOpenNotifications: () -> Unit,
    onRepair: () -> Unit,
    onUnpair: () -> Unit,
    onBack: () -> Unit,
) {
    val check = Prerequisites.check(status, hasMainModel = true)
    var confirmUnpair by remember { mutableStateOf(false) }
    PageScaffold(stringResource(R.string.settings_phone_ops), onBack) {
        GroupLabel(stringResource(R.string.settings_status))
        GroupCard {
            val (a11yText, a11yTone) = StatusCopy.accessibilityValue(status.accessibility)
            ListRow(
                MikaIcons.Hand, stringResource(R.string.settings_a11y), stringResource(R.string.settings_a11y_sub), first = true,
                onClick = check.problems.firstOrNull { it is Problem.Accessibility }?.let { { onProblem(it) } },
            ) { RowValue(a11yText, a11yTone) }
            // 安卓 12 以下手机功能不可用,不列这一行(系统版本那一行已说明)
            if (status.sdkInt >= Prerequisites.MIN_SDK) {
                val helper = status.builtinShell
                val (hText, hTone) = PhoneSetupCopy.statusValue(helper)
                val reconnect = PhoneSetupCopy.canReconnect(helper)
                ListRow(
                    MikaIcons.Link, PhoneSetupCopy.ROW_TITLE,
                    if (reconnect && helper is BuiltinShellStatus.Failed) PhoneSetupCopy.failure(helper.reason).title
                    else PhoneSetupCopy.statusLine(helper, status.usbDebugging),
                    onClick = { onProblem(Problem.PhoneHelper(helper)) },
                ) { if (reconnect) RowValue(PhoneSetupCopy.RECONNECT, ValueTone.Link) else RowValue(hText, hTone) }
            }
            val (notifText, notifTone) = StatusCopy.notificationSummary(status.notificationOverview().summary)
            ListRow(MikaIcons.Bell, StatusCopy.NOTIFICATIONS, stringResource(R.string.settings_notif_progress_sub), onClick = onOpenNotifications) {
                RowValue(notifText, notifTone)
                RowChevron()
            }
            val tooOld = status.sdkInt < Prerequisites.MIN_SDK
            val focusWarning = Problem.FocusMayLeak in check.problems
            ListRow(
                MikaIcons.Phone, stringResource(R.string.settings_system_version),
                if (focusWarning) stringResource(R.string.settings_android_focus, androidRelease) else stringResource(R.string.settings_android, androidRelease),
                onClick = when {
                    tooOld -> ({ onProblem(Problem.SystemTooOld) })
                    focusWarning -> ({ onProblem(Problem.FocusMayLeak) })
                    else -> null
                },
            ) {
                when {
                    tooOld -> RowValue(stringResource(R.string.pill_unsupported), ValueTone.Bad)
                    focusWarning -> RowValue(stringResource(R.string.pill_available), ValueTone.Plain)
                    else -> RowValue(stringResource(R.string.settings_meets), ValueTone.Ok)
                }
            }
        }
        if (status.sdkInt >= Prerequisites.MIN_SDK) {
            GroupLabel(stringResource(R.string.phone_channel))
            GroupCard {
                ListRow(MikaIcons.Link, PhoneSetupCopy.REPAIR, PhoneSetupCopy.REPAIR_SUBTITLE, first = true, onClick = onRepair) {
                    RowChevron()
                }
                val paired = status.builtinShell != BuiltinShellStatus.NotPaired
                ListRow(
                    MikaIcons.Close, PhoneSetupCopy.UNPAIR, PhoneSetupCopy.UNPAIR_SUBTITLE,
                    titleColor = if (paired) MikaTheme.colors.bad else MikaTheme.colors.ink3,
                    onClick = if (paired) ({ confirmUnpair = true }) else null,
                )
            }
            FooterNote(PhoneSetupCopy.usageNote(status.helperRestart))
        }
    }
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text(PhoneSetupCopy.UNPAIR_TITLE) },
            text = { Text(PhoneSetupCopy.UNPAIR_BODY) },
            confirmButton = {
                TextButton({ confirmUnpair = false; onUnpair() }) { Text(PhoneSetupCopy.UNPAIR_CONFIRM, color = MikaTheme.colors.bad) }
            },
            dismissButton = { TextButton({ confirmUnpair = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// ---------- 通知 ----------

/**
 * 设置 › 通知:只看状态、去系统里改。顶上是 App 通知总开关,下面三个任务通知频道各一行(什么时候发 + 现在会不会弹出),
 * 点哪一行都由 [onFix] 打开对应的系统设置页(总开关没开时先请求权限或打开应用通知页)。
 */
@Composable
fun NotificationsScreen(status: SystemStatus, onFix: (Fix) -> Unit, onBack: () -> Unit) {
    val o = status.notificationOverview()
    PageScaffold(StatusCopy.NOTIFICATIONS, onBack) {
        GroupCard {
            ListRow(
                MikaIcons.Bell, stringResource(R.string.settings_notif_master),
                if (o.appAllowed) StatusCopy.NOTIF_APP_SUBTITLE_ON else StatusCopy.NOTIF_APP_SUBTITLE_OFF,
                first = true, tone = if (o.appAllowed) TileTone.Neutral else TileTone.Bad,
                onClick = if (o.appAllowed) null else ({ onFix(Fix.AllowNotifications) }),
            ) {
                if (o.appAllowed) RowValue(stringResource(R.string.settings_allowed), ValueTone.Ok) else RowValue(StatusCopy.HEADS_UP_ACTION, ValueTone.Link)
            }
        }
        GroupLabel(stringResource(R.string.settings_task_notifs))
        GroupCard {
            ChannelRowItem(StatusCopy.ATTENTION_CHANNEL, StatusCopy.NOTIF_ATTENTION_WHEN, o.attention, Fix.OpenAttentionChannel, onFix, first = true)
            ChannelRowItem(StatusCopy.RESULT_CHANNEL, StatusCopy.NOTIF_RESULT_WHEN, o.result, Fix.OpenResultChannel, onFix)
            ChannelRowItem(StatusCopy.PROGRESS_CHANNEL, StatusCopy.NOTIF_PROGRESS_WHEN, o.progress, Fix.OpenProgressChannel, onFix)
        }
        FooterNote(StatusCopy.NOTIF_FOOTER)
    }
}

@Composable
private fun ChannelRowItem(title: String, whenText: String, row: ChannelRow, fix: Fix, onFix: (Fix) -> Unit, first: Boolean = false) {
    val (text, tone) = StatusCopy.channelLevelValue(row)
    ListRow(
        MikaIcons.Bell, title, whenText, first = first,
        tone = if (row.warn) TileTone.Warn else TileTone.Neutral,
        onClick = { onFix(fix) },
    ) {
        RowValue(text, tone)
        RowChevron()
    }
}

// ---------- 干活时 ----------

/** 并行任务的设置:用户设的上限 [max](1–3),和设备此刻允许的 [limit]。 */
data class ParallelSetting(val max: Int, val limit: ParallelLimit)

/**
 * 设置 › 干活时:屏幕常亮、被操作的 App 静音(默认都开,可以关);[parallel] 不为空(实验开关打开了)时多一行同时进行的任务数。
 * 下面一组是操作节奏:快 / 标准(默认) / 像人,选了下一个任务起生效。
 */
@Composable
fun DuringWorkScreen(
    keepScreenOn: Boolean, onKeepScreenOn: (Boolean) -> Unit,
    muteTarget: Boolean, onMuteTarget: (Boolean) -> Unit,
    parallel: ParallelSetting?, onParallelMax: (Int) -> Unit, onBack: () -> Unit,
    operatingPace: OperatingPace = OperatingPace.DEFAULT, onOperatingPace: (OperatingPace) -> Unit = {},
    selfConfig: Boolean = false, onSelfConfig: (Boolean) -> Unit = {},
    runInBackground: Boolean = true, onRunInBackground: (Boolean) -> Unit = {},
) {
    PageScaffold(stringResource(R.string.settings_during_work), onBack) {
        GroupCard {
            ListRow(
                MikaIcons.Moon, stringResource(R.string.settings_run_in_background), stringResource(R.string.settings_run_in_background_sub),
                first = true, onClick = { onRunInBackground(!runInBackground) },
            ) {
                MikaSwitch(runInBackground, onRunInBackground, Modifier.testTag("work-run-in-background"))
            }
            ListRow(
                MikaIcons.Sun, stringResource(R.string.settings_keep_on), stringResource(R.string.settings_keep_on_sub),
                onClick = { onKeepScreenOn(!keepScreenOn) },
            ) {
                MikaSwitch(keepScreenOn, onKeepScreenOn, Modifier.testTag("work-keep-screen-on"))
            }
            ListRow(
                MikaIcons.Mute, stringResource(R.string.settings_mute), stringResource(R.string.settings_mute_sub),
                onClick = { onMuteTarget(!muteTarget) },
            ) {
                MikaSwitch(muteTarget, onMuteTarget, Modifier.testTag("work-mute-target"))
            }
            parallel?.let { ParallelRow(it, onParallelMax) }
        }
        // 允许 Mika 改自己的设置:默认关,打开后任务里才有 settings_list、settings_set
        GroupCard {
            ListRow(
                MikaIcons.Spark, stringResource(R.string.settings_self_config),
                stringResource(R.string.settings_self_config_sub),
                first = true,
                onClick = { onSelfConfig(!selfConfig) },
            ) {
                MikaSwitch(selfConfig, onSelfConfig, Modifier.testTag("work-self-config"))
            }
        }
        GroupLabel(stringResource(R.string.settings_pace))
        PaceSelectorCards(operatingPace, onOperatingPace)
        FooterNote(stringResource(R.string.settings_pace_foot))
    }
}

@Composable
private fun PaceSelectorCards(
    current: OperatingPace,
    onSelect: (OperatingPace) -> Unit,
) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OperatingPace.entries.forEach { p ->
            val selected = p == current
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .then(
                        if (selected) Modifier
                            .background(c.surface, shape)
                            .border(1.6.dp, c.accent, shape)
                        else Modifier
                            .background(c.fill, shape)
                            .border(0.5.dp, c.hair, shape)
                    )
                    .clickable { onSelect(p) }
                    .padding(horizontal = 6.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    paceLabel(p),
                    style = MikaType.BodyStrong.copy(
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    ),
                    color = if (selected) c.accent else c.ink,
                )
                Text(
                    when (p) {
                        OperatingPace.FAST -> "极速跳过"
                        OperatingPace.STANDARD -> "标准稳定"
                        OperatingPace.HUMAN -> "拟真拟人"
                    },
                    style = MikaType.Caption.copy(fontSize = 11.sp),
                    color = c.ink3,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun paceLabel(p: OperatingPace): String = stringResource(
    when (p) {
        OperatingPace.FAST -> R.string.settings_pace_fast
        OperatingPace.STANDARD -> R.string.settings_pace_standard
        OperatingPace.HUMAN -> R.string.settings_pace_human
    },
)

@Composable
private fun paceDetail(p: OperatingPace): String = stringResource(
    when (p) {
        OperatingPace.FAST -> R.string.settings_pace_fast_detail
        OperatingPace.STANDARD -> R.string.settings_pace_standard_detail
        OperatingPace.HUMAN -> R.string.settings_pace_human_detail
    },
)

/** 同时进行几个任务:设备不支持时灰掉、写明原因,上限固定为 1。 */
@Composable
private fun ParallelRow(p: ParallelSetting, onMax: (Int) -> Unit) {
    val supported = p.limit.reason == null
    ListRow(
        MikaIcons.Phone, stringResource(R.string.settings_parallel),
        subtitle = p.limit.reason ?: stringResource(R.string.settings_parallel_sub),
        titleColor = if (supported) MikaTheme.colors.ink else MikaTheme.colors.ink3,
    ) {
        if (!supported) {
            RowValue(trPlural(R.plurals.settings_count_tasks, 1))
            return@ListRow
        }
        Text(
            "−", Modifier.clickable(enabled = p.max > 1) { onMax(p.max - 1) }.padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 18.sp, color = if (p.max > 1) MikaTheme.colors.accent else MikaTheme.colors.ink3,
        )
        RowValue(trPlural(R.plurals.settings_count_tasks, p.max))
        Text(
            "+", Modifier.clickable(enabled = p.max < 3) { onMax(p.max + 1) }.padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 18.sp, color = if (p.max < 3) MikaTheme.colors.accent else MikaTheme.colors.ink3,
        )
    }
}

// ---------- 悬浮胶囊 ----------

/** 设置 › 悬浮胶囊的文字。 */
object CapsuleSettingsCopy {
    val TITLE: String get() = tr(R.string.capsule_settings_title)
    val SUBTITLE: String get() = tr(R.string.capsule_settings_sub)
    val FOOTNOTE: String get() = tr(R.string.capsule_settings_foot)
    /** 系统可能关掉后台的 App,胶囊跟着不见:请用户在系统设置里放行。 */
    val BACKGROUND: String get() = tr(R.string.capsule_settings_background)

    fun label(mode: CapsuleMode): String = when (mode) {
        CapsuleMode.Off -> tr(R.string.capsule_mode_off)
        CapsuleMode.DuringTasks -> tr(R.string.capsule_mode_tasks)
        CapsuleMode.Always -> tr(R.string.capsule_mode_always)
    }

    fun detail(mode: CapsuleMode): String = when (mode) {
        CapsuleMode.Off -> tr(R.string.capsule_mode_off_detail)
        CapsuleMode.DuringTasks -> tr(R.string.capsule_mode_tasks_detail)
        CapsuleMode.Always -> tr(R.string.capsule_mode_always_detail)
    }
}

/** 设置 › 悬浮胶囊:关闭 / 有任务时显示(默认) / 一直显示,选了当场生效。 */
@Composable
fun CapsuleScreen(mode: CapsuleMode, onMode: (CapsuleMode) -> Unit, onBack: () -> Unit) {
    PageScaffold(CapsuleSettingsCopy.TITLE, onBack) {
        GroupLabel("显示时机")
        CapsuleModeCards(mode, onMode)
        Spacer(Modifier.height(8.dp))
        FooterNote(CapsuleSettingsCopy.FOOTNOTE)
        FooterNote(CapsuleSettingsCopy.BACKGROUND)
    }
}

@Composable
private fun CapsuleModeCards(
    current: CapsuleMode,
    onSelect: (CapsuleMode) -> Unit,
) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CapsuleMode.entries.forEach { m ->
            val selected = m == current
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .then(
                        if (selected) Modifier
                            .background(c.surface, shape)
                            .border(1.6.dp, c.accent, shape)
                        else Modifier
                            .background(c.fill, shape)
                            .border(0.5.dp, c.hair, shape)
                    )
                    .clickable { onSelect(m) }
                    .padding(horizontal = 6.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    CapsuleSettingsCopy.label(m),
                    style = MikaType.BodyStrong.copy(
                        fontSize = 13.5.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    ),
                    color = if (selected) c.accent else c.ink,
                )
                Text(
                    when (m) {
                        CapsuleMode.Off -> "完全隐藏"
                        CapsuleMode.DuringTasks -> "后台时 (默认)"
                        CapsuleMode.Always -> "常驻边缘"
                    },
                    style = MikaType.Caption.copy(fontSize = 11.sp),
                    color = c.ink3,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 设置 › 语言与外观:跟随系统、浅色、深色微缩窗口卡片;各语种单选。 */
@Composable
fun LanguageScreen(language: AppLanguage, onLanguage: (AppLanguage) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val flags = remember { FeatureFlags.get(context) }
    // 跟着设置走:任务里改了外观,这页也对得上
    val themeMode by remember(flags) { flags.themeModeChanges() }.collectAsState(flags.themeMode())
    PageScaffold(stringResource(R.string.appearance_title), onBack) {
        // 语言和外观放在同一页:都是「界面长什么样」
        GroupLabel(stringResource(R.string.appearance_group))
        GroupCard {
            ThemePickerCards(
                current = themeMode,
                onSelect = { m ->
                    // 界面跟着设置当场换颜色,不重建,页面和滚动位置都不丢
                    if (m != themeMode) flags.setThemeMode(m)
                },
            )
        }
        if (themeMode == ThemeMode.System) {
            FooterNote(stringResource(R.string.appearance_system_detail))
        }

        GroupLabel(stringResource(R.string.language_title))
        GroupCard {
            AppLanguage.entries.forEachIndexed { i, l ->
                RadioRow(
                    languageLabel(l), selected = l == language, onClick = { if (l != language) onLanguage(l) },
                    subtitle = if (l == AppLanguage.System) stringResource(R.string.language_system_detail) else null, first = i == 0,
                )
            }
        }
    }
}

/** 外观主题微缩预览卡片选择器:跟随系统 / 浅色 / 深色。三联排布，自适应宽度。 */
@Composable
private fun ThemePickerCards(
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ThemeMode.entries.forEach { mode ->
            val selected = mode == current
            val shape = RoundedCornerShape(12.dp)
            val borderModifier = if (selected) {
                Modifier.border(1.6.dp, c.accent, shape)
            } else {
                Modifier.border(0.5.dp, c.hair, shape)
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(shape)
                    .clickable { onSelect(mode) }
                    .then(borderModifier)
                    .background(if (selected) c.accentSoft.copy(alpha = 0.08f) else c.surface)
                    .padding(horizontal = 6.dp, vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 迷你手机界面视窗预览
                ThemePreviewWindow(mode, Modifier.fillMaxWidth().height(56.dp))
                // 文字标题
                Text(
                    text = appearanceLabel(mode),
                    style = MikaType.Caption,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) c.accent else c.ink,
                )
            }
        }
    }
}

/** 迷你手机界面微缩视窗 */
@Composable
private fun ThemePreviewWindow(mode: ThemeMode, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .border(0.5.dp, MikaTheme.colors.hair, shape),
    ) {
        when (mode) {
            ThemeMode.Light -> LightPreviewWindowContent()
            ThemeMode.Dark -> DarkPreviewWindowContent()
            ThemeMode.System -> {
                // 左半浅色，右半深色
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        LightPreviewWindowContent()
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        DarkPreviewWindowContent()
                    }
                }
            }
        }
    }
}

@Composable
private fun LightPreviewWindowContent() {
    Column(
        Modifier.fillMaxSize().background(Color(0xFFF7F7F8)).padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 顶部小导航栏
        Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0xFFE5E5EA), RoundedCornerShape(2.dp)))
        // 模拟对方灰色气泡
        Box(Modifier.width(28.dp).height(8.dp).background(Color(0xFFE5E5EA), RoundedCornerShape(3.dp)))
        // 模拟己方蓝色气泡
        Box(
            Modifier.align(Alignment.End).width(32.dp).height(8.dp)
                .background(Color(0xFF0A7CFF), RoundedCornerShape(3.dp)),
        )
    }
}

@Composable
private fun DarkPreviewWindowContent() {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF161618)).padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 顶部小导航栏
        Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0xFF2C2C2E), RoundedCornerShape(2.dp)))
        // 模拟对方深灰气泡
        Box(Modifier.width(28.dp).height(8.dp).background(Color(0xFF2C2C2E), RoundedCornerShape(3.dp)))
        // 模拟己方蓝色气泡
        Box(
            Modifier.align(Alignment.End).width(32.dp).height(8.dp)
                .background(Color(0xFF0A7CFF), RoundedCornerShape(3.dp)),
        )
    }
}

/** 语言的名字:每种语言总用它自己的写法(日本語、Español…),「跟随系统」按当前语言写。 */
@Composable
fun languageLabel(l: AppLanguage): String = stringResource(
    when (l) {
        AppLanguage.System -> R.string.language_system
        AppLanguage.SimplifiedChinese -> R.string.language_simplified_chinese
        AppLanguage.TraditionalChinese -> R.string.language_traditional_chinese
        AppLanguage.English -> R.string.language_english
        AppLanguage.Japanese -> R.string.language_japanese
        AppLanguage.Korean -> R.string.language_korean
        AppLanguage.Spanish -> R.string.language_spanish
        AppLanguage.Portuguese -> R.string.language_portuguese
        AppLanguage.Russian -> R.string.language_russian
        AppLanguage.Indonesian -> R.string.language_indonesian
    },
)

// ---------- 隐私与数据 ----------

/** 设置 › 网站登录。 */
@Composable
fun WebSitesScreen(state: WebSitesState, onBack: () -> Unit) {
    PageScaffold(stringResource(R.string.settings_web_logins), onBack) { WebSitesSection(state, labeled = false) }
}

// ---------- 高级 ----------

/** 设置 › 文件与计算环境:允许脚本联网、网络代理、已用空间(按对话清理工作区)、重置运行环境。 */
@Composable
fun SandboxScreen(onBack: () -> Unit) {
    PageScaffold(stringResource(R.string.settings_sandbox), onBack) { AdvancedSettings() }
}

/** 设置 › 记住的操作路线:[count] 条,可以全部清除(先确认);[count] 为空表示还没读出来。 */
@Composable
fun RoutesScreen(count: Int?, onClear: () -> Unit, onBack: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    PageScaffold(stringResource(R.string.settings_routes), onBack) {
        GroupCard {
            ListRow(
                MikaIcons.Route, stringResource(R.string.routes_saved),
                if (count == null) stringResource(R.string.loading) else trPlural(R.plurals.routes_count, count), first = true,
            ) {
                if (count != null && count > 0) Text(
                    stringResource(R.string.routes_clear_all), Modifier.clickable { confirmClear = true }.padding(4.dp),
                    style = MikaType.Value, color = MikaTheme.colors.bad,
                )
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.routes_clear_title)) },
            text = { Text(stringResource(R.string.routes_clear_body)) },
            confirmButton = { TextButton({ confirmClear = false; onClear() }) { Text(stringResource(R.string.clear)) } },
            dismissButton = { TextButton({ confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** 设置 › 恢复与修复:手机操作的几个授权开关,下面是兼容性自检的入口。 */
@Composable
fun RecoveryScreen(onBack: () -> Unit, onOpenSelfCheck: () -> Unit = {}) {
    PageScaffold(stringResource(R.string.settings_recovery), onBack) {
        RecoverySwitches()
        GroupCard {
            ListRow(
                MikaIcons.Bug, stringResource(R.string.selfcheck_title), stringResource(R.string.selfcheck_row_sub), first = true,
                onClick = onOpenSelfCheck,
            ) { RowChevron() }
        }
    }
}

/** 外观的当前说法(设置页和这一行都用)。 */
fun appearanceLabel(mode: ThemeMode): String = tr(
    when (mode) {
        ThemeMode.System -> R.string.appearance_system
        ThemeMode.Light -> R.string.appearance_light
        ThemeMode.Dark -> R.string.appearance_dark
    },
)
