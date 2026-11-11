package com.hana.agent.cap.phone.a11y

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.hana.agent.core.uistate.VisibleTextLine
import com.hana.agent.core.uistate.normalize
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 两套内置模型均在本机运行；同一帧并行识别，完成后才释放位图。两套都出错时抛异常(不当成没字)。 */
internal object PhoneOcr {
    private const val TAG = "PhoneOcr"

    private val chinese: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val latin: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** 预热 OCR 模型与本地动态链接库，避免首个任务冷启动等待。 */
    fun warmUp() {
        try {
            val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            val img = InputImage.fromBitmap(bmp, 0)
            chinese.process(img).addOnCompleteListener {
                try { bmp.recycle() } catch (_: Exception) {}
                Log.i(TAG, "PhoneOcr warmed up")
            }
        } catch (e: Exception) {
            Log.w(TAG, "warmUp failed", e)
        }
    }

    suspend fun read(bitmap: Bitmap, recycle: Boolean = true): List<VisibleTextLine> {
        val res = withTimeoutOrNull(3500L) {
            doRead(bitmap, recycle)
        }
        if (res == null) {
            Log.w(TAG, "OCR recognition timed out after 3.5s; recycling bitmap")
            if (recycle) try { bitmap.recycle() } catch (_: Exception) {}
            return emptyList()
        }
        return res
    }

    private suspend fun doRead(bitmap: Bitmap, recycle: Boolean = true): List<VisibleTextLine> {
        val image = try { InputImage.fromBitmap(bitmap, 0) } catch (e: Exception) {
            if (recycle) try { bitmap.recycle() } catch (_: Exception) {}
            throw e
        }

        fun extractLines(result: Text): List<VisibleTextLine> {
            val lines = mutableListOf<VisibleTextLine>()
            result.textBlocks.forEach { block ->
                block.lines.forEach { line ->
                    val box = line.boundingBox ?: return@forEach
                    val value = line.text.trim()
                    if (value.isNotEmpty()) {
                        lines += VisibleTextLine(
                            value,
                            listOf(
                                normalize(box.left, bitmap.width),
                                normalize(box.top, bitmap.height),
                                normalize(box.right, bitmap.width),
                                normalize(box.bottom, bitmap.height),
                            ),
                            line.confidence.takeIf { it > 0f }
                        )
                    }
                }
            }
            return lines
        }

        return suspendCancellableCoroutine { continuation ->
            val task: Task<Text> = chinese.process(image)
            task.addOnCompleteListener { primaryDone ->
                if (primaryDone.isSuccessful && primaryDone.result != null) {
                    val lines = extractLines(primaryDone.result)
                    if (recycle) try { bitmap.recycle() } catch (_: Exception) {}
                    if (continuation.isActive) continuation.resume(lines)
                } else {
                    Log.w(TAG, "primary Chinese OCR failed, falling back to Latin", primaryDone.exception)
                    try {
                        val fallbackTask = latin.process(image)
                        fallbackTask.addOnCompleteListener { fallbackDone ->
                            if (recycle) try { bitmap.recycle() } catch (_: Exception) {}
                            if (continuation.isActive) {
                                if (fallbackDone.isSuccessful && fallbackDone.result != null) {
                                    continuation.resume(extractLines(fallbackDone.result))
                                } else {
                                    continuation.resumeWithException(IllegalStateException("text recognition failed", fallbackDone.exception))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (recycle) try { bitmap.recycle() } catch (_: Exception) {}
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        }
    }
}
