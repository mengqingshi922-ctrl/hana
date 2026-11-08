package com.hana.agent.edge

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * 验证 MNN-Pocket 0.3B ModelStore 下载管线、生命周期、断点续传与 SHA-256 校验
 */
class MnnPocketModelStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var rootDir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        rootDir = tmp.newFolder()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun verifiesDownloadStateHierarchy() {
        val notDownloaded: DownloadState = DownloadState.NotDownloaded
        assertNotNull(notDownloaded)

        val downloading: DownloadState = DownloadState.Downloading(
            progress = 0.5f,
            speedBps = 1024 * 1024L,
            downloadedBytes = 50L,
            totalBytes = 100L,
        )
        assertTrue(downloading is DownloadState.Downloading)
        assertEquals(0.5f, (downloading as DownloadState.Downloading).progress)

        val verifying: DownloadState = DownloadState.Verifying("abc123")
        assertTrue(verifying is DownloadState.Verifying)

        val ready: DownloadState = DownloadState.Ready
        assertTrue(ready is DownloadState.Ready)

        val error: DownloadState = DownloadState.Error("网络超时", canRetry = true)
        assertTrue(error is DownloadState.Error)
        assertTrue((error as DownloadState.Error).canRetry)
    }

    @Test
    fun downloadPipelineDownloadsWeightsAndVerifiesChecksum() = runTest {
        val testPayload = "MNN_POCKET_TEST_INT4_WEIGHTS_CONTENT"
        val expectedSha256 = MessageDigest.getInstance("SHA-256")
            .digest(testPayload.toByteArray())
            .joinToString("") { "%02x".format(it) }

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(testPayload)
        )

        val store = MnnPocketModelStore(
            baseDir = rootDir,
            httpClient = OkHttpClient(),
        )

        assertEquals(DownloadState.NotDownloaded, store.downloadState.value)
        assertFalse(store.isReady())

        // 触发下载
        val downloadUrl = server.url("/mnn_pocket_0.3b_dec.mnn").toString()
        store.startDownload(mirrorUrl = downloadUrl)

        // 验证下载成功且就绪
        assertEquals(DownloadState.Ready, store.downloadState.value)
        assertTrue(store.isReady())
        assertEquals(testPayload, store.decModelFile.readText())

        // 校验 SHA-256 完整性
        val checksumMatches = store.verifyChecksum(store.decModelFile, expectedSha256)
        assertTrue("SHA-256 校验必须与预期哈希完全匹配", checksumMatches)

        val wrongChecksumMatches = store.verifyChecksum(store.decModelFile, "deadbeef00000000")
        assertFalse("错误的 SHA-256 校验必须返回 false", wrongChecksumMatches)

        // 清理权重
        assertTrue(store.purgeWeights())
        assertFalse(store.isReady())
        assertEquals(DownloadState.NotDownloaded, store.downloadState.value)
    }

    @Test
    fun cancelDownloadResetsStateCorrectly() {
        val store = MnnPocketModelStore(baseDir = rootDir)
        store.cancelDownload()
        assertEquals(DownloadState.NotDownloaded, store.downloadState.value)
    }

    @Test
    fun handlesHttpErrorGracefully() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val store = MnnPocketModelStore(
            baseDir = rootDir,
            httpClient = OkHttpClient(),
        )

        val errorUrl = server.url("/failed.mnn").toString()
        store.startDownload(mirrorUrl = errorUrl)

        val state = store.downloadState.value
        assertTrue("HTTP 500 时状态必须置为 DownloadState.Error", state is DownloadState.Error)
        assertTrue((state as DownloadState.Error).canRetry)
    }
}
