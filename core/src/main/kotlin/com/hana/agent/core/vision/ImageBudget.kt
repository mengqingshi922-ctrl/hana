package com.hana.agent.core.vision

import com.hana.agent.core.model.Author
import com.hana.agent.core.model.Message
import com.hana.agent.core.model.Part

/**
 * 多图历史预算管控：在组装发给模型的消息历史时统计 [Part.Image] 的累计字节数与图片张数；
 * 超出单次请求上限（如 > 20MB 或 > N 张）时，按从早到晚的顺序将历史截图折叠为文本占位符
 * [COLLAPSED_IMAGE_PLACEHOLDER]，防止请求体过大导致网关爆栈或截断。
 */
object ImageBudget {
    /** 默认单请求图片荷载上限：20 MiB（按发给网关的 Base64 字节计）。 */
    const val DEFAULT_MAX_BYTES: Long = 20L * 1024L * 1024L

    /** 默认单请求最大图片张数。 */
    const val DEFAULT_MAX_IMAGES: Int = 8

    /** 折叠后的默认文本占位符。 */
    const val COLLAPSED_IMAGE_PLACEHOLDER: String = "[历史截图已折叠]"

    /**
     * 图片预算上限配置。
     * [protectUserImages] 为 true 时优先且仅折叠 [Author.Hana] 生成的历史截图，保留用户上传的原图；
     * 为 false 时若折叠助手截图后仍超限，会继续从最早的用户图片开始折叠以确保硬上限。
     */
    data class Limits(
        val maxTotalBytes: Long = DEFAULT_MAX_BYTES,
        val maxImages: Int = DEFAULT_MAX_IMAGES,
        val protectUserImages: Boolean = true,
    ) {
        init {
            require(maxTotalBytes >= 0L) { "maxTotalBytes must be >= 0" }
            require(maxImages >= 0) { "maxImages must be >= 0" }
        }
    }

    /** 消息历史中的图片规模统计。 */
    data class ImageStats(
        val imageCount: Int,
        val totalBytes: Long,
        val assistantImageCount: Int,
        val assistantBytes: Long,
        val userImageCount: Int,
        val userBytes: Long,
    )

    /** 预算裁剪后的结果：消息副本、前后统计与实际折叠的数量/字节。 */
    data class Result(
        val messages: List<Message>,
        val statsBefore: ImageStats,
        val statsAfter: ImageStats,
        val foldedCount: Int,
        val foldedBytes: Long,
    )

    /** 单张图片在 JSON 请求体中占用的 Base64 字节数。 */
    fun payloadBytes(image: Part.Image): Long = image.pngBase64.length.toLong()

    /** 单张图片解码后的原始二进制字节估算（扣除 Base64 尾部填充 `=`）。 */
    fun decodedBytes(image: Part.Image): Long {
        val len = image.pngBase64.length
        if (len == 0) return 0L
        var padding = 0
        if (image.pngBase64.endsWith("==")) padding = 2 else if (image.pngBase64.endsWith("=")) padding = 1
        return ((len.toLong() * 3L) / 4L - padding).coerceAtLeast(0L)
    }

    /** 统计消息列表中 [Part.Image] 的张数与累计荷载字节数。 */
    fun measure(messages: List<Message>): ImageStats {
        var totalCount = 0
        var totalBytes = 0L
        var assistantCount = 0
        var assistantBytes = 0L
        var userCount = 0
        var userBytes = 0L
        for (m in messages) {
            for (p in m.parts) {
                if (p !is Part.Image) continue
                val bytes = payloadBytes(p)
                totalCount++
                totalBytes += bytes
                if (m.author == Author.User) {
                    userCount++
                    userBytes += bytes
                } else {
                    assistantCount++
                    assistantBytes += bytes
                }
            }
        }
        return ImageStats(
            imageCount = totalCount,
            totalBytes = totalBytes,
            assistantImageCount = assistantCount,
            assistantBytes = assistantBytes,
            userImageCount = userCount,
            userBytes = userBytes,
        )
    }

    /**
     * 对消息历史执行多图预算管控，返回不可变副本与裁剪详情。
     * 未超预算时直接原样返回输入列表。
     */
    fun enforce(
        messages: List<Message>,
        limits: Limits = Limits(),
        placeholder: (Part.Image) -> String = { COLLAPSED_IMAGE_PLACEHOLDER },
    ): Result {
        val before = measure(messages)
        if (before.totalBytes <= limits.maxTotalBytes && before.imageCount <= limits.maxImages) {
            return Result(messages, before, before, foldedCount = 0, foldedBytes = 0L)
        }

        var remainingCount = before.imageCount
        var remainingBytes = before.totalBytes
        var foldedCount = 0
        var foldedBytes = 0L
        val out = messages.toMutableList()

        fun foldPass(targetUser: Boolean) {
            for (i in out.indices) {
                if (remainingBytes <= limits.maxTotalBytes && remainingCount <= limits.maxImages) break
                val msg = out[i]
                val isUser = msg.author == Author.User
                if (isUser != targetUser || msg.parts.none { it is Part.Image }) continue

                var changed = false
                val newParts = msg.parts.map { part ->
                    if (part is Part.Image && (remainingBytes > limits.maxTotalBytes || remainingCount > limits.maxImages)) {
                        val b = payloadBytes(part)
                        remainingBytes = (remainingBytes - b).coerceAtLeast(0L)
                        remainingCount = (remainingCount - 1).coerceAtLeast(0)
                        foldedCount++
                        foldedBytes += b
                        changed = true
                        Part.Text(placeholder(part))
                    } else {
                        part
                    }
                }
                if (changed) {
                    out[i] = msg.copy(parts = newParts)
                }
            }
        }

        // 第一轮：优先从最早的助手/系统历史截图开始折叠，保住用户上传原图
        foldPass(targetUser = false)
        // 第二轮：若不保护用户图片且仍超预算，再从最早的用户图片开始折叠
        if (!limits.protectUserImages && (remainingBytes > limits.maxTotalBytes || remainingCount > limits.maxImages)) {
            foldPass(targetUser = true)
        }

        if (foldedCount == 0) {
            return Result(messages, before, before, foldedCount = 0, foldedBytes = 0L)
        }
        val after = measure(out)
        return Result(out, before, after, foldedCount, foldedBytes)
    }

    /** 便捷方法：按指定上限裁剪历史消息中的超额截图，直接返回裁剪后的消息列表。 */
    fun trim(
        messages: List<Message>,
        maxTotalBytes: Long = DEFAULT_MAX_BYTES,
        maxImages: Int = DEFAULT_MAX_IMAGES,
        protectUserImages: Boolean = true,
    ): List<Message> = enforce(messages, Limits(maxTotalBytes, maxImages, protectUserImages)).messages
}
