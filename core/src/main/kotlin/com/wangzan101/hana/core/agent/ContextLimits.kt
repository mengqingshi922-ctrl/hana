package com.wangzan101.hana.core.agent

/**
 * 一个模型上下文窗口上的几条线。全部按**整份请求**计(系统提示词 + 工具定义 + 消息,和服务商报的输入 token 同一口径),
 * 固定开销不在这里扣,由量的那一边([UsageMeter])算进去,只算一次。触发点只在 [of] 一处算。
 *
 * - [usable]:窗口扣掉给输出的预留(最多 [MAX_OUTPUT_RESERVE],小窗口按窗口的四分之一)。
 * - [margin]:触发点离 [usable] 的余量(最多 [MARGIN_TOKENS],小窗口按 [usable] 的四分之一);也是压缩后的回差,
 *   压完至少再长这么多才会再压,不会每步都压。
 * - [trigger]:当前请求超过它就压缩一次。不低于窗口的 [MIN_TRIGGER_SHARE],不高于 [usable]。
 * - [target]:一次压缩要把整份请求压到它以下([usable] 的 [AFTER_COMPACTION],且至少比触发点低一个 [margin])。
 */
class ContextLimits private constructor(
    val window: Int, val usable: Int, val trigger: Int, val margin: Int, val target: Int,
    val minTrigger: Int,
) {
    companion object {
        /** 给模型输出预留的 token 上限。 */
        const val MAX_OUTPUT_RESERVE = 20_000
        /** 触发点离可用窗口留的余量上限。 */
        const val MARGIN_TOKENS = 13_000
        /** 一次压缩要压到可用窗口的这个比例以下。 */
        const val AFTER_COMPACTION = 0.5
        /** 触发点的下限(占窗口的比例):没显式指定时，不会低到一开局就压。 */
        const val MIN_TRIGGER_SHARE = 0.5

        /**
         * 显式指定触发点时用的绝对下限。系统提示词加工具本身就有 2 万多 token，
         * 再低就等于每步都压。按窗口比例算(128K 窗口是 64K)会把"想压到 28K"这件事吃掉，
         * 而模型池取两通道最小窗口后这种情况很常见。
         */
        const val MIN_OVERRIDE_TRIGGER = 32_000

        /**
         * [window]:模型上下文长度。[triggerOverride]:按模型单独给的触发点(整份请求的 token);限在下限和 [usable] 之间。
         */
        fun of(window: Int, triggerOverride: Int? = null, allowLowOverride: Boolean = false): ContextLimits {
            val w = window.coerceAtLeast(0)
            val usable = w - minOf(MAX_OUTPUT_RESERVE, w / 4)
            val margin = minOf(MARGIN_TOKENS, usable / 4)
            val floor = minOf((w * MIN_TRIGGER_SHARE).toInt(), usable)
            // 显式触发点的下限取"窗口比例下限"和"绝对下限"里更小的那个:
            // 大窗口(128K 的下限是 64K)才允许压到 32K;小窗口仍按比例，免得每步都压
            // 手填的设置仍按窗口比例夹（免得在设置里填个 1K 每步都压）；
            // 按模型预设的触发点可以更低，只受绝对下限约束
            val overrideFloor = if (allowLowOverride) minOf(floor, MIN_OVERRIDE_TRIGGER, usable) else floor
            val trigger = if (triggerOverride != null) {
                triggerOverride.coerceIn(overrideFloor, usable)
            } else {
                (usable - margin).coerceIn(floor, usable)
            }
            val target = minOf((usable * AFTER_COMPACTION).toInt(), trigger - margin).coerceAtLeast(0)
            return ContextLimits(w, usable, trigger, margin, target, floor)
        }
    }
}
