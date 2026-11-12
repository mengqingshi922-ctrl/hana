package com.hana.agent.core.vision

import com.hana.agent.core.model.Author
import com.hana.agent.core.model.Message
import com.hana.agent.core.model.Part
import com.hana.agent.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ImageBudget] 多图历史体积统计与最早截图折叠单元测试。 */
class ImageBudgetTest {

    private fun imageMsg(
        author: Author,
        payloadSize: Int,
        label: String,
        screenshotId: Int? = null,
    ): Message {
        val role = if (author == Author.User) Role.User else Role.Assistant
        val b64 = "A".repeat(payloadSize)
        return Message(
            role = role,
            parts = listOf(
                Part.Text(label),
                Part.Image(pngBase64 = b64, mime = "image/jpeg", width = 720, height = 1280, screenshotId = screenshotId),
            ),
            author = author,
        )
    }

    @Test
    fun withinBudgetReturnsOriginalListUnchanged() {
        val messages = listOf(
            imageMsg(Author.User, 100, "user-photo"),
            imageMsg(Author.Hana, 200, "step-1", screenshotId = 1),
        )
        val result = ImageBudget.enforce(
            messages = messages,
            limits = ImageBudget.Limits(maxTotalBytes = 1024L, maxImages = 4),
        )
        assertSame(messages, result.messages)
        assertEquals(0, result.foldedCount)
        assertEquals(0L, result.foldedBytes)
        assertEquals(2, result.statsAfter.imageCount)
        assertEquals(300L, result.statsAfter.totalBytes)
    }

    @Test
    fun foldsOldestAssistantScreenshotsFirstWhenOverByteLimit() {
        val messages = listOf(
            imageMsg(Author.Hana, 800, "old-shot-1", screenshotId = 1),
            imageMsg(Author.User, 500, "user-ref"),
            imageMsg(Author.Hana, 800, "old-shot-2", screenshotId = 2),
            imageMsg(Author.Hana, 800, "latest-shot-3", screenshotId = 3),
        )
        // 总字节 = 2900，限制 = 1500 => 应优先从最早的 Hana 截图折叠（old-shot-1 与 old-shot-2），保留 user-ref 和 latest-shot-3
        val result = ImageBudget.enforce(
            messages = messages,
            limits = ImageBudget.Limits(maxTotalBytes = 1500L, maxImages = 8, protectUserImages = true),
        )

        assertEquals(2, result.foldedCount)
        assertEquals(1600L, result.foldedBytes)
        assertEquals(2, result.statsAfter.imageCount)
        assertEquals(1300L, result.statsAfter.totalBytes)

        assertEquals(
            listOf(Part.Text("old-shot-1"), Part.Text(ImageBudget.COLLAPSED_IMAGE_PLACEHOLDER)),
            result.messages[0].parts,
        )
        assertTrue(result.messages[1].parts[1] is Part.Image)
        assertEquals(
            listOf(Part.Text("old-shot-2"), Part.Text(ImageBudget.COLLAPSED_IMAGE_PLACEHOLDER)),
            result.messages[2].parts,
        )
        assertTrue(result.messages[3].parts[1] is Part.Image)
    }

    @Test
    fun foldsByImageCountLimitUsingDefaultPlaceholder() {
        val messages = (1..5).map { idx ->
            imageMsg(Author.Hana, 64, "step-$idx", screenshotId = idx)
        }
        val trimmed = ImageBudget.trim(
            messages = messages,
            maxTotalBytes = ImageBudget.DEFAULT_MAX_BYTES,
            maxImages = 2,
        )

        // 最早 3 张折叠，保留最后 2 张
        for (i in 0 until 3) {
            assertEquals(Part.Text(ImageBudget.COLLAPSED_IMAGE_PLACEHOLDER), trimmed[i].parts[1])
        }
        assertTrue(trimmed[3].parts[1] is Part.Image)
        assertTrue(trimmed[4].parts[1] is Part.Image)
    }

    @Test
    fun fallsBackToUserImagesOnlyWhenProtectUserImagesIsFalse() {
        val messages = listOf(
            imageMsg(Author.User, 1000, "user-1"),
            imageMsg(Author.User, 1000, "user-2"),
            imageMsg(Author.Hana, 500, "hana-1", screenshotId = 1),
        )
        // protectUserImages = true：仅折叠 hana-1
        val protectedRes = ImageBudget.enforce(
            messages = messages,
            limits = ImageBudget.Limits(maxTotalBytes = 1200L, maxImages = 10, protectUserImages = true),
        )
        assertEquals(1, protectedRes.foldedCount)
        assertEquals(2, protectedRes.statsAfter.userImageCount)
        assertEquals(0, protectedRes.statsAfter.assistantImageCount)

        // protectUserImages = false：先折叠 hana-1（剩 2000），再折叠最早的 user-1（剩 1000 <= 1200）
        val strictRes = ImageBudget.enforce(
            messages = messages,
            limits = ImageBudget.Limits(maxTotalBytes = 1200L, maxImages = 10, protectUserImages = false),
        )
        assertEquals(2, strictRes.foldedCount)
        assertEquals(1, strictRes.statsAfter.imageCount)
        assertEquals(1000L, strictRes.statsAfter.totalBytes)
        assertEquals(Part.Text(ImageBudget.COLLAPSED_IMAGE_PLACEHOLDER), strictRes.messages[0].parts[1])
        assertTrue(strictRes.messages[1].parts[1] is Part.Image)
        assertEquals(Part.Text(ImageBudget.COLLAPSED_IMAGE_PLACEHOLDER), strictRes.messages[2].parts[1])
    }

    @Test
    fun decodedBytesHandlesBase64PaddingCorrectly() {
        assertEquals(0L, ImageBudget.decodedBytes(Part.Image("", "image/png", 1, 1)))
        assertEquals(1L, ImageBudget.decodedBytes(Part.Image("QQ==", "image/png", 1, 1)))
        assertEquals(2L, ImageBudget.decodedBytes(Part.Image("QUI=", "image/png", 1, 1)))
        assertEquals(3L, ImageBudget.decodedBytes(Part.Image("QUJD", "image/png", 1, 1)))
    }
}
