package com.hana.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hana.agent.ui.theme.HanaTheme
import com.hana.agent.ui.theme.HanaType

/**
 * Hana 设计系统统一空状态组件 (HanaEmptyState):
 * 带有柔和的 accentSoft 圆形光晕徽章，居中排版，支持可选图标或小精灵展示与业务主/次动作引导。
 */
@Composable
fun HanaEmptyState(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val c = HanaTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 柔和 accentSoft 圆形光晕徽章
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(c.accentSoft, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = c.accent,
                )
            } else {
                GrokBotAvatar(size = 38.dp, isWorking = false, enableClickToggle = false)
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = title,
            style = HanaType.Title.copy(fontSize = 17.sp, lineHeight = 23.sp),
            color = c.ink,
            textAlign = TextAlign.Center,
        )

        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = HanaType.Body.copy(fontSize = 13.5.sp, lineHeight = 20.sp),
                color = c.ink3,
                textAlign = TextAlign.Center,
            )
        }

        if (!actionLabel.isNullOrBlank() && onAction != null) {
            Spacer(Modifier.height(18.dp))
            HanaButton(
                text = actionLabel,
                onClick = onAction,
                kind = ButtonKind.Tonal,
                size = ButtonSize.Medium,
            )
        }
    }
}
