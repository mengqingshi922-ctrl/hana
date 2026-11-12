package com.hana.agent.memory

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.hana.agent.core.memory.ConsolidationReport
import com.hana.agent.core.memory.MarkdownMemoryFile
import com.hana.agent.core.memory.MarkdownMemoryProjection
import com.hana.agent.core.memory.MarkdownSyncResult
import com.hana.agent.core.memory.MemoryStore
import com.hana.agent.core.memory.RoutineSuggestion
import com.hana.agent.core.memory.SleepConsolidator
import com.hana.agent.core.memory.TaskMemoryStore
import com.hana.agent.core.memory.TemporalFactGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.ZoneId

/**
 * 睡眠期记忆整合与习惯挖掘后台调度器 (`SleepConsolidationWorker`)。
 *
 * 在设备空闲或后台周期任务触发时执行：
 * 1. 对 [MemoryStore] 执行近似记忆去重合并与 Ebbinghaus 保留度整理；
 * 2. 基于 [TaskMemoryStore] 历史足迹挖掘固定 App 序列习惯并生成 [RoutineSuggestion]；
 * 3. 更新人类可读可编辑的 `MEMORY.md` 投影视图，并持久化最新 Routine 建议列表。
 */
class SleepConsolidationWorker(
    private val memoryStore: MemoryStore,
    private val taskMemoryStore: TaskMemoryStore?,
    private val factGraph: TemporalFactGraph? = null,
    private val markdownFile: MarkdownMemoryFile,
    private val routinesFile: File,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val minRoutineOccurrences: Int = 2,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val _routines = MutableStateFlow(loadSavedRoutines())
    val routines: StateFlow<List<RoutineSuggestion>> = _routines.asStateFlow()

    private val _lastReport = MutableStateFlow<ConsolidationReport?>(null)
    val lastReport: StateFlow<ConsolidationReport?> = _lastReport.asStateFlow()

    @Volatile
    var lastRunAtMs: Long = 0L
        private set

    /**
     * 立即执行一轮完整的睡眠期记忆整合、Routine 挖掘与 `MEMORY.md` 渲染落盘。
     */
    suspend fun runOnce(nowMs: Long = clock()): ConsolidationReport = mutex.withLock {
        withContext(ioDispatcher) {
            val (report, markdown) = SleepConsolidator.consolidate(
                memoryStore = memoryStore,
                taskMemoryStore = taskMemoryStore,
                factGraph = factGraph,
                minRoutineOccurrences = minRoutineOccurrences,
                nowMs = nowMs,
                zoneId = zoneId,
            )
            markdownFile.write(markdown)
            saveRoutines(report.routinesDetected)
            _routines.value = report.routinesDetected
            _lastReport.value = report
            lastRunAtMs = nowMs
            report
        }
    }

    /**
     * 设备进入空闲态时调用的门控触发器：仅当处于空闲且距离上次执行超过 [minIntervalMs] 时才执行整合。
     */
    suspend fun onIdleTick(isDeviceIdle: Boolean, nowMs: Long = clock()): ConsolidationReport? {
        if (!isDeviceIdle) return null
        if (lastRunAtMs > 0L && (nowMs - lastRunAtMs) < minIntervalMs) return null
        return runOnce(nowMs)
    }

    /**
     * 将用户在 `MEMORY.md` 中的手工修改写回 [MemoryStore]，并刷新投影视图。
     */
    suspend fun syncMarkdownEdits(deleteMissing: Boolean = false): MarkdownSyncResult = mutex.withLock {
        withContext(ioDispatcher) {
            val md = markdownFile.readOrNull() ?: return@withContext MarkdownSyncResult()
            val result = MarkdownMemoryProjection.syncEditsToStore(md, memoryStore, deleteMissing = deleteMissing)
            val refreshed = MarkdownMemoryProjection.render(
                items = memoryStore.items.value,
                facts = factGraph?.allFacts().orEmpty(),
                tasks = taskMemoryStore?.getAll().orEmpty(),
                routines = _routines.value,
                nowMs = clock(),
                zoneId = zoneId,
            )
            markdownFile.write(refreshed)
            result
        }
    }

    private fun loadSavedRoutines(): List<RoutineSuggestion> {
        if (!routinesFile.exists() || routinesFile.length() == 0L) return emptyList()
        return try {
            json.decodeFromString<List<RoutineSuggestion>>(routinesFile.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveRoutines(list: List<RoutineSuggestion>) {
        routinesFile.parentFile?.mkdirs()
        val tmp = File(routinesFile.parentFile, "${routinesFile.name}.tmp")
        tmp.writeText(json.encodeToString(list))
        if (!tmp.renameTo(routinesFile)) {
            routinesFile.writeText(tmp.readText())
            tmp.delete()
        }
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}

/**
 * 基于 Android 原生 [JobScheduler] 的系统空闲调度入口。
 * 要求设备空闲且周期性执行睡眠期记忆整合，无需引入额外后台框架依赖。
 */
object SleepConsolidationScheduler {
    const val JOB_ID = 0x4841_4E01

    @JvmOverloads
    fun schedule(
        context: Context,
        intervalMs: Long = SleepConsolidationWorker.DEFAULT_MIN_INTERVAL_MS,
        requireCharging: Boolean = false,
    ): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        val component = ComponentName(context, SleepConsolidationJobService::class.java)
        val info = JobInfo.Builder(JOB_ID, component)
            .setRequiresDeviceIdle(true)
            .setRequiresCharging(requireCharging)
            .setPeriodic(intervalMs.coerceAtLeast(JobInfo.getMinPeriodMillis()))
            .build()
        return scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }
}

/** 系统空闲时运行的睡眠期整合 [JobService]。 */
class SleepConsolidationJobService : JobService() {
    private var runningJob: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext as? MemoryRuntime.Owner ?: return false
        val runtime = app.memory
        runningJob = CoroutineScope(Dispatchers.IO).launch {
            val reschedule = try {
                runtime.sleepConsolidationWorker().runOnce()
                false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                true
            }
            jobFinished(params, reschedule)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runningJob?.cancel()
        runningJob = null
        return true
    }
}
