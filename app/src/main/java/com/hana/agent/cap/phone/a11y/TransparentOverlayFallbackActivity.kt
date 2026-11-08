package com.hana.agent.cap.phone.a11y

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

/**
 * 全屏透明兜底 Activity (Transparent Overlay Fallback Activity)
 *
 * 当定制 ROM（如 HyperOS / MIUI / OriginOS / Flyme）对 TYPE_ACCESSIBILITY_OVERLAY
 * 抛出 WindowManager.BadTokenException 时，作为兜底宿主通道启动，
 * 保证无障碍浮层与胶囊组件在受限环境下依然能够正常挂载存活。
 */
class TransparentOverlayFallbackActivity : ComponentActivity() {

    companion object {
        @Volatile
        var isFallbackActive: Boolean = false
            private set

        fun launch(context: Context) {
            try {
                val intent = Intent(context, TransparentOverlayFallbackActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
                context.startActivity(intent)
            } catch (e: Throwable) {
                android.util.Log.w("OverlayFallback", "Failed to launch transparent fallback activity", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isFallbackActive = true
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        )
        setContent {
            Box(Modifier.fillMaxSize())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isFallbackActive = false
    }
}
