package com.hana.agent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hana.agent.edge.DownloadState
import com.hana.agent.edge.MnnPocketEngine
import com.hana.agent.edge.MnnPocketModelStore
import com.hana.agent.settings.FeatureFlags
import com.hana.agent.ui.theme.MikaIcons
import com.hana.agent.ui.theme.MikaShapes
import com.hana.agent.ui.theme.MikaTheme
import com.hana.agent.ui.theme.MikaType
import kotlinx.coroutines.launch

/**
 * 阿里 MNN-Pocket-0.3B 端侧神经核流体化 Bento 控制台卡片：
 * 集成下载管线生命周期管理、断点续传进度条、Canvas 神经反射波形与时序瀑布流。
 */
@Composable
fun MnnPocketBentoCard(
    modifier: Modifier = Modifier,
    engine: MnnPocketEngine? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = MikaTheme.colors
    val shape = RoundedCornerShape(14.dp)
    val featureFlags = remember { FeatureFlags.get(context) }
    var enabled by remember { mutableStateOf(featureFlags.edgePocketEnabled()) }

    val activeEngine = remember(engine) { engine ?: MnnPocketEngine.getInstance(context) }
    val store = activeEngine.store
    val downloadState by store.downloadState.collectAsState()
    val isReady = store.isReady()
    val isNativeLoaded = MnnPocketEngine.isNativeLoaded
    val isEngineReady = isReady && isNativeLoaded && enabled

    Column(
        modifier
            .testTag("mnn-pocket-bento-card")
            .fillMaxWidth()
            .padding(top = 10.dp)
            .cardSurface(c, shape)
            .border(0.5.dp, c.edge, shape)
            .clip(shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 第一行：左侧[图标 + 标题组] + 右侧[拨动开关]
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f, fill = false),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .background(if (enabled) c.accentSoft else c.fill, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        MikaIcons.Spark,
                        null,
                        Modifier.size(20.dp),
                        tint = if (enabled) c.accent else c.ink3,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "端侧神经核",
                            style = MikaType.BodyStrong.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                            color = c.ink,
                        )
                        val pillStatus = when {
                            !enabled -> "已停用" to PillTone.Neutral
                            downloadState is DownloadState.Downloading -> "下载中" to PillTone.Accent
                            downloadState is DownloadState.Verifying -> "校验中" to PillTone.Accent
                            isReady && !isNativeLoaded -> "规则兜底" to PillTone.Neutral
                            isReady && isNativeLoaded -> "协同中" to PillTone.Ok
                            downloadState is DownloadState.Error -> "异常" to PillTone.Warn
                            else -> "未下载" to PillTone.Neutral
                        }
                        StatusPill(pillStatus.first, pillStatus.second)
                    }
                    Text(
                        if (isNativeLoaded) "阿里 MNN-Pocket · 173MB INT4 QAT"
                        else "纯规则兜底模式 · 原生 MNN 库未载入",
                        style = MikaType.Caption.copy(fontSize = 11.5.sp),
                        color = c.ink3,
                    )
                }
            }

            MikaSwitch(
                checked = enabled,
                onCheckedChange = { checked ->
                    enabled = checked
                    featureFlags.setEdgePocketEnabled(checked)
                },
            )
        }

        // 第二行：模型下载管线与磁盘管理交互区
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(c.fill)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (val state = downloadState) {
                is DownloadState.NotDownloaded -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "端侧权重未下载 (约173MB)",
                            style = MikaType.Caption.copy(fontSize = 12.sp),
                            color = c.ink2,
                        )
                        Box(
                            Modifier
                                .clip(MikaShapes.Pill)
                                .background(c.accentSoft)
                                .clickable { scope.launch { store.startDownload() } }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                .testTag("btn-download-weights"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "一键拉取",
                                style = MikaType.Caption.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                                color = c.accent,
                            )
                        }
                    }
                }
                is DownloadState.Downloading -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "正在下载 ${(state.progress * 100).toInt()}% · ${(state.speedBps / 1024)} KB/s",
                            style = MikaType.Caption.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
                            color = c.accent,
                        )
                        Box(
                            Modifier
                                 .clip(MikaShapes.Pill)
                                 .background(c.surface)
                                 .clickable { store.cancelDownload() }
                                 .padding(horizontal = 8.dp, vertical = 3.dp)
                                 .testTag("btn-cancel-download"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("取消", style = MikaType.Caption.copy(fontSize = 11.sp), color = c.ink3)
                        }
                    }
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(MikaShapes.Pill),
                        color = c.accent,
                        trackColor = c.edge,
                    )
                }
                is DownloadState.Verifying -> {
                    Text(
                        "正在校验 SHA-256 完整性...",
                        style = MikaType.Caption.copy(fontSize = 11.5.sp),
                        color = c.accent,
                    )
                }
                is DownloadState.Ready -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "神经核就绪 · 本地占用 ${(store.getDiskUsageBytes() / (1024 * 1024))}MB",
                            style = MikaType.Caption.copy(fontSize = 11.5.sp),
                            color = c.ok,
                        )
                        Box(
                            Modifier
                                 .clip(MikaShapes.Pill)
                                 .background(c.surface)
                                 .clickable { store.purgeWeights() }
                                 .padding(horizontal = 8.dp, vertical = 3.dp)
                                 .testTag("btn-purge-weights"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("清除权重", style = MikaType.Caption.copy(fontSize = 11.sp), color = c.ink3)
                        }
                    }
                }
                is DownloadState.Error -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "下载失败: ${state.message}",
                            style = MikaType.Caption.copy(fontSize = 11.5.sp),
                            color = c.warn,
                            modifier = Modifier.weight(1f, fill = false),
                            maxLines = 1,
                        )
                        Box(
                            Modifier
                                .clip(MikaShapes.Pill)
                                .background(c.accentSoft)
                                .clickable { scope.launch { store.startDownload() } }
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                .testTag("btn-retry-download"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("重试", style = MikaType.Caption.copy(fontSize = 11.sp), color = c.accent)
                        }
                    }
                }
            }
        }

        // 第三行：神经核反射脉冲指示器
        EdgePulseChart(
            historyLatenciesMs = if (isEngineReady) emptyList() else listOf(0.8f, 0.9f, 0.7f, 1.0f, 0.8f),
            isReady = isEngineReady,
            isNativeLoaded = isNativeLoaded,
            modifier = Modifier.fillMaxWidth(),
        )

        // 第四行：时序瀑布流预览 (Perceive -> Edge Filter -> Cloud TTFT -> Inject)
        val stages = if (isEngineReady) {
            listOf(
                TelemetryStage("Perceive 感知", 35L, 0xFF3B82F6),
                TelemetryStage("Edge Filter 神经反射", 12L, 0xFF10B981),
                TelemetryStage("Cloud TTFT 云端", 160L, 0xFFF59E0B),
                TelemetryStage("Inject 动作注入", 45L, 0xFF8B5CF6),
            )
        } else {
            listOf(
                TelemetryStage("Perceive 感知", 35L, 0xFF3B82F6),
                TelemetryStage("Rule Filter 规则兜底", 1L, 0xFF6B7280),
                TelemetryStage("Cloud TTFT 云端", 160L, 0xFFF59E0B),
                TelemetryStage("Inject 动作注入", 45L, 0xFF8B5CF6),
            )
        }
        TelemetryWaterfall(
            stages = stages,
            modifier = Modifier.fillMaxWidth(),
        )

        // 底部说明文案
        Text(
            if (enabled) {
                if (isEngineReady) "端侧动作反射神经核已激活，12ms 极速开局判定与弹窗消解，敏感数据零出端。"
                else if (isReady && !isNativeLoaded) "MNN 原生库未加载，已自动切换至高精度纯规则引擎兜底模式（时延 < 1ms）。"
                else "端侧模型未就绪，系统已自动无感降级至高精度纯规则兜底或云端全量推理。"
            } else {
                "端侧核已停用，意图与弹窗判断全量交由云端主模型（可能略微增加网络耗时）。"
            },
            style = MikaType.Caption.copy(fontSize = 11.sp),
            color = if (enabled) c.ink3 else c.warn,
            lineHeight = 15.sp,
        )
    }
}
