package com.hana.agent.core.uistate.tools

import com.hana.agent.core.tool.ArgError
import com.hana.agent.core.tool.Args
import com.hana.agent.core.tool.Risk
import com.hana.agent.core.tool.ToolContext
import com.hana.agent.core.tool.ToolDef
import com.hana.agent.core.tool.ToolExecution
import com.hana.agent.core.tool.Verb
import com.hana.agent.core.tool.int
import com.hana.agent.core.uistate.ScreenFamily
import com.hana.agent.core.uistate.screen
import com.hana.agent.core.uistate.session
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 局部高清放大截图工具：供 VLM 指定归一化矩形框 [x1, y1, x2, y2] (0-999)，
 * 基于原始高清帧做局部裁剪与二次精定位。
 */
internal class ZoomRegionTool(f: ScreenFamily) : ScreenTool(f, "zoom_region") {
    override val verb = Verb.Screenshot
    override val label = "局部放大截图"
    override val risk = Risk.Read
    override val yieldsToSteer = true

    override fun usable(ctx: ToolContext): Boolean =
        ctx.config.visionEnabled && ctx.config.imageOps != null

    override val def: ToolDef by lazy {
        ToolDef(
            name = name,
            description = describe(),
            props = mapOf(
                "x1" to int("Left edge of the crop box (normalized 0-999, or 0-1 / pixel coordinate).", 0, 999),
                "y1" to int("Top edge of the crop box (normalized 0-999, or 0-1 / pixel coordinate).", 0, 999),
                "x2" to int("Right edge of the crop box (normalized 0-999, or 0-1 / pixel coordinate).", 0, 999),
                "y2" to int("Bottom edge of the crop box (normalized 0-999, or 0-1 / pixel coordinate).", 0, 999),
                "screenshotId" to int(
                    "Optional screenshotId from an earlier read to crop from; omit to capture or reuse the current screen.",
                    1,
                    Int.MAX_VALUE,
                ),
            ),
            required = listOf("x1", "y1", "x2", "y2"),
        )
    }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val x1 = a.coord("x1") ?: throw ArgError("x1", "required")
        val y1 = a.coord("y1") ?: throw ArgError("y1", "required")
        val x2 = a.coord("x2") ?: throw ArgError("x2", "required")
        val y2 = a.coord("y2") ?: throw ArgError("y2", "required")
        if (x2 <= x1 || y2 <= y1) {
            throw ArgError("x2", "crop box must have x2 > x1 and y2 > y1 (got [$x1, $y1, $x2, $y2])")
        }

        val screenshotId = a.int("screenshotId", 1, Int.MAX_VALUE)

        val ops = ctx.config.imageOps
            ?: return errorResult("image_ops_unavailable", "图像裁剪能力不可用。")

        // 仅当裁剪当前屏幕时校验受保护页面；若引用已召回的历史截图则直接按 ID 检索
        if (screenshotId == null) {
            val isProtected = if (ctx.session.isFrozen) {
                ctx.session.lastSnapshot?.protected == true
            } else {
                ctx.screen.isProtected()
            }
            if (isProtected) {
                return ToolExecution(
                    content = buildJsonObject {
                        put("ok", false)
                        put("error", "protected_content")
                        put("protected", true)
                        put("message", "当前界面受系统隐私保护，禁止截取局部画面。")
                        put("recoverable", true)
                    }
                )
            }
        }

        val sourcePng = if (screenshotId != null) {
            ctx.shots.recalls.firstOrNull { it.id == screenshotId }?.png
                ?: return errorResult(
                    "screenshot_not_found",
                    "未找到编号为 #$screenshotId 的历史截图，或该截图已过期。"
                )
        } else {
            ctx.session.screenshotPngCached()
                ?: return errorResult("screenshot_unavailable", "当前无法获取屏幕截图。")
        }

        val cropped = runCatching { ops.crop(sourcePng, intArrayOf(x1, y1, x2, y2)) }
            .getOrElse { err ->
                return errorResult("crop_failed", "局部裁剪失败：${err.message ?: err::class.simpleName}")
            }

        val sourceSize = runCatching { ops.size(sourcePng) }.getOrNull()
        val croppedSize = runCatching { ops.size(cropped) }.getOrNull()

        return ToolExecution(
            content = buildJsonObject {
                put("ok", true)
                put("x1", x1)
                put("y1", y1)
                put("x2", x2)
                put("y2", y2)
                if (screenshotId != null) {
                    put("sourceScreenshotId", screenshotId)
                }
                if (ctx.session.isFrozen && screenshotId == null) {
                    put("frozen", true)
                }
                if (sourceSize != null) {
                    put("sourceWidth", sourceSize.first)
                    put("sourceHeight", sourceSize.second)
                }
                if (croppedSize != null) {
                    put("croppedWidth", croppedSize.first)
                    put("croppedHeight", croppedSize.second)
                }
            },
            image = cropped,
        )
    }

    private fun errorResult(code: String, message: String): ToolExecution = ToolExecution(
        content = buildJsonObject {
            put("ok", false)
            put("error", code)
            put("message", message)
            put("recoverable", true)
        }
    )
}
