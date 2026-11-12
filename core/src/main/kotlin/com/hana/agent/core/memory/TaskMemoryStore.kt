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
 * 跨会话长期任务记忆存储中心。
 * 管理历史任务经验的读写、文件持久化、近期足迹 timeline 及倒排关键词检索。
 *
 * @param persistence 持久化读写器
 * @param limit 最大保存的任务记忆条数，默认 1000 条
 */
class TaskMemoryStore(
    private val persistence: TaskMemoryPersistence,
    val limit: Int = 1000,
) {
    /** 支持直接传入 JSON 文件的便捷构造方法。 */
    constructor(file: File, limit: Int = 1000) : this(FileTaskMemoryPersistence(file), limit)

    private val lock = Any()
    private val _memories = MutableStateFlow<List<TaskMemory>>(emptyList())
    val memories: StateFlow<List<TaskMemory>> = _memories.asStateFlow()

    @Volatile
    private var index: TaskMemoryIndex = TaskMemoryIndex(emptyList())

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
        index = TaskMemoryIndex(loaded)
        loaded
    }

    /** 写入一条新的任务记忆并持久化，同时同步更新倒排索引。 */
    fun add(memory: TaskMemory): TaskMemory = synchronized(lock) {
        val current = _memories.value.filterNot { it.id == memory.id }
        // 超出限制时淘汰最旧的记录
        val updated = (listOf(memory) + current).take(limit)
        _memories.value = updated
        try {
            persistence.save(updated)
        } catch (e: Exception) {
            // 写入失败不阻断内存运行
        }
        index = TaskMemoryIndex(updated)
        memory
    }

    /** 获取指定 ID 的任务记忆。 */
    fun get(id: String): TaskMemory? = _memories.value.find { it.id == id }

    /** 获取当前所有任务记忆列表。 */
    fun getAll(): List<TaskMemory> = _memories.value

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
     * 按意图关键词或 App 进行语义模糊检索。
     *
     * @param query 意图关键词或操作词
     * @param app 限定的目标 App 包名或名称
     * @param limit 返回最大条数
     */
    fun search(query: String, app: String? = null, limit: Int = 10): List<TaskMemory> {
        return index.search(query, app, limit)
    }

    /** 清空任务记忆（主要用于测试）。 */
    fun clear() = synchronized(lock) {
        _memories.value = emptyList()
        index = TaskMemoryIndex(emptyList())
        try {
            persistence.save(emptyList())
        } catch (_: Exception) {}
    }
}
