package com.hana.agent.core.proactive

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * 联系人活跃信息。
 *
 * @property id 联系人唯一标识
 * @property name 联系人姓名或昵称
 * @property packageName 关联的应用包名（如微信、短信等）
 * @property lastContactMs 最近一次互动时间戳
 * @property frequency 互动频次计数
 */
@Serializable
data class ContactInfo(
    val id: String,
    val name: String,
    val packageName: String,
    val lastContactMs: Long,
    val frequency: Int = 1
)

/**
 * 联系人池：管理常用联系人互动偏好与频次。
 * 纯 JVM 实现，线程安全。
 */
class ContactPool(
    private val maxCapacity: Int = 100
) {
    private val contacts = ConcurrentHashMap<String, ContactInfo>()

    /**
     * 记录或更新联系人互动。
     */
    fun recordContact(id: String, name: String, packageName: String, timestampMs: Long = System.currentTimeMillis()) {
        contacts.compute(id) { _, existing ->
            if (existing != null) {
                existing.copy(
                    name = name,
                    packageName = packageName,
                    lastContactMs = timestampMs,
                    frequency = existing.frequency + 1
                )
            } else {
                pruneIfOverCapacity()
                ContactInfo(
                    id = id,
                    name = name,
                    packageName = packageName,
                    lastContactMs = timestampMs,
                    frequency = 1
                )
            }
        }
    }

    /**
     * 获取按互动频次降序排列的高频联系人列表。
     */
    fun getTopContacts(limit: Int = 20): List<ContactInfo> {
        return contacts.values
            .sortedWith(compareByDescending<ContactInfo> { it.frequency }.thenByDescending { it.lastContactMs })
            .take(limit)
    }

    /**
     * 移除联系人。
     */
    fun removeContact(id: String) {
        contacts.remove(id)
    }

    /**
     * 清空联系人池。
     */
    fun clear() {
        contacts.clear()
    }

    /**
     * 导出为列表。
     */
    fun listAll(): List<ContactInfo> = contacts.values.toList()

    private fun pruneIfOverCapacity() {
        if (contacts.size >= maxCapacity) {
            val leastActive = contacts.values.minWithOrNull(
                compareBy<ContactInfo> { it.frequency }.thenBy { it.lastContactMs }
            )
            if (leastActive != null) {
                contacts.remove(leastActive.id)
            }
        }
    }
}

/**
 * 话题概要信息。
 *
 * @property id 话题标识
 * @property topic 话题标题或关键字
 * @property sourcePackage 来源应用包名
 * @property summary 话题要点或简短摘要
 * @property lastUpdatedMs 最近更新时间戳
 * @property hitCount 关联事件命中次数
 */
@Serializable
data class TopicInfo(
    val id: String,
    val topic: String,
    val sourcePackage: String,
    val summary: String,
    val lastUpdatedMs: Long,
    val hitCount: Int = 1
)

/**
 * 话题池：管理当前正在追踪的主题或待办事件簇。
 * 纯 JVM 实现，线程安全。
 */
class TopicPool(
    private val maxCapacity: Int = 100
) {
    private val topics = ConcurrentHashMap<String, TopicInfo>()

    /**
     * 添加或更新话题。
     */
    fun recordTopic(
        id: String,
        topic: String,
        sourcePackage: String,
        summary: String,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        topics.compute(id) { _, existing ->
            if (existing != null) {
                existing.copy(
                    topic = topic,
                    sourcePackage = sourcePackage,
                    summary = summary,
                    lastUpdatedMs = timestampMs,
                    hitCount = existing.hitCount + 1
                )
            } else {
                pruneIfOverCapacity()
                TopicInfo(
                    id = id,
                    topic = topic,
                    sourcePackage = sourcePackage,
                    summary = summary,
                    lastUpdatedMs = timestampMs,
                    hitCount = 1
                )
            }
        }
    }

    /**
     * 获取最近更新的话题列表。
     */
    fun getRecentTopics(limit: Int = 20): List<TopicInfo> {
        return topics.values
            .sortedByDescending { it.lastUpdatedMs }
            .take(limit)
    }

    /**
     * 移除话题。
     */
    fun removeTopic(id: String) {
        topics.remove(id)
    }

    /**
     * 清空话题池。
     */
    fun clear() {
        topics.clear()
    }

    /**
     * 导出为列表。
     */
    fun listAll(): List<TopicInfo> = topics.values.toList()

    private fun pruneIfOverCapacity() {
        if (topics.size >= maxCapacity) {
            val oldest = topics.values.minByOrNull { it.lastUpdatedMs }
            if (oldest != null) {
                topics.remove(oldest.id)
            }
        }
    }
}

/**
 * 单条摘要项。
 *
 * @property id 唯一 ID
 * @property timestampMs 事件发生时间戳
 * @property category 分类（如 "SCHEDULE", "EXPRESS", "MESSAGE", "SYSTEM"）
 * @property title 标题
 * @property summary 结构化摘要描述
 * @property sourcePackage 来源包名
 */
@Serializable
data class DigestItem(
    val id: String,
    val timestampMs: Long,
    val category: String,
    val title: String,
    val summary: String,
    val sourcePackage: String
)

/**
 * 汇总日/周摘要模型，支持 JSON 持久化序列化与反序列化。
 *
 * @property date 汇总归属日期（如 "2026-11-14"）
 * @property items 汇总所包含的摘要项集合
 * @property generatedAtMs 生成该汇总的时间戳
 */
@Serializable
data class DigestRollup(
    val date: String,
    val items: List<DigestItem> = emptyList(),
    val generatedAtMs: Long = System.currentTimeMillis()
)

/**
 * 主动摘要存储与淘汰管理器。
 * 纯 JVM 实现，具备线程安全增删改查、过期数据清理及 JSON 导入导出能力。
 */
class ProactiveDigestStore(
    private val maxItemsPerRollup: Int = 200
) {
    private val lock = Any()
    private val rollups = mutableMapOf<String, DigestRollup>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /**
     * 向指定日期添加一条摘要项。
     */
    fun addItem(date: String, item: DigestItem): Unit = synchronized(lock) {
        val existing = rollups[date]
        val currentItems = existing?.items ?: emptyList()
        val updatedItems = (currentItems + item).takeLast(maxItemsPerRollup)
        rollups[date] = DigestRollup(
            date = date,
            items = updatedItems,
            generatedAtMs = System.currentTimeMillis()
        )
    }

    /**
     * 获取指定日期的摘要汇总。
     */
    fun getRollup(date: String): DigestRollup? = synchronized(lock) {
        return rollups[date]
    }

    /**
     * 获取所有现存的摘要汇总列表（按日期倒序）。
     */
    fun listAllRollups(): List<DigestRollup> = synchronized(lock) {
        return rollups.values.sortedByDescending { it.date }
    }

    /**
     * 淘汰指定保留天数之前的历史汇总。
     *
     * @param retentionDays 保留的天数（必须大于等于 1）
     * @param today 当前日期字符串（如 "2026-11-14"）
     */
    fun purgeOlderThan(retentionDays: Int, today: String): Unit = synchronized(lock) {
        val sortedDates = rollups.keys.sorted()
        val keepThreshold = sortedDates.takeLast(retentionDays.coerceAtLeast(1)).toSet()
        val keysToRemove = rollups.keys.filter { it !in keepThreshold && it < today }
        keysToRemove.forEach { rollups.remove(it) }
    }

    /**
     * 将当前存储内容导出为标准 JSON 字符串。
     */
    fun exportToJson(): String = synchronized(lock) {
        return json.encodeToString(rollups.values.toList())
    }

    /**
     * 从 JSON 字符串加载并合并摘要数据。
     */
    fun loadFromJson(jsonString: String): Unit = synchronized(lock) {
        if (jsonString.isBlank()) return
        val list = json.decodeFromString<List<DigestRollup>>(jsonString)
        for (rollup in list) {
            val existing = rollups[rollup.date]
            if (existing != null) {
                // 合并去重
                val existingIds = existing.items.map { it.id }.toSet()
                val newItems = rollup.items.filter { it.id !in existingIds }
                val merged = (existing.items + newItems).takeLast(maxItemsPerRollup)
                rollups[rollup.date] = existing.copy(items = merged, generatedAtMs = System.currentTimeMillis())
            } else {
                rollups[rollup.date] = rollup
            }
        }
    }

    /**
     * 清空所有摘要数据。
     */
    fun clear(): Unit = synchronized(lock) {
        rollups.clear()
    }
}
