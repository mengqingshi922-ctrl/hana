package com.hana.agent.privacy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.hana.agent.cap.phone.a11y.PhoneOcr
import com.hana.agent.core.security.HybridSanitizeResult
import com.hana.agent.core.security.MaskStyle
import com.hana.agent.core.security.RedactionRegion
import com.hana.agent.core.security.SensitiveCategory
import com.hana.agent.core.uistate.UiElement
import com.hana.agent.core.uistate.VisibleTextLine
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.hana.agent.core.security.HybridPrivacyFirewall as CoreFirewall

/**
 * Android Bitmap 局部打码输出结果。
 * 调用方需在使用完毕后按需回收 [bitmap]。
 */
data class BitmapRedactionResult(
    val bitmap: Bitmap,
    val regions: List<RedactionRegion>,
    val modified: Boolean,
)

/**
 * 截图送往云端 VLM 前的本地打码与编码结果。
 */
data class EncodedRedactedScreenshot(
    val bytes: ByteArray,
    val regions: List<RedactionRegion>,
    val redactedCategories: Set<SensitiveCategory>,
    val wasRedacted: Boolean,
)

/**
 * 端侧混合隐私防火墙 Android 门面 (Hybrid Privacy Firewall)
 *
 * 整合文本通道与视觉通道：
 * 1. 文本脱敏：委托 [CoreFirewall] 完成「正则 + 上下文敏感实体识别 + RegisterBus 寄存」；
 * 2. 视觉本地坐标打码：在截图上传云端 VLM 之前，结合本机 OCR 识别框列表 ([VisibleTextLine]) 与
 *    无障碍密码控件 ([UiElement]) 对密码、验证码、银行卡、身份证等区域执行像素级马赛克模糊或深色遮罩，
 *    并在端侧完成缩放与 JPEG/PNG 编码输出。
 */
object HybridPrivacyFirewall {
    private const val DEFAULT_BLOCK_SIZE_PX = 16
    // Color.rgb(17, 24, 39) = #111827, Color.rgb(245, 158, 11) = #F59E0B
    // 避免在 object 初始化时调用 android.graphics.Color，使用硬编码 ARGB Int 值
    private val DEFAULT_MASK_COLOR = (0xFF shl 24) or (17 shl 16) or (24 shl 8) or 39
    private val DEFAULT_BADGE_TEXT_COLOR = (0xFF shl 24) or (245 shl 16) or (158 shl 8) or 11

    fun isSensitivePackage(packageName: String?): Boolean =
        CoreFirewall.isSensitivePackage(packageName)

    fun sanitizeText(rawText: String, packageName: String? = null, force: Boolean = false): HybridSanitizeResult =
        CoreFirewall.sanitizeText(rawText, packageName, force)

    fun sanitizeScreenJson(jsonString: String, packageName: String? = null, force: Boolean = false): String =
        CoreFirewall.sanitizeScreenJson(jsonString, packageName, force)

    fun planVisualRedactions(
        ocrLines: List<VisibleTextLine>,
        uiElements: List<UiElement> = emptyList(),
        packageName: String? = null,
        force: Boolean = false,
    ): List<RedactionRegion> = CoreFirewall.planVisualRedactions(ocrLines, uiElements, packageName, force)

    /**
     * 对纯像素缓冲区执行打码（用于离屏渲染管线及无 Android Bitmap 环境的单元测试验证）。
     */
    fun redactPixelBuffer(
        pixels: IntArray,
        width: Int,
        height: Int,
        ocrLines: List<VisibleTextLine>,
        uiElements: List<UiElement> = emptyList(),
        packageName: String? = null,
        force: Boolean = false,
        blockSize: Int = DEFAULT_BLOCK_SIZE_PX,
    ): Pair<IntArray, List<RedactionRegion>> {
        val regions = planVisualRedactions(ocrLines, uiElements, packageName, force)
        if (regions.isEmpty()) return pixels to emptyList()
        val masked = CoreFirewall.redactPixels(
            pixels = pixels,
            width = width,
            height = height,
            regions = regions,
            blockSize = blockSize,
            maskColor = DEFAULT_MASK_COLOR,
        )
        return masked to regions
    }

    /**
     * 给定 OCR 识别框列表对 [source] Bitmap 中的密码/验证码/银行卡等敏感区域执行本地像素模糊/遮罩。
     * 若未命中敏感区域，返回原图的可变安全副本或原图引用（[modified] 为 false）。
     */
    fun redactBitmap(
        source: Bitmap,
        ocrLines: List<VisibleTextLine>,
        uiElements: List<UiElement> = emptyList(),
        packageName: String? = null,
        force: Boolean = false,
        blockSize: Int = DEFAULT_BLOCK_SIZE_PX,
    ): BitmapRedactionResult {
        val regions = planVisualRedactions(ocrLines, uiElements, packageName, force)
        if (regions.isEmpty() || source.isRecycled || source.width <= 0 || source.height <= 0) {
            return BitmapRedactionResult(source, emptyList(), modified = false)
        }

        val w = source.width
        val h = source.height
        val mutable = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(w * h)
        mutable.getPixels(pixels, 0, w, 0, 0, w, h)

        val redactedPixels = CoreFirewall.redactPixels(
            pixels = pixels,
            width = w,
            height = h,
            regions = regions,
            blockSize = blockSize,
            maskColor = DEFAULT_MASK_COLOR,
        )
        mutable.setPixels(redactedPixels, 0, w, 0, 0, w, h)

        // 为遮罩框绘制轻微语义边框与安全占位提示，帮助云端 VLM 理解该区域已被端侧隐私脱敏保护
        val canvas = Canvas(mutable)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2f, min(w, h) * 0.003f)
            color = DEFAULT_BADGE_TEXT_COLOR
        }
        for (region in regions) {
            val l = (region.bounds[0] * (w - 1) / 999f)
            val t = (region.bounds[1] * (h - 1) / 999f)
            val r = (region.bounds[2] * (w - 1) / 999f)
            val b = (region.bounds[3] * (h - 1) / 999f)
            if (r > l && b > t) {
                canvas.drawRoundRect(RectF(l, t, r, b), 6f, 6f, borderPaint)
            }
        }

        return BitmapRedactionResult(mutable, regions, modified = true)
    }

    /**
     * 发送云端 VLM 前的一体化本地打码 + 缩放 + 编码接口。
     * 先按 OCR 坐标框打码，再缩放到长边不超过 [maxSide] 并压缩为 [format] 字节流；不会回收调用方传入的 [source]。
     */
    fun redactAndEncode(
        source: Bitmap,
        ocrLines: List<VisibleTextLine>,
        uiElements: List<UiElement> = emptyList(),
        packageName: String? = null,
        force: Boolean = false,
        maxSide: Int = 1280,
        quality: Int = 80,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        blockSize: Int = DEFAULT_BLOCK_SIZE_PX,
    ): EncodedRedactedScreenshot {
        val redacted = redactBitmap(source, ocrLines, uiElements, packageName, force, blockSize)
        val working = redacted.bitmap
        var scaled: Bitmap? = null
        try {
            val target = if (maxSide > 0 && max(working.width, working.height) > maxSide) {
                val factor = maxSide.toDouble() / max(working.width, working.height)
                val sw = max(1, (working.width * factor).roundToInt())
                val sh = max(1, (working.height * factor).roundToInt())
                Bitmap.createScaledBitmap(working, sw, sh, true).also { scaled = it }
            } else {
                working
            }
            val bytes = ByteArrayOutputStream().use { out ->
                target.compress(format, quality.coerceIn(1, 100), out)
                out.toByteArray()
            }
            return EncodedRedactedScreenshot(
                bytes = bytes,
                regions = redacted.regions,
                redactedCategories = redacted.regions.mapTo(LinkedHashSet()) { it.category },
                wasRedacted = redacted.modified,
            )
        } finally {
            if (scaled != null && scaled !== working && scaled !== source) {
                scaled?.recycle()
            }
            if (working !== source && !working.isRecycled) {
                working.recycle()
            }
        }
    }

    /**
     * 完整闭环便捷方法：自动触发本机 [PhoneOcr] 提取截图文字框，执行坐标打码后再编码上传云端 VLM。
     * OCR 异常时安全降级为空识别列表（仍保留无障碍 `secret` 密码框遮罩），不中断主流程。
     */
    suspend fun scanWithOcrAndEncode(
        source: Bitmap,
        uiElements: List<UiElement> = emptyList(),
        packageName: String? = null,
        force: Boolean = false,
        maxSide: Int = 1280,
        quality: Int = 80,
        ocrReader: suspend (Bitmap) -> List<VisibleTextLine> = { bmp ->
            runCatching { PhoneOcr.read(bmp, recycle = false) }.getOrElse { emptyList() }
        },
    ): EncodedRedactedScreenshot {
        val lines = runCatching { ocrReader(source) }.getOrElse { emptyList() }
        return redactAndEncode(
            source = source,
            ocrLines = lines,
            uiElements = uiElements,
            packageName = packageName,
            force = force,
            maxSide = maxSide,
            quality = quality,
        )
    }
}
