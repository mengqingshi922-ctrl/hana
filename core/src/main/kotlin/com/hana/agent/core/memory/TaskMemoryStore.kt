package com.hana.agent.core.memory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 任务记忆的持久化抽象接口，支持轻量级 JSON 文件或自定义加密存储。
 */
interface TaskMemoryPersistence {
    fun load(): List<TaskMemory>
    fun save(memories: List<TaskMemory>)
}

/**
 * 任务记忆冷归档持久化抽象接口。
 */
interface TaskMemoryArchivePersistence {
    fun loadArchive(): List<TaskMemory>
    fun appendArchive(memories: List<TaskMemory>)
    fun clearArchive() {}
}

/**
 * 基于本地 JSON 文件的任务记忆持久化实现。
 */
class FileTaskMemoryPersistence(private val file: File) : TaskMemoryPersistence {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override fun load(): List<TaskMemory> {
        if (!file.exists() || file.length() == 0L) return emptyList()
        return try {
            json.decodeFromString<List<TaskMemory>>(file.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun save(memories: List<TaskMemory>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(memories))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}

/**
 * 基于本地 JSON 文件的任务记忆冷归档持久化实现。
 */
class FileTaskMemoryArchivePersistence(private val file: File) : TaskMemoryArchivePersistence {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Synchronized
    override fun loadArchive(): List<TaskMemory> {
        if (!file.exists() || file.length() == 0L) return emptyList()
        return try {
            json.decodeFromString<List<TaskMemory>>(file.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    override fun appendArchive(memories: List<TaskMemory>) {
        if (memories.isEmpty()) return
        val existing = loadArchive()
        val byId = existing.associateByTo(LinkedHashMap()) { it.id }
        memories.forEach { byId[it.id] = it }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(byId.values.toList()))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    @Synchronized
    override fun clearArchive() {
        if (file.exists()) file.delete()
    }
}

/**
 * 跨会话长期任务记忆存储中心。
 * 管理历史任务经验的读写、文件持久化、近期足迹 timeline、倒排关键词检索与冷归档流转。
 *
 * @param persistence 持久化读写器
 * @param limit 活跃区最大保存的任务记忆条数，默认 1000 条（超限优雅转入冷归档）
 * @param clock 时钟源（毫秒）
 * @param archivePersistence 可选冷归档持久化器
 */
class TaskMemoryStore(
    private val persistence: TaskMemoryPersistence,
    val limit: Int = 1000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val archivePersistence: TaskMemoryArchivePersistence? = null,
) {
    /** 支持直接传入 JSON 文件的便捷构造方法（自动配套同目录冷归档文件）。 */
    constructor(
        file: File,
        limit: Int = 1000,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        persistence = FileTaskMemoryPersistence(file),
        limit = limit,
        clock = clock,
        archivePersistence = FileTaskMemoryArchivePersistence(
            File(file.parentFile ?: File("."), "${file.nameWithoutExtension}_archive.json")
        ),
    )

    private val lock = Any()
    private val _memories = MutableStateFlow<List<TaskMemory>>(emptyList())
    val memories: StateFlow<List<TaskMemory>> = _memories.asStateFlow()

    private val fallbackArchive = LinkedHashMap<String, TaskMemory>()

    @Volatile
    private var index: TaskMemoryIndex = TaskMemoryIndex(emptyList(), clock())

    init {
        reload()
    }

    /** 从持久化介质重新载入全部任务记忆并重建内存索引。 */
    fun reload(): List<TaskMemory> = synchronized(lock) {
        val loaded = try {
            persistence.load()
        } catch (e: Exception) {
            emptyList()
        }
        _memories.value = loaded
        index = TaskMemoryIndex(loaded, clock())
        loaded
    }

    /** 写入一条新的任务记忆并持久化，同时同步更新倒排索引；超限时按 Ebbinghaus 保持分将低分老旧记忆转入冷归档。 */
    fun add(memory: TaskMemory): TaskMemory = synchronized(lock) {
        val now = clock()
        val current = _memories.value.filterNot { it.id == memory.id }
        val updated = if (limit <= 0) {
            archiveMemories(listOf(memory) + current)
            emptyList()
        } else if (current.size + 1 > limit) {
            val needEvict = current.size + 1 - limit
            val rankedEvict = current.sortedWith(
                compareBy<TaskMemory> {
                    EbbinghausRetention.archivalScore(
                        lastTimeMs = it.lastAccessedAt ?: it.timestamp,
                        nowMs = now,
                        strength = it.strength,
                        accessCount = it.accessCount,
                    )
                }.thenBy { it.timestamp }
            )
            val evicted = rankedEvict.take(needEvict)
            val evictIds = evicted.map { it.id }.toSet()
            archiveMemories(evicted)
            listOf(memory) + current.filterNot { it.id in evictIds }
        } else {
            listOf(memory) + current
        }

        _memories.value = updated
        persistQuietly(updated)
        index = TaskMemoryIndex(updated, now)
        memory
    }

    /** 获取指定 ID 的任务记忆。 */
    fun get(id: String): TaskMemory? = _memories.value.find { it.id == id }

    /** 获取当前所有任务记忆列表。 */
    fun getAll(): List<TaskMemory> = _memories.value

    /** 查看冷归档中的历史任务记忆。 */
    fun archived(): List<TaskMemory> = synchronized(lock) {
        val ap = archivePersistence
        if (ap != null) {
            runCatching { ap.loadArchive() }.getOrDefault(emptyList())
        } else {
            fallbackArchive.values.toList()
        }
    }

    /**
     * 浏览近期的任务执行足迹（Timeline）。
     *
     * @param limit 返回条数，默认 20 条
     * @param beforeTimestamp 可选，时间戳游标，返回早于该时间戳的任务
     */
    fun timeline(limit: Int = 20, beforeTimestamp: Long? = null): List<TaskMemory> {
        val all = _memories.value.sortedByDescending { it.timestamp }
        val filtered = if (beforeTimestamp != null) all.filter { it.timestamp < beforeTimestamp } else all
        return filtered.take(limit.coerceAtLeast(1))
    }

    /**
     * 按意图关键词或 App 进行语义模糊检索（引入 Ebbinghaus 时间衰减），默认命中后自动强化 [strength]。
     */
    @JvmOverloads
    fun search(
        query: String,
        app: String? = null,
        limit: Int = 10,
        reinforce: Boolean = true,
    ): List<TaskMemory> = synchronized(lock) {
        val now = clock()
        // 使用当前时钟重建/查询索引确保时间衰减准确
        val currentIndex = TaskMemoryIndex(_memories.value, now)
        index = currentIndex
        val hits = currentIndex.search(query, app, limit)
        if (!reinforce || hits.isEmpty()) return hits

        val hitIds = hits.map { it.id }.toSet()
        val reinforcedMap = HashMap<String, TaskMemory>(hitIds.size)
        val updated = _memories.value.map { mem ->
            if (mem.id in hitIds) {
                val refTime = mem.lastAccessedAt ?: mem.timestamp
                mem.copy(
                    lastAccessedAt = now,
                    accessCount = mem.accessCount + 1,
                    strength = EbbinghausRetention.reinforce(mem.strength, refTime, now),
                ).also { reinforcedMap[it.id] = it }
            } else {
                mem
            }
        }
        _memories.value = updated
        persistQuietly(updated)
        index = TaskMemoryIndex(updated, now)
        hits.map { reinforcedMap[it.id] ?: it }
    }

    /** 在冷归档中按意图关键词或 App 检索历史任务记忆。 */
    fun searchArchive(query: String, app: String? = null, limit: Int = 10): List<TaskMemory> {
        val arc = archived()
        if (arc.isEmpty()) return emptyList()
        return TaskMemoryIndex(arc, clock()).search(query, app, limit)
    }

    /** 显式记录访问强化指定任务记忆的保持强度。 */
    fun recordAccess(ids: List<String>) = synchronized(lock) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        val now = clock()
        var changed = false
        val updated = _memories.value.map { mem ->
            if (mem.id in set) {
                changed = true
                val refTime = mem.lastAccessedAt ?: mem.timestamp
                mem.copy(
                    lastAccessedAt = now,
                    accessCount = mem.accessCount + 1,
                    strength = EbbinghausRetention.reinforce(mem.strength, refTime, now),
                )
            } else mem
        }
        if (changed) {
            _memories.value = updated
            persistQuietly(updated)
            index = TaskMemoryIndex(updated, now)
        }
    }

    /** 清空任务记忆及冷归档（主要用于测试）。 */
    fun clear() = synchronized(lock) {
        _memories.value = emptyList()
        fallbackArchive.clear()
        index = TaskMemoryIndex(emptyList(), clock())
        runCatching { archivePersistence?.clearArchive() }
        persistQuietly(emptyList())
    }

    private fun archiveMemories(items: List<TaskMemory>) {
        if (items.isEmpty()) return
        val ap = archivePersistence
        if (ap != null) {
            runCatching { ap.appendArchive(items) }
        } else {
            items.forEach { fallbackArchive[it.id] = it }
        }
    }

    private fun persistQuietly(memories: List<TaskMemory>) {
        try {
            persistence.save(memories)
        } catch (_: Exception) {
            // 写入失败不阻断内存运行
        }
    }
}
