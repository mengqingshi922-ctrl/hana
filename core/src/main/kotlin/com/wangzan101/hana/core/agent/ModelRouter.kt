package com.wangzan101.hana.core.agent

import com.wangzan101.hana.core.model.ChatModel
import com.wangzan101.hana.core.tool.Verb

/** 决定下一步用哪个模型时看的上一步情况。 */
data class StepContext(
    val step: Int,
    val lastOk: Boolean,
    val lastHadGuard: Boolean,
    /** 上一步之后画面变没变;读屏一类不报时为 null。 */
    val lastChanged: Boolean?,
    /** 回放交还、批量停止、核查退回、接管或追问恢复。 */
    val lastWasRecovery: Boolean,
    val sameAppAsLast: Boolean,
    /** 要发出的上下文里有截图;不看图的轻量模型不能接。 */
    val hasImages: Boolean = false,
)

/**
 * 实验开关 lightSteps 的分工:简单步给轻量模型,其余给主模型。
 * 轻量模型没配、不支持工具调用时恒用主模型。一步出了错误、守卫提示或画面没变,接下来 [TROUBLE_STEPS] 步强制主模型。
 */
class ModelRouter(private val main: ChatModel, private val light: ChatModel?) {
    private var forcedMain = 0
    private var disabled = false
    private var consecutiveLightSteps = 0
    private var consecutiveNoChangeSteps = 0

    /** 轻量模型能不能接手机步;不能时 lightSteps 不生效。 */
    val active: Boolean get() = !disabled && light != null && light.capabilities.toolCalls

    /** 轻量模型出了重试也没用的错(Key、额度、模型名、请求不对):余下都用主模型。一个 router 只服务一次任务。 */
    fun disable() { disabled = true }

    fun pick(ctx: StepContext): ChatModel {
        if (!ctx.lastOk || ctx.lastHadGuard || ctx.lastChanged == false) {
            noteTrouble()
        }
        if (forcedMain > 0) {
            forcedMain--
            consecutiveLightSteps = 0
            return main
        }
        val l = light?.takeIf { active } ?: return main

        // 端云分层协同：同 App 内且无异常状况时支持端侧自主推进（爆发上限 5 步，到期主动回退主模型复核）
        // 端侧具备视觉或槽位解析时，直接接管屏幕决策，大幅削减云端高昂的 3~4 秒 RTT 往返
        val isLocalLight = l.name.contains("端侧") || l.name.contains("tars") || l.name.contains("smol") || l.name.contains("0.5b")
        val canHandleVisual = !ctx.hasImages || l.capabilities.vision || isLocalLight
        val simple = ctx.step > 1 && !ctx.lastWasRecovery && ctx.sameAppAsLast && canHandleVisual

        return if (simple && consecutiveLightSteps < MAX_CONSECUTIVE_LIGHT_STEPS) {
            consecutiveLightSteps++
            l
        } else {
            consecutiveLightSteps = 0
            main
        }
    }

    /** 出了状况(含轻量模型调用失败、回复被改派给主模型、画面没变或纠错回退):之后 [TROUBLE_STEPS] 步用主模型。 */
    fun noteTrouble() {
        forcedMain = TROUBLE_STEPS
        consecutiveLightSteps = 0
    }

    fun isLight(m: ChatModel): Boolean = light != null && m === light

    companion object {
        const val TROUBLE_STEPS = 2
        /** 端侧轻量小模型最大连续自主爆发步数，到期主动回退主模型复核。 */
        const val MAX_CONSECUTIVE_LIGHT_STEPS = 8
        /** 轻量模型给出这些调用(还有 do_steps)时不执行,这一步改问主模型:放弃、确认、接管、追问、沙箱命令都由主模型决定。 */
        private val MAIN_ONLY_VERBS = setOf(Verb.EndNotDone, Verb.RequestConfirmation, Verb.HandToUser, Verb.AskUser, Verb.HandleObstacle,
            Verb.PhoneRun, Verb.Collect, Verb.PhoneFind, Verb.PhoneDialog, Verb.PhoneGesture, Verb.PhoneCommand, Verb.FindVisually) +
            // 沙箱里跑的是模型写的命令:只让主模型写
            setOf(Verb.RunCommand, Verb.RunBundledScript, Verb.ReadFile, Verb.WriteFile, Verb.SearchFiles, Verb.ShareFile)
        /** 只能由主模型给出的调用([name] 是工具名,[verb] 是它做的动作)。 */
        fun mainOnly(name: String, verb: Verb?): Boolean = name == DO_STEPS_TOOL || verb in MAIN_ONLY_VERBS

        /** 这些动作之后的一步算恢复/纠错(等过用户、回退退出、视觉定位、等待)。严禁轻量模型无脑抢权！ */
        internal val RECOVERY_VERBS = setOf(
            Verb.HandToUser, Verb.AskUser, Verb.RequestConfirmation,
            Verb.Back, Verb.FindVisually, Verb.Diagnose, Verb.Wait
        )
        /** 这些动作会换前台 App 或网页(打开网址、换标签);之后的一步不算同一个地方。 */
        internal val APP_SWITCH_VERBS = setOf(Verb.LaunchApp, Verb.OpenLink, Verb.ShareText, Verb.ShowAlarms, Verb.AddCalendarEvent, Verb.Navigate, Verb.Tabs)
    }
}

/** AgentLoop 用:记下每一步的情况,拼出下一步的 [StepContext]。 */
internal class StepTrack {
    private var ok = true
    private var guard = false
    private var changed: Boolean? = null
    private var recovery = false
    private var app: String? = null
    private var appBefore: String? = null
    private var switched = false

    fun context(step: Int, hasImages: Boolean) =
        StepContext(step, ok, guard, changed, recovery, !switched && ((app != null && app == appBefore) || (app == null && appBefore == null && step > 2)), hasImages)

    /** [app] 为 null 表示这一步的结果没报前台 App,沿用之前的。 */
    /** 刚从回放交还、批量停止这类状况里恢复:下一步按恢复步算。在 [after] 之后调用。 */
    fun markRecovery() { recovery = true }

    fun after(verb: Verb?, ok: Boolean, guard: Boolean, changed: Boolean?, recovery: Boolean, app: String?) {
        this.ok = ok
        this.guard = guard
        this.changed = changed
        this.recovery = recovery || verb in ModelRouter.RECOVERY_VERBS
        appBefore = this.app
        if (app != null) this.app = app
        switched = verb in ModelRouter.APP_SWITCH_VERBS
    }
}
