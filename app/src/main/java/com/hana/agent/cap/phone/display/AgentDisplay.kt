package com.hana.agent.cap.phone.display

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.hana.agent.shell.IHanaShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

/**
 * Agent 专用的虚拟屏。画面输出到本进程的 ImageReader,截图不依赖无障碍截图接口。
 * 帧处理限速到 ≤10 帧/秒:来帧时若离上次处理不足 100ms 就延后到期再取最新一帧,
 * 期间旧帧由 acquireLatestImage 丢弃。
 */
class AgentDisplay(
    private val shell: IHanaShell,
    val width: Int,
    val height: Int,
    dpi: Int,
) {
    private val thread = HandlerThread("agent-display").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

    val displayId: Int
    val pipInput: PipInput by lazy { DisplayPipInput(shell, this) }

    private val _frames = MutableStateFlow<Bitmap?>(null)
    /** 实时画面:限速 ≤10 帧/秒,缩到宽 ≤360。 */
    val frames: StateFlow<Bitmap?> = _frames.asStateFlow()

    private val frameLock = Any()
    private var fullFrame: Bitmap? = null // 带行填充的整帧,宽 = rowStride / pixelStride
    private var hasFrame = false
    private var lastProcessedAt = 0L
    private var pending = false
    @Volatile private var released = false

    /** 自增帧版本号与最新帧到达时间戳，供上层 O(1) 缓存亮度指纹、黑屏检测与视觉网格，避免轮询期重复像素采样。 */
    @Volatile var frameSeq: Long = 0L
        private set
    @Volatile var lastFrameUptimeMs: Long = 0L
        private set
    private var cachedSigSeq: Long = -1L
    private var cachedSignature: IntArray? = null
    private var cachedBlackSeq: Long = -1L
    private var cachedBlack: Boolean? = null
    private var cachedGridSeq: Long = -1L
    private var cachedGrid: com.hana.agent.core.visual.FrameGrid? = null
    private var previewSeq: Long = -1L

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val previewWidth = minOf(PREVIEW_MAX_WIDTH, width)
    private val previewHeight = maxOf(1, height * previewWidth / width)

    private val process = Runnable {
        pending = false
        lastProcessedAt = SystemClock.uptimeMillis()
        processLatest()
    }

    init {
        reader.setOnImageAvailableListener({ schedule() }, handler)
        val id = try {
            shell.createDisplay(reader.surface, width, height, dpi)
        } catch (t: Throwable) {
            closeLocal()
            throw t
        }
        if (id < 0) {
            closeLocal()
            throw IllegalStateException("createDisplay failed")
        }
        displayId = id
    }

    private fun schedule() {
        if (pending || released) return
        pending = true
        val wait = lastProcessedAt + FRAME_INTERVAL_MS - SystemClock.uptimeMillis()
        if (wait > 0) handler.postDelayed(process, wait) else handler.post(process)
    }

    private fun processLatest() {
        if (released) return
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val plane = image.planes[0]
            val strideWidth = plane.rowStride / plane.pixelStride
            synchronized(frameLock) {
                var bmp = fullFrame
                if (bmp == null || bmp.width != strideWidth) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(strideWidth, height, Bitmap.Config.ARGB_8888)
                    fullFrame = bmp
                }
                bmp!!.copyPixelsFromBuffer(plane.buffer.rewind())
                hasFrame = true
                frameSeq++
                lastFrameUptimeMs = SystemClock.uptimeMillis()

                // 按需抓帧瘦身：仅当存在实时预览流订阅者（如 PiP 窗/前台视图）时才生成缩略图并发射；
                // 后台静默执行期间仅维护 frameSeq 与时间戳，彻底消除每 100ms 的无效缩略图内存拷贝与 GC 抖动。
                if (_frames.subscriptionCount.value > 0) {
                    val preview = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
                    Canvas(preview).drawBitmap(bmp, Rect(0, 0, width, height), Rect(0, 0, previewWidth, previewHeight), paint)
                    previewSeq = frameSeq
                    _frames.value = preview
                } else if (_frames.value != null) {
                    previewSeq = -1L
                    _frames.value = null
                }
            }
        } finally {
            image.close()
        }
    }

    /** 最新一帧的图片(以优化后的 JPEG 格式压缩，大幅减小网络体积提升响应速度);还没收到画面时返回 null。 */
    fun latestFramePng(): ByteArray? {
        val crop = latestFrameBitmap() ?: return null
        return try {
            ByteArrayOutputStream().also { crop.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        } finally {
            crop.recycle()
        }
    }

    /** OCR 使用原始尺寸的画面副本，避免 PNG 编码和解码；调用方负责 recycle。 */
    fun latestFrameBitmap(): Bitmap? = synchronized(frameLock) {
        val bmp = fullFrame
        if (!hasFrame || bmp == null) null else Bitmap.createBitmap(bmp, 0, 0, width, height)
    }

    /** 基于 frameSeq 的 O(1) 记忆化亮度格子签名：同一帧在轮询期间零像素重算，无预览订阅时直接复用 fullFrame 采样。 */
    fun latestSignature(): IntArray? = synchronized(frameLock) {
        if (!hasFrame) return null
        if (cachedSigSeq == frameSeq && cachedSignature != null) return cachedSignature
        val previewBmp = _frames.value?.takeIf { previewSeq == frameSeq }
        val result = when {
            previewBmp != null -> runCatching { FrameMath.lumaGrid(previewBmp.width, previewBmp.height, previewBmp::getPixel) }.getOrNull()
            fullFrame != null -> {
                val full = fullFrame!!
                runCatching { FrameMath.lumaGrid(width, height, full::getPixel) }.getOrNull()
            }
            else -> null
        }
        return result?.also {
            cachedSigSeq = frameSeq
            cachedSignature = it
        }
    }

    /** 基于 frameSeq 的 O(1) 记忆化黑屏判定：同一帧不重复采样像素，无预览订阅时直接复用 fullFrame 采样。 */
    fun latestIsBlack(): Boolean? = synchronized(frameLock) {
        if (!hasFrame) return null
        if (cachedBlackSeq == frameSeq && cachedBlack != null) return cachedBlack
        val previewBmp = _frames.value?.takeIf { previewSeq == frameSeq }
        val result = when {
            previewBmp != null -> runCatching { FrameMath.isBlack(previewBmp.width, previewBmp.height, previewBmp::getPixel) }.getOrNull()
            fullFrame != null -> {
                val full = fullFrame!!
                runCatching { FrameMath.isBlack(width, height, full::getPixel) }.getOrNull()
            }
            else -> null
        }
        return result?.also {
            cachedBlackSeq = frameSeq
            cachedBlack = it
        }
    }

    /** 基于 frameSeq 的 O(1) 记忆化视觉网格：供 probe 与 withEffect 直接复用，无预览订阅时直接复用 fullFrame 采样。 */
    fun latestFrameGrid(): com.hana.agent.core.visual.FrameGrid? = synchronized(frameLock) {
        if (!hasFrame) return null
        if (cachedGridSeq == frameSeq && cachedGrid != null) return cachedGrid
        val previewBmp = _frames.value?.takeIf { previewSeq == frameSeq }
        val result = when {
            previewBmp != null -> runCatching { com.hana.agent.core.visual.FrameGrid.sample(previewBmp.width, previewBmp.height, previewBmp::getPixel) }.getOrNull()
            fullFrame != null -> {
                val full = fullFrame!!
                runCatching { com.hana.agent.core.visual.FrameGrid.sample(width, height, full::getPixel) }.getOrNull()
            }
            else -> null
        }
        return result?.also {
            cachedGridSeq = frameSeq
            cachedGrid = it
        }
    }

    fun release() {
        if (released) return
        released = true
        runCatching { shell.releaseDisplay(displayId) }
        closeLocal()
        _frames.value = null
    }

    private fun closeLocal() {
        released = true
        handler.removeCallbacksAndMessages(null)
        // 在帧线程上收尾,避免与正在处理的帧并发
        handler.post {
            reader.close()
            synchronized(frameLock) {
                fullFrame?.recycle()
                fullFrame = null
                hasFrame = false
            }
        }
        thread.quitSafely()
    }

    private companion object {
        const val PREVIEW_MAX_WIDTH = 360
        const val FRAME_INTERVAL_MS = 100L
    }
}
