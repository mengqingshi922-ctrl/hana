package com.hana.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.hana.agent.about.Changelog
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hana.agent.R
import com.hana.agent.about.HanaLinks
import com.hana.agent.about.QrImage
import com.hana.agent.ui.components.ButtonKind
import com.hana.agent.ui.components.GroupCard
import com.hana.agent.ui.components.ListRow
import com.hana.agent.ui.components.HanaButton
import com.hana.agent.ui.components.PageScaffold
import com.hana.agent.ui.components.RowChevron
import com.hana.agent.ui.components.RowValue
import com.hana.agent.ui.components.ValueTone
import com.hana.agent.ui.theme.HanaIcons
import com.hana.agent.ui.theme.HanaTheme
import com.hana.agent.ui.theme.HanaType

/** 关于页上点开的底部面板。 */
private enum class AboutSheet { Changelog, Feedback, Group, Diagnostics }

/**
 * 关于 Hana:顶上图标、名字、一句话介绍、版本(Beta 标记、构建编号);下面几组——更新记录(本地),
 * 联系作者与反馈,关注作者、开源仓库与使用说明;一段数据说明;最底下许可证与作者。
 * 外部链接都交给 [onOpenUrl](系统浏览器)。
 * [diagnostics] 写出诊断信息(反馈面板里「复制诊断信息」点开预览时在后台线程调)。
 */
@Composable
fun AboutScreen(
    versionName: String,
    build: String,
    beta: Boolean,
    issueUrl: String,
    onOpenUrl: (String) -> Unit,
    onSaveQr: () -> Boolean,
    onOpenLicense: () -> Unit,
    onBack: () -> Unit,
    diagnostics: () -> String = { "" },
) {
    val c = HanaTheme.colors
    val context = LocalContext.current
    var sheet by rememberSaveable { mutableStateOf<AboutSheet?>(null) }
    val saveQr = {
        Toast.makeText(context, if (onSaveQr()) AboutCopy.QR_SAVED else AboutCopy.QR_SAVE_FAILED, Toast.LENGTH_SHORT).show()
    }

    PageScaffold(AboutCopy.TITLE, onBack) {
        Hero(versionName, build, beta)

        GroupCard {
            ListRow(HanaIcons.Lines, AboutCopy.CHANGELOG, AboutCopy.CHANGELOG_SUB, first = true, onClick = { sheet = AboutSheet.Changelog }) { RowChevron() }
        }
        GroupCard {
            ListRow(HanaIcons.Code, AboutCopy.REPO, AboutCopy.REPO_SUB, first = true, onClick = { onOpenUrl(HanaLinks.REPO) }) { ExternalMark() }
            ListRow(HanaIcons.Book, AboutCopy.GUIDE, AboutCopy.GUIDE_SUB, onClick = { onOpenUrl(HanaLinks.README) }) { ExternalMark() }
            ListRow(HanaIcons.Chats, AboutCopy.GROUP, AboutCopy.GROUP_SUB, onClick = { sheet = AboutSheet.Group }) { RowChevron() }
            ListRow(HanaIcons.Bug, AboutCopy.FEEDBACK, AboutCopy.FEEDBACK_SUB, onClick = { sheet = AboutSheet.Feedback }) { RowChevron() }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp).background(c.okSoft, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(HanaIcons.Shield, null, Modifier.padding(top = 1.dp).size(18.dp), tint = c.ok)
            Text(AboutCopy.DATA_NOTE, style = HanaType.Caption, color = c.ink2)
        }

        Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                buildAnnotatedString {
                    val (before, after) = stringResource(R.string.about_released_under).split("%1\$s").let { it[0] to it.getOrElse(1) { "" } }
                    append(before)
                    withStyle(SpanStyle(color = c.accent)) { append(AboutCopy.LICENSE_LINK) }
                    append(after)
                },
                Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onOpenLicense).padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("about-license"),
                style = HanaType.Hint, color = c.ink3, textAlign = TextAlign.Center,
            )
        }
    }

    val close = { sheet = null }
    when (sheet) {
        AboutSheet.Changelog -> ChangelogSheet(onDismiss = close)
        AboutSheet.Feedback -> FeedbackSheet(
            onGitHub = { close(); onOpenUrl(issueUrl) },
            onGroup = { sheet = AboutSheet.Group },
            onDiagnostics = { sheet = AboutSheet.Diagnostics },
            onDismiss = close,
        )
        AboutSheet.Diagnostics -> DiagnosticsSheet(diagnostics, onDismiss = close)
        AboutSheet.Group -> GroupSheet(
            onSave = saveQr, onDismiss = close,
        )
        null -> Unit
    }
}

@Composable
private fun Hero(versionName: String, build: String, beta: Boolean) {
    val c = HanaTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painterResource(R.drawable.hana_launcher_art), contentDescription = null,
            Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)),
        )
        Text("Hana", Modifier.padding(top = 12.dp), style = HanaType.Title, fontWeight = FontWeight.Bold, color = c.ink)
        Text(AboutCopy.TAGLINE, Modifier.padding(top = 4.dp), style = HanaType.Caption, color = c.ink2, textAlign = TextAlign.Center)
        Row(Modifier.padding(top = 10.dp).testTag("about-version"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(AboutCopy.version(versionName), style = HanaType.Hint, color = c.ink3)
            if (beta) {
                Text(
                    AboutCopy.BETA, Modifier.background(c.accentSoft, RoundedCornerShape(7.dp)).padding(horizontal = 7.dp, vertical = 1.dp),
                    style = HanaType.Hint, fontWeight = FontWeight.SemiBold, color = c.accent,
                )
            }
            Text("· ${AboutCopy.buildLabel(build)}", style = HanaType.Hint, color = c.ink3)
        }
    }
}

/** 行尾的值:转圈、红点加文字,或一段普通的值。 */
@Composable
internal fun TrailingValue(v: AboutCopy.CheckValue) {
    if (v.spinner) {
        CircularProgressIndicator(Modifier.size(16.dp).testTag("update-spinner"), color = HanaTheme.colors.accent, strokeWidth = 2.dp)
        return
    }
    if (v.dot) UpdateDot()
    v.text?.let { RowValue(it, if (v.highlight) ValueTone.Link else ValueTone.Plain) }
}

/** 有新版本时的小红点。 */
@Composable
internal fun UpdateDot(modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(HanaTheme.colors.bad, CircleShape).testTag("update-dot"))
}

@Composable
private fun ExternalMark() {
    Icon(HanaIcons.External, null, Modifier.size(15.dp), tint = HanaTheme.colors.ink3)
}

// ---------- 底部面板 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutSheetFrame(title: String, sub: String?, tag: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = HanaTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        // 面板内容太高(矮屏、大字号)时整体能滚,底下的按钮总够得着
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp).testTag(tag),
        ) {
            Text(title, style = HanaType.Title, color = c.ink)
            sub?.let { Text(it, Modifier.padding(top = 2.dp), style = HanaType.Caption, color = c.ink3) }
            content()
        }
    }
}

@Composable
private fun ChangelogSheet(onDismiss: () -> Unit) {
    val c = HanaTheme.colors
    AboutSheetFrame(title = "Hana 更新记录", sub = "版本演进与功能改动说明", tag = "changelog-sheet", onDismiss = onDismiss) {
        val entries = Changelog.entries
        if (entries.isEmpty()) {
            Text(AboutCopy.CHANGELOG_EMPTY, Modifier.padding(top = 10.dp), style = HanaType.Caption, color = c.ink3)
            return@AboutSheetFrame
        }

        val latest = entries.first()
        val history = entries.drop(1)
        var expandedHistory by remember { mutableStateOf<Set<String>>(emptySet()) }

        Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // ----- 最新版本精美 Bento 卡片容器 -----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.fill, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 顶部版本行：版本号 + 最新版本药丸标签 + 日期
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "v${latest.version.removePrefix("v")}",
                            style = HanaType.BodyStrong,
                            color = c.ink,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(
                            modifier = Modifier
                                .background(c.okSoft, RoundedCornerShape(10.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(modifier = Modifier.size(6.dp).background(c.ok, CircleShape))
                            Text("最新版本", style = HanaType.Hint, color = c.ok, fontWeight = FontWeight.Medium)
                        }
                    }
                    Text(latest.date, style = HanaType.Hint, color = c.ink3)
                }

                if (latest.highlight.isNotEmpty()) {
                    Text(
                        latest.highlight,
                        style = HanaType.Caption,
                        color = c.ink2,
                        fontWeight = FontWeight.Medium,
                    )
                }

                // Bento 分组模块卡片
                latest.groups.forEach { group ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(c.surface, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 分组标题
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(group.icon, style = HanaType.Label)
                            Text(group.title, style = HanaType.Label, color = c.ink, fontWeight = FontWeight.SemiBold)
                        }

                        // 分组条目
                        group.items.forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                val (tagBg, tagFg) = when (item.tag) {
                                    Changelog.Tag.Feature -> c.okSoft to c.ok
                                    Changelog.Tag.Perf -> c.accentSoft to c.accent
                                    Changelog.Tag.Fix -> c.warnSoft to c.warn
                                }
                                Text(
                                    item.tag.label,
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .background(tagBg, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                    style = HanaType.Hint,
                                    fontWeight = FontWeight.Medium,
                                    color = tagFg,
                                )
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text(item.title, style = HanaType.BodyStrong.copy(fontSize = 13.5.sp), color = c.ink)
                                    if (item.detail.isNotEmpty()) {
                                        Text(item.detail, style = HanaType.Caption, color = c.ink3)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ----- 历史版本折叠收折区 -----
            if (history.isNotEmpty()) {
                Text(
                    "历史版本演进",
                    modifier = Modifier.padding(top = 4.dp, start = 2.dp),
                    style = HanaType.Label,
                    color = c.ink3,
                )

                history.forEach { entry ->
                    val isExpanded = expandedHistory.contains(entry.version)
                    val rotation by animateFloatAsState(
                        targetValue = if (isExpanded) 90f else 0f,
                        label = "history-expand-chevron",
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(c.fill, RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp)),
                    ) {
                        // 历史版本头部：点击展开/折叠
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedHistory = if (isExpanded) {
                                        expandedHistory - entry.version
                                    } else {
                                        expandedHistory + entry.version
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        "v${entry.version.removePrefix("v")}",
                                        style = HanaType.Label,
                                        color = c.ink,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(entry.date, style = HanaType.Hint, color = c.ink3)
                                }
                                if (entry.highlight.isNotEmpty()) {
                                    Text(
                                        entry.highlight,
                                        style = HanaType.Caption,
                                        color = c.ink2,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            Icon(
                                HanaIcons.Chevron,
                                contentDescription = if (isExpanded) "收起" else "展开",
                                modifier = Modifier
                                    .size(16.dp)
                                    .rotate(rotation),
                                tint = c.ink3,
                            )
                        }

                        // 展开的内容详情
                        AnimatedVisibility(
                            visible = isExpanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                HorizontalDivider(color = c.hair, thickness = 0.5.dp)
                                entry.groups.forEach { group ->
                                    group.items.forEach { item ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.Top,
                                        ) {
                                            val (tagBg, tagFg) = when (item.tag) {
                                                Changelog.Tag.Feature -> c.okSoft to c.ok
                                                Changelog.Tag.Perf -> c.accentSoft to c.accent
                                                Changelog.Tag.Fix -> c.warnSoft to c.warn
                                            }
                                            Text(
                                                item.tag.label,
                                                modifier = Modifier
                                                    .padding(top = 2.dp)
                                                    .background(tagBg, RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                                                style = HanaType.Hint,
                                                fontWeight = FontWeight.Medium,
                                                color = tagFg,
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(item.title, style = HanaType.BodyStrong.copy(fontSize = 13.sp), color = c.ink)
                                                if (item.detail.isNotEmpty()) {
                                                    Text(item.detail, style = HanaType.Caption, color = c.ink3)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedbackSheet(onGitHub: () -> Unit, onGroup: () -> Unit, onDiagnostics: () -> Unit, onDismiss: () -> Unit) {
    AboutSheetFrame(AboutCopy.FEEDBACK, AboutCopy.FEEDBACK_SHEET_SUB, "feedback-sheet", onDismiss) {
        Column(Modifier.padding(top = 6.dp)) {
            FeedbackOption(HanaIcons.Code, AboutCopy.FEEDBACK_GITHUB, AboutCopy.FEEDBACK_GITHUB_SUB, first = true, onGitHub)
            FeedbackOption(HanaIcons.Chats, AboutCopy.FEEDBACK_GROUP, AboutCopy.GROUP_SUB, first = false, onGroup)
            FeedbackOption(HanaIcons.Copy, AboutCopy.DIAGNOSTICS, AboutCopy.DIAGNOSTICS_SUB, first = false, onDiagnostics)
        }
    }
}

@Composable
private fun FeedbackOption(icon: ImageVector, title: String, sub: String, first: Boolean, onClick: () -> Unit) {
    Box(Modifier.testTag("feedback-option")) {
        ListRow(icon, title, sub, first = first, onClick = onClick)
    }
}

/**
 * 联系作者面板:作者的微信二维码(长按或按钮存进相册)。
 */
@Composable
internal fun GroupSheet(onSave: () -> Unit, onDismiss: () -> Unit) {
    val c = HanaTheme.colors
    // 二维码最高占屏幕约三分之一,常见手机上不用滚就能看到底下的按钮
    val qrHeight = (LocalConfiguration.current.screenHeightDp * 0.34f).coerceAtMost(280f).dp
    val qr = painterResource(QrImage.RES)
    AboutSheetFrame(AboutCopy.GROUP_SHEET_TITLE, AboutCopy.GROUP_SHEET_SUB, "group-sheet", onDismiss) {
        Image(
            qr, contentDescription = AboutCopy.GROUP_SHEET_TITLE,
            Modifier.padding(top = 12.dp, bottom = 6.dp).align(Alignment.CenterHorizontally)
                .height(qrHeight).aspectRatio(qr.intrinsicSize.width / qr.intrinsicSize.height)
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(Unit) { detectTapGestures(onLongPress = { onSave() }) }
                .testTag("wechat-qr"),
        )
        Text(AboutCopy.GROUP_HINT, Modifier.fillMaxWidth(), style = HanaType.Hint, color = c.ink3, textAlign = TextAlign.Center)
        HanaButton(AboutCopy.SAVE_QR, onSave, Modifier.fillMaxWidth().padding(top = 14.dp), kind = ButtonKind.Tonal)
    }
}
