package com.hana.agent.chat

import android.content.Context
import com.hana.agent.core.agent.Turn
import com.hana.agent.core.memory.MemoryNote
import com.hana.agent.core.memory.NoteKind
import com.hana.agent.core.skills.SkillNote
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 每个对话采用追加日志文件 (.jsonl) 存储，并在目录内维护单文件元数据索引 (index.json)；
 * 兼容旧版本单 JSON 数组 (.json) 文件并实现无感平滑迁移。
 */
class ConversationStore(private val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, "conversations"))

    fun newConversation(): String = UUID.randomUUID().toString()

    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * 写过的对话编号:存进一轮、改了某一轮、去掉记忆提示时各发一次,不管是谁写的(对话页、悬浮胶囊、定时任务)。
     * 对话页和侧栏据此重读,屏上的就是存下的那份。
     */
    val changes: SharedFlow<String> = _changes.asSharedFlow()

    private val _deletions = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 删掉的对话编号:[delete] 时发一次([changes] 也照发)。悬浮胶囊据此拿掉这个对话刚结束的任务、卡片不再接着它。 */
    val deletions: SharedFlow<String> = _deletions.asSharedFlow()

    /**
     * 用户此刻在哪个对话,见 [CurrentChat]。存在对话目录里的 `current` 文件(不是 `.json`,不进 [list]);
     * 进程重启后照样读得回来,胶囊不必等对话文件写下来才知道新开的是哪个。
     */
    private val _current = MutableStateFlow(readCurrent())

    val current: StateFlow<CurrentChat?> = _current.asStateFlow()

    fun currentChat(): CurrentChat? = _current.value

    /** 记下用户此刻在哪个对话;删掉的对话记不住。写进去了发一次 [current]。 */
    @Synchronized
    fun setCurrent(chat: CurrentChat?) {
        val target = when (chat) {
            is CurrentChat.Open -> if (safe(chat.id) in deleted) null else chat
            else -> chat
        }
        if (target == _current.value) return
        dir.mkdirs()
        runCatching {
            when (target) {
                null -> currentFile().delete()
                CurrentChat.Fresh -> currentFile().writeText(CURRENT_FRESH)
                is CurrentChat.Open -> currentFile().writeText(safe(target.id))
            }
        }
        _current.value = target
    }

    private fun readCurrent(): CurrentChat? {
        val f = currentFile().takeIf { it.isFile } ?: return null
        val text = runCatching { f.readText().trim() }.getOrNull().orEmpty()
        return when {
            text.isEmpty() -> null
            text == CURRENT_FRESH -> CurrentChat.Fresh
            else -> safe(text).takeIf(String::isNotEmpty)?.let(CurrentChat::Open)
        }
    }

    /**
     * 元数据索引条目：记录对话的基本摘要与各关联文件的时间戳校验戳，用于快速组装列表与感知外部变更。
     */
    private data class IndexEntry(
        val id: String,
        val firstUserText: String?,
        val title: String?,
        val updatedAt: Long,
        val pinnedAt: Long?,
        val pageOrigin: PageOrigin?,
        val fileModified: Long,
        val titleModified: Long = 0L,
        val pinModified: Long = 0L,
        val pageModified: Long = 0L,
    )

    private val indexEntries = mutableMapOf<String, IndexEntry>()
    private var indexLoaded = false

    private val _summaries = MutableStateFlow<List<ConversationSummary>>(emptyList())

    /**
     * 对话摘要列表的状态流，初始及每次对话变更后实时更新。
     */
    val summaries: StateFlow<List<ConversationSummary>> = _summaries.asStateFlow()

    init {
        loadIndexOnInit()
    }

    private fun loadIndexOnInit() {
        val f = indexFile()
        if (f.isFile) {
            val text = runCatching { f.readText() }.getOrNull().orEmpty()
            val loaded = parseIndex(text)
            if (loaded != null) {
                indexEntries.putAll(loaded)
                indexLoaded = true
                updateSummariesState()
            }
        }
    }

    /**
     * 对话列表,最近改过的在前;[ConversationSummary.title] 是标题(用户改的或起好的),没有时是第一句话;
     * [ConversationSummary.pinnedAt] 是置顶的时间;[ConversationSummary.page] 是从哪一页开始的。没有内容的对话不列。
     */
    @Synchronized
    fun list(): List<ConversationSummary> {
        dir.mkdirs()
        if (!indexLoaded) {
            val f = indexFile()
            if (f.isFile) {
                val text = runCatching { f.readText() }.getOrNull().orEmpty()
                parseIndex(text)?.let { indexEntries.putAll(it) }
            }
            indexLoaded = true
        }

        val files = dir.listFiles { f ->
            f.isFile && (f.name.endsWith(".jsonl") || f.name.endsWith(".json")) &&
                f.name != "index.json" && f.name != "index.json.tmp" &&
                !f.name.contains(".corrupt-") && !f.name.endsWith(".tmp")
        }.orEmpty()

        val mainFilesById = mutableMapOf<String, File>()
        for (f in files) {
            val id = if (f.name.endsWith(".jsonl")) f.name.removeSuffix(".jsonl") else f.name.removeSuffix(".json")
            val existing = mainFilesById[id]
            if (existing == null || f.name.endsWith(".jsonl")) {
                mainFilesById[id] = f
            }
        }

        var dirty = false
        val currentIds = mainFilesById.keys
        val staleIds = indexEntries.keys.filter { it !in currentIds }
        if (staleIds.isNotEmpty()) {
            staleIds.forEach { indexEntries.remove(it) }
            dirty = true
        }

        for ((id, file) in mainFilesById) {
            val fMod = file.lastModified()
            val entry = indexEntries[id]
            if (entry != null && entry.fileModified == fMod && entry.firstUserText != null) {
                // 命中索引且文件时间戳匹配，无需磁盘 stat 或反序列化
                continue
            }

            // 索引缺失或文件被修改，增量读取
            val turnsList = if (file.name.endsWith(".jsonl")) {
                records(id).map { it.first }
            } else {
                readLegacyJson(file)?.map { it.first }
            }
            val firstText = turnsList?.firstOrNull()?.userText
            if (firstText == null) {
                if (indexEntries.remove(id) != null) dirty = true
                continue
            }
            val tf = titleFile(id)
            val pf = pinFile(id)
            val pgf = pageFile(id)
            val t = title(id)
            val pin = pinnedAt(id)
            val page = pageOrigin(id)

            indexEntries[id] = IndexEntry(
                id = id,
                firstUserText = firstText,
                title = t,
                updatedAt = fMod,
                pinnedAt = pin,
                pageOrigin = page,
                fileModified = fMod,
                titleModified = if (tf.isFile) tf.lastModified() else 0L,
                pinModified = if (pf.isFile) pf.lastModified() else 0L,
                pageModified = if (pgf.isFile) pgf.lastModified() else 0L,
            )
            dirty = true
        }

        if (dirty) {
            persistIndex()
        }
        updateSummariesState()
        return _summaries.value
    }

    /** 对话起好的标题(见 [ConversationTitler]);没有、读不到为 null。标题放在对话文件旁边的 `.title` 文件里,不动对话文件的格式。 */
    @Synchronized
    fun title(conversationId: String): String? =
        runCatching { titleFile(conversationId).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.takeIf(String::isNotEmpty)

    /**
     * 删掉过的对话。删之后才到的「补写」(后台起好的标题、任务收尾后补的结局和交代)不再把它写回来;
     * 再往里存进一轮([append])就是重新开始用这个编号,从这里拿掉。只记在进程里。
     */
    private val deleted = mutableSetOf<String>()

    /**
     * 删掉一个对话存下的一切:对话文件、标题、置顶,以及写到一半的临时文件和挪到一边的损坏文件。删了什么都发一次 [changes]
     * 和 [deletions]。
     * 对话的工作区、定时任务、在跑的任务不归这里管,见 [ConversationDeleter]。
     */
    @Synchronized
    fun delete(conversationId: String) {
        val sId = safe(conversationId)
        deleted += sId
        val removed = indexEntries.remove(sId) != null || indexEntries.remove(conversationId) != null
        if (removed) {
            persistIndex()
            updateSummariesState()
        }

        val jsonName = jsonFile(conversationId).name
        val jsonlName = jsonlFile(conversationId).name
        val titleName = titleFile(conversationId).name
        val pinName = pinFile(conversationId).name
        val pageName = pageFile(conversationId).name

        dir.listFiles { f ->
            if (!f.isFile) return@listFiles false
            val n = f.name
            n == jsonName || n.startsWith("$jsonName.") ||
                n == jsonlName || n.startsWith("$jsonlName.") ||
                n == titleName || n == pinName || n == pageName
        }?.forEach { it.delete() }

        // 删掉的正是用户此刻在的那个:不再指着它,胶囊回落到最近那个
        if (_current.value == CurrentChat.Open(sId)) setCurrent(null)
        _changes.tryEmit(conversationId)
        _deletions.tryEmit(conversationId)
    }

    /**
     * 给对话记下起好的标题;已经有标题(起好的或用户改的)就不动(标题只起一次)。写进去了返回 true。
     * 所以后台起标题晚到时,用户先改的名字不会被盖掉。
     */
    @Synchronized
    fun setTitle(conversationId: String, title: String): Boolean {
        if (title(conversationId) != null) return false
        return writeTitle(conversationId, title)
    }

    /** 用户给对话改名:不管有没有标题都换成 [title];空的不改。写进去了返回 true。 */
    @Synchronized
    fun rename(conversationId: String, title: String): Boolean = writeTitle(conversationId, title)

    private fun writeTitle(conversationId: String, title: String): Boolean {
        val t = title.trim()
        val sId = safe(conversationId)
        if (t.isEmpty() || sId in deleted) return false
        dir.mkdirs()
        val tf = titleFile(conversationId)
        val ok = runCatching { tf.writeText(t) }.isSuccess
        if (ok) {
            val entry = indexEntries[sId]
            if (entry != null) {
                indexEntries[sId] = entry.copy(title = t, titleModified = tf.lastModified())
            } else {
                val mf = activeMainFile(sId)
                if (mf != null) {
                    val fMod = mf.lastModified()
                    indexEntries[sId] = IndexEntry(
                        id = sId,
                        firstUserText = records(sId).firstOrNull()?.first?.userText,
                        title = t,
                        updatedAt = fMod,
                        pinnedAt = pinnedAt(conversationId),
                        pageOrigin = pageOrigin(conversationId),
                        fileModified = fMod,
                        titleModified = tf.lastModified(),
                    )
                }
            }
            persistIndex()
            updateSummariesState()
            _changes.tryEmit(conversationId)
        }
        return ok
    }

    /** 对话置顶的时间;没置顶、读不到为 null。放在对话文件旁边的 `.pin` 文件里。 */
    @Synchronized
    fun pinnedAt(conversationId: String): Long? =
        runCatching { pinFile(conversationId).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() }.getOrNull()

    /** 置顶([pinned] 为真,记下时间 [at])或取消置顶。删掉的对话不动。改了返回 true。 */
    @Synchronized
    fun setPinned(conversationId: String, pinned: Boolean, at: Long = System.currentTimeMillis()): Boolean {
        val sId = safe(conversationId)
        if (sId in deleted) return false
        val f = pinFile(conversationId)
        val ok = if (pinned) {
            dir.mkdirs()
            runCatching { f.writeText(at.toString()) }.isSuccess
        } else !f.exists() || f.delete()
        if (ok) {
            val entry = indexEntries[sId]
            val newPin = if (pinned) at else null
            val pinMod = if (f.isFile) f.lastModified() else 0L
            if (entry != null) {
                indexEntries[sId] = entry.copy(pinnedAt = newPin, pinModified = pinMod)
            } else {
                val mf = activeMainFile(sId)
                if (mf != null) {
                    val fMod = mf.lastModified()
                    indexEntries[sId] = IndexEntry(
                        id = sId,
                        firstUserText = records(sId).firstOrNull()?.first?.userText,
                        title = title(conversationId),
                        updatedAt = fMod,
                        pinnedAt = newPin,
                        pageOrigin = pageOrigin(conversationId),
                        fileModified = fMod,
                        pinModified = pinMod,
                    )
                }
            }
            persistIndex()
            updateSummariesState()
            _changes.tryEmit(conversationId)
        }
        return ok
    }

    /**
     * 对话是从哪一页开始的(用户在浏览器页上对着一页问 Hana);不是、读不到为 null。
     * 放在对话文件旁边的 `.page` 文件里(JSON:url、host、title),不动对话文件的格式;旧对话没有这个文件。
     */
    @Synchronized
    fun pageOrigin(conversationId: String): PageOrigin? = runCatching {
        val f = pageFile(conversationId).takeIf { it.isFile } ?: return@runCatching null
        val o = Json.parseToJsonElement(f.readText()) as? JsonObject ?: return@runCatching null
        PageOrigin(str(o, "url") ?: return@runCatching null, str(o, "host") ?: return@runCatching null, str(o, "title").orEmpty())
    }.getOrNull()

    /** 记下对话从哪一页开始(之后在同一页上接着问时换成最新的那一页)。删掉的对话不动。写进去了返回 true。 */
    @Synchronized
    fun setPageOrigin(conversationId: String, origin: PageOrigin): Boolean {
        val sId = safe(conversationId)
        if (sId in deleted) return false
        dir.mkdirs()
        val f = pageFile(conversationId)
        val json = buildJsonObject { put("url", origin.url); put("host", origin.host); put("title", origin.title) }
        val ok = runCatching { f.writeText(json.toString()) }.isSuccess
        if (ok) {
            val entry = indexEntries[sId]
            val pageMod = if (f.isFile) f.lastModified() else 0L
            if (entry != null) {
                indexEntries[sId] = entry.copy(pageOrigin = origin, pageModified = pageMod)
            } else {
                val mf = activeMainFile(sId)
                if (mf != null) {
                    val fMod = mf.lastModified()
                    indexEntries[sId] = IndexEntry(
                        id = sId,
                        firstUserText = records(sId).firstOrNull()?.first?.userText,
                        title = title(conversationId),
                        updatedAt = fMod,
                        pinnedAt = pinnedAt(conversationId),
                        pageOrigin = origin,
                        fileModified = fMod,
                        pageModified = pageMod,
                    )
                }
            }
            persistIndex()
            updateSummariesState()
            _changes.tryEmit(conversationId)
        }
        return ok
    }

    /** 文件损坏时当作空对话,不让它挡住新任务。 */
    @Synchronized
    fun turns(conversationId: String): List<Turn> = records(conversationId).map { it.first }

    /** 每一轮连同这一轮导出的文件(文件卡片)。优先读取追加日志 (.jsonl)，回退兼容旧版 (.json)。 */
    @Synchronized
    fun records(conversationId: String): List<Pair<Turn, List<SharedFile>>> {
        val jl = jsonlFile(conversationId)
        if (jl.isFile) {
            val list = mutableListOf<Pair<Turn, List<SharedFile>>>()
            try {
                jl.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        runCatching {
                            val elem = Json.parseToJsonElement(trimmed)
                            parseRecord(elem)?.let { list.add(it) }
                        }
                    }
                }
            } catch (_: Exception) {
                // 忽略非致命异常
            }
            return list
        }
        val j = jsonFile(conversationId)
        if (j.isFile) {
            return readLegacyJson(j).orEmpty()
        }
        return emptyList()
    }

    /** 读不懂(不是 JSON 数组)时返回 null。用于旧版 .json 文件读取。 */
    private fun readLegacyJson(f: File): List<Pair<Turn, List<SharedFile>>>? = try {
        (Json.parseToJsonElement(f.readText()) as? JsonArray)?.mapNotNull(::parseRecord)
    } catch (_: SerializationException) {
        null
    } catch (_: Exception) {
        null
    }

    private fun parseRecord(e: kotlinx.serialization.json.JsonElement): Pair<Turn, List<SharedFile>>? {
        val o = e as? JsonObject ?: return null
        fun field(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        val turn = Turn(
            field("userText") ?: return null,
            field("outcome").orEmpty(),
            field("status") ?: return null,
            field("note"),
            field("runId"),
            memoryNotes = objects(o, "memoryNotes").mapNotNull { n ->
                val kind = NoteKind.entries.find { it.name == str(n, "kind") } ?: return@mapNotNull null
                MemoryNote(kind, str(n, "memoryId") ?: return@mapNotNull null, str(n, "undoToken"), str(n, "pendingId"))
            },
            usedMemory = (o["usedMemory"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            skillNotes = objects(o, "skillNotes").map { n -> SkillNote(str(n, "text").orEmpty(), str(n, "draftId"), str(n, "suspended")) },
            unconfirmed = (o["unconfirmed"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            result = field("result"),
            steers = (o["steers"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            interruptedReplies = (o["interruptedReplies"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() } ?: emptyList(),
            finishedReplies = (o["finishedReplies"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() } ?: emptyList(),
            groups = (o["groups"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            keptPage = field("keptPage"),
            keptPageClosed = field("keptPageClosed"),
        )
        return turn to (o["files"] as? JsonArray).orEmpty().mapNotNull(::fileOf)
    }

    private fun fileOf(e: kotlinx.serialization.json.JsonElement): SharedFile? {
        val o = e as? JsonObject ?: return null
        fun field(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        return SharedFile(field("name") ?: return null, field("size")?.toLongOrNull() ?: 0, field("savedAs") ?: return null, field("uri") ?: return null)
    }

    /**
     * 追加单轮消息。采用 .jsonl 文件的追加写优化，同时自动将旧版 .json 迁移为 .jsonl。
     */
    @Synchronized
    fun append(conversationId: String, turn: Turn, files: List<SharedFile> = emptyList()) {
        val sId = safe(conversationId)
        deleted -= sId
        dir.mkdirs()
        val jl = jsonlFile(conversationId)
        val j = jsonFile(conversationId)

        // 1. 若 .jsonl 不存在但旧 .json 存在：迁移旧记录到 .jsonl
        if (!jl.isFile && j.isFile) {
            val legacyRecords = readLegacyJson(j)
            if (legacyRecords == null) {
                // 旧文件损坏，重命名为 .corrupt-* 保留现场
                val corruptFile = File(dir, "${j.name}.corrupt-${System.currentTimeMillis()}")
                j.renameTo(corruptFile)
            } else {
                val tmp = File(dir, "${jl.name}.tmp")
                tmp.bufferedWriter(Charsets.UTF_8).use { writer ->
                    for ((t, fs) in legacyRecords) {
                        writer.write(recordToJson(t, fs).toString())
                        writer.newLine()
                    }
                }
                if (!tmp.renameTo(jl)) {
                    tmp.copyTo(jl, overwrite = true)
                    tmp.delete()
                }
                j.delete()
            }
        }

        // 2. 以追加模式写入新记录单行
        val line = recordToJson(turn, files).toString()
        FileOutputStream(jl, true).use { fos ->
            fos.write(line.toByteArray(Charsets.UTF_8))
            fos.write('\n'.code)
            fos.flush()
        }

        // 3. 更新索引条目
        val jlMod = jl.lastModified()
        val existing = indexEntries[sId]
        val firstText = existing?.firstUserText ?: run {
            records(conversationId).firstOrNull()?.first?.userText ?: turn.userText
        }
        val tf = titleFile(conversationId)
        val pf = pinFile(conversationId)
        val pgf = pageFile(conversationId)
        val t = existing?.title ?: title(conversationId)
        val pin = existing?.pinnedAt ?: pinnedAt(conversationId)
        val page = existing?.pageOrigin ?: pageOrigin(conversationId)

        indexEntries[sId] = IndexEntry(
            id = sId,
            firstUserText = firstText,
            title = t,
            updatedAt = jlMod,
            pinnedAt = pin,
            pageOrigin = page,
            fileModified = jlMod,
            titleModified = if (tf.isFile) tf.lastModified() else 0L,
            pinModified = if (pf.isFile) pf.lastModified() else 0L,
            pageModified = if (pgf.isFile) pgf.lastModified() else 0L,
        )
        persistIndex()
        updateSummariesState()
        _changes.tryEmit(conversationId)
    }

    /** 改运行编号为 [runId] 的那一轮(任务结束后补上判的结局、记忆与技能提示);找不到就不动。 */
    @Synchronized
    fun updateTurn(conversationId: String, runId: String, transform: (Turn) -> Turn) {
        val currentRecords = records(conversationId)
        if (currentRecords.isEmpty()) return
        if (currentRecords.none { it.first.runId == runId }) return
        val updated = currentRecords.map { (t, fs) ->
            (if (t.runId == runId) transform(t) else t) to fs
        }
        writeJsonl(conversationId, updated)
    }

    /** 记忆删了或全部清除时:去掉指向这些条目的提示和「用到」记录;[ids] 为 null 时去掉全部。 */
    @Synchronized
    fun stripMemory(ids: Set<String>?) {
        val files = dir.listFiles { f ->
            f.isFile && (f.name.endsWith(".jsonl") || f.name.endsWith(".json")) &&
                f.name != "index.json" && !f.name.endsWith(".tmp") && !f.name.contains(".corrupt-")
        }.orEmpty()

        val handledIds = mutableSetOf<String>()
        for (f in files) {
            val id = if (f.name.endsWith(".jsonl")) f.name.removeSuffix(".jsonl") else f.name.removeSuffix(".json")
            if (!handledIds.add(id)) continue
            val currentRecords = records(id)
            if (currentRecords.isEmpty()) continue
            fun keep(memId: String) = ids != null && memId !in ids
            val stripped = currentRecords.map { (t, fs) ->
                t.copy(
                    memoryNotes = t.memoryNotes.filter { keep(it.memoryId) },
                    usedMemory = t.usedMemory.filter(::keep),
                ) to fs
            }
            if (stripped != currentRecords) {
                writeJsonl(id, stripped)
            }
        }
    }

    private fun writeJsonl(conversationId: String, all: List<Pair<Turn, List<SharedFile>>>) {
        val sId = safe(conversationId)
        dir.mkdirs()
        val jl = jsonlFile(conversationId)
        val tmp = File(dir, "${jl.name}.tmp")
        tmp.bufferedWriter(Charsets.UTF_8).use { writer ->
            for ((t, fs) in all) {
                writer.write(recordToJson(t, fs).toString())
                writer.newLine()
            }
        }
        if (!tmp.renameTo(jl)) {
            tmp.copyTo(jl, overwrite = true)
            tmp.delete()
        }
        jsonFile(conversationId).takeIf { it.isFile }?.delete()

        val jlMod = jl.lastModified()
        val existing = indexEntries[sId]
        val firstText = all.firstOrNull()?.first?.userText ?: existing?.firstUserText
        val tf = titleFile(conversationId)
        val pf = pinFile(conversationId)
        val pgf = pageFile(conversationId)
        val t = existing?.title ?: title(conversationId)
        val pin = existing?.pinnedAt ?: pinnedAt(conversationId)
        val page = existing?.pageOrigin ?: pageOrigin(conversationId)

        indexEntries[sId] = IndexEntry(
            id = sId,
            firstUserText = firstText,
            title = t,
            updatedAt = jlMod,
            pinnedAt = pin,
            pageOrigin = page,
            fileModified = jlMod,
            titleModified = if (tf.isFile) tf.lastModified() else 0L,
            pinModified = if (pf.isFile) pf.lastModified() else 0L,
            pageModified = if (pgf.isFile) pgf.lastModified() else 0L,
        )
        persistIndex()
        updateSummariesState()
        _changes.tryEmit(conversationId)
    }

    private fun recordToJson(turn: Turn, files: List<SharedFile>): JsonObject = buildJsonObject {
        put("userText", turn.userText)
        put("outcome", turn.outcome)
        put("status", turn.status)
        turn.note?.let { put("note", it) }
        turn.runId?.let { put("runId", it) }
        turn.result?.let { put("result", it) }
        if (turn.memoryNotes.isNotEmpty()) put("memoryNotes", JsonArray(turn.memoryNotes.map { n ->
            buildJsonObject {
                put("kind", n.kind.name)
                put("memoryId", n.memoryId)
                n.undoToken?.let { put("undoToken", it) }
                n.pendingId?.let { put("pendingId", it) }
            }
        }))
        if (turn.usedMemory.isNotEmpty()) put("usedMemory", JsonArray(turn.usedMemory.map(::JsonPrimitive)))
        if (turn.unconfirmed.isNotEmpty()) put("unconfirmed", JsonArray(turn.unconfirmed.map(::JsonPrimitive)))
        if (turn.steers.isNotEmpty()) put("steers", JsonArray(turn.steers.map(::JsonPrimitive)))
        if (turn.interruptedReplies.any(String::isNotEmpty)) put("interruptedReplies", JsonArray(turn.interruptedReplies.map(::JsonPrimitive)))
        if (turn.finishedReplies.any(String::isNotEmpty)) put("finishedReplies", JsonArray(turn.finishedReplies.map(::JsonPrimitive)))
        if (turn.groups.isNotEmpty()) put("groups", JsonArray(turn.groups.map(::JsonPrimitive)))
        turn.keptPage?.let { put("keptPage", it) }
        turn.keptPageClosed?.let { put("keptPageClosed", it) }
        if (turn.skillNotes.isNotEmpty()) put("skillNotes", JsonArray(turn.skillNotes.map { n ->
            buildJsonObject {
                put("text", n.text)
                n.draftId?.let { put("draftId", it) }
                n.suspended?.let { put("suspended", it) }
            }
        }))
        if (files.isNotEmpty()) put("files", JsonArray(files.map { f ->
            buildJsonObject {
                put("name", f.name)
                put("size", f.size)
                put("savedAs", f.savedAs)
                put("uri", f.uri)
            }
        }))
    }

    private fun updateSummariesState() {
        val list = indexEntries.values
            .filter { it.firstUserText != null }
            .map { entry ->
                ConversationSummary(
                    id = entry.id,
                    title = entry.title ?: entry.firstUserText.orEmpty(),
                    updatedAt = entry.updatedAt,
                    pinnedAt = entry.pinnedAt,
                    page = entry.pageOrigin,
                )
            }
            .sortedByDescending { it.updatedAt }
        _summaries.value = list
    }

    private fun serializeIndex(entries: Collection<IndexEntry>): String {
        val arr = JsonArray(entries.map { entry ->
            buildJsonObject {
                put("id", entry.id)
                entry.firstUserText?.let { put("firstUserText", it) }
                entry.title?.let { put("title", it) }
                put("updatedAt", entry.updatedAt)
                entry.pinnedAt?.let { put("pinnedAt", it) }
                entry.pageOrigin?.let { p ->
                    put("pageOrigin", buildJsonObject {
                        put("url", p.url)
                        put("host", p.host)
                        put("title", p.title)
                    })
                }
                put("fileModified", entry.fileModified)
                put("titleModified", entry.titleModified)
                put("pinModified", entry.pinModified)
                put("pageModified", entry.pageModified)
            }
        })
        return buildJsonObject {
            put("version", 1)
            put("entries", arr)
        }.toString()
    }

    private fun parseIndex(text: String): Map<String, IndexEntry>? = runCatching {
        if (text.isEmpty()) return@runCatching null
        val root = Json.parseToJsonElement(text) as? JsonObject ?: return@runCatching null
        val entriesArray = root["entries"] as? JsonArray ?: return@runCatching null
        val map = mutableMapOf<String, IndexEntry>()
        for (elem in entriesArray) {
            val o = elem as? JsonObject ?: continue
            fun field(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun longField(k: String) = field(k)?.toLongOrNull()
            val id = field("id") ?: continue
            val pageObj = o["pageOrigin"] as? JsonObject
            val page = if (pageObj != null) {
                val u = (pageObj["url"] as? JsonPrimitive)?.contentOrNull
                val h = (pageObj["host"] as? JsonPrimitive)?.contentOrNull
                val t = (pageObj["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (u != null && h != null) PageOrigin(u, h, t) else null
            } else null
            map[id] = IndexEntry(
                id = id,
                firstUserText = field("firstUserText"),
                title = field("title"),
                updatedAt = longField("updatedAt") ?: 0L,
                pinnedAt = longField("pinnedAt"),
                pageOrigin = page,
                fileModified = longField("fileModified") ?: 0L,
                titleModified = longField("titleModified") ?: 0L,
                pinModified = longField("pinModified") ?: 0L,
                pageModified = longField("pageModified") ?: 0L,
            )
        }
        map
    }.getOrNull()

    private fun persistIndex() {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "index.json.tmp")
            tmp.writeText(serializeIndex(indexEntries.values))
            val target = indexFile()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun activeMainFile(id: String): File? {
        val jl = jsonlFile(id)
        if (jl.isFile) return jl
        val j = jsonFile(id)
        if (j.isFile) return j
        return null
    }

    private fun objects(o: JsonObject, k: String) = (o[k] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull

    companion object {
        /** App 进程里共用的那一份(读写都在它的锁里串行)。 */
        fun shared(context: Context): ConversationStore = (context.applicationContext as com.hana.agent.HanaApp).conversations

        /** `current` 文件里表示「新开还没说话的对话」的那个值(对话编号只会是字母数字、`-`、`_`,不会撞上)。 */
        private const val CURRENT_FRESH = "fresh"
    }

    // id 来自 newConversation 或外部传入,只留安全字符,免得拼出目录外的路径
    private fun jsonFile(id: String) = File(dir, "${safe(id)}.json")
    private fun jsonlFile(id: String) = File(dir, "${safe(id)}.jsonl")
    private fun indexFile() = File(dir, "index.json")
    private fun titleFile(id: String) = File(dir, "${safe(id)}.title")
    private fun pinFile(id: String) = File(dir, "${safe(id)}.pin")
    private fun pageFile(id: String) = File(dir, "${safe(id)}.page")

    /** 用户此刻在哪个对话,见 [CurrentChat];不是 `.json` 或 `.jsonl`,不进 [list]。 */
    private fun currentFile() = File(dir, "current")
    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
}

data class ConversationSummary(val id: String, val title: String, val updatedAt: Long, val pinnedAt: Long? = null, val page: PageOrigin? = null)

/**
 * 用户此刻在哪个对话:App 里新开、还没说过话的是 [Fresh],正在看的是 [Open];没有记录(没进过 Hana、旧版本)为 null。
 * 悬浮胶囊据此接着该接的那个,而不是按「哪个对话文件最后被写过」去猜——新开的对话还没有文件,猜出来的会是上一个。
 */
sealed interface CurrentChat {
    data object Fresh : CurrentChat

    data class Open(val id: String) : CurrentChat
}

/** 对话从哪一页开始:网址、主机名、页面标题(没有为空串)。 */
data class PageOrigin(val url: String, val host: String, val title: String = "")
