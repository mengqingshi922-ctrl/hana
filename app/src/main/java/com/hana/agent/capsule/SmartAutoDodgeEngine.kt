package com.hana.agent.capsule

import android.graphics.Rect
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 智能视口自动避让引擎 (Smart Auto-Dodge Engine)
 *
 * 核心机制：
 * 1. 碰撞检测 (Collision Detection)：在自动化驱动注入手势前，检测目标交互点/区域是否与悬浮窗重叠；
 * 2. 动态穿透 (Instant Touchable Disable)：一旦产生碰撞，T=0ms 瞬间剥离 FLAG_NOT_TOUCHABLE 属性（setTouchable(false)），杜绝手势被浮层拦截；
 * 3. 阻尼滑移 (Damped Dodge Motion)：以 150ms 阻尼向屏幕对侧停靠边平滑滑移；
 * 4. 栅栏互锁 (Barrier Pacing Interlock)：手势执行线程挂起等待 awaitDodgeSettled(150ms)，并在动画落定后恢复 touchable。
 */
object SmartAutoDodgeEngine {

    interface DodgeClient {
        /** 获取悬浮窗当前在屏幕中的绝对边界矩形（若当前隐藏或不可见返回 null 或空矩形） */
        fun getOverlayBounds(): Rect?

        /** 避让至屏幕另一侧，完成阻尼弹簧位移动效 */
        suspend fun dodgeToOppositeSide(durationMs: Long = 150L): Boolean

        /** 动态切换窗口的触摸穿透属性：false 时追加 FLAG_NOT_TOUCHABLE */
        fun setTouchable(touchable: Boolean)
    }

    private val clients = CopyOnWriteArraySet<DodgeClient>()

    fun registerClient(client: DodgeClient) {
        clients.add(client)
    }

    fun unregisterClient(client: DodgeClient) {
        clients.remove(client)
    }

    fun getRegisteredClients(): Set<DodgeClient> = clients

    /** 检测目标交互区域与悬浮窗视口是否发生空间相交重叠 */
    fun checkCollision(targetRect: Rect, overlayBounds: Rect?): Boolean {
        if (overlayBounds == null || overlayBounds.isEmpty) return false
        return Rect.intersects(targetRect, overlayBounds)
    }

    /**
     * 智能手势注入前通知与栅栏互锁：
     * 若目标区域与任何注册的浮窗产生碰撞，立即禁用浮窗触摸，触发对侧避让，并等待避让落定。
     * 返回是否发生了碰撞避让。
     */
    suspend fun notifyTargetAndAwaitDodge(targetRect: Rect, timeoutMs: Long = 180L): Boolean {
        var collided = false
        for (client in clients) {
            val bounds = client.getOverlayBounds()
            if (checkCollision(targetRect, bounds)) {
                collided = true
                // T=0ms 瞬时关闭 touchable，避免手势被浮窗自身截获
                client.setTouchable(false)
                withTimeoutOrNull(timeoutMs) {
                    client.dodgeToOppositeSide(150L)
                }
                client.setTouchable(true)
            }
        }
        return collided
    }

    /** 等待避让动效完全落定 */
    suspend fun awaitDodgeSettled(durationMs: Long = 150L) {
        delay(durationMs)
    }
}
