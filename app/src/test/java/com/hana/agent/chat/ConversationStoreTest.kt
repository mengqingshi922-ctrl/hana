package com.hana.agent.chat

import com.hana.agent.core.agent.Turn
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConversationStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun persistsAcrossInstances() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("查天气", "晴", "succeeded"))
        a.append(id, Turn("订票", "验证码\n\"过不去\"", "failed"))
        val b = ConversationStore(dir)
        assertEquals(listOf(Turn("查天气", "晴", "succeeded"), Turn("订票", "验证码\n\"过不去\"", "failed")), b.turns(id))
        assertEquals(emptyList<Turn>(), b.turns(b.newConversation()))
        assertNotEquals(id, b.newConversation())
    }

    /** 留在后台运行的那一页(站点)和它后来关掉的原因跟着那一轮存下;没留页的轮次不写这两个字段。 */
    @Test fun persistsKeptPage() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("播放课程,挂着别关", "开始播放了", "succeeded", runId = "r1", keptPage = "course.example.com"))
        a.append(id, Turn("谢谢", "不客气", "succeeded", runId = "r2"))
        assertEquals(listOf("course.example.com", null), ConversationStore(dir).turns(id).map { it.keptPage })
        a.updateTurn(id, "r1") { it.copy(keptPageClosed = "expired") }
        assertEquals("expired", ConversationStore(dir).turns(id).first().keptPageClosed)
        assertFalse("keptPage" in File(dir, "$id.jsonl").readText().substringAfter("谢谢"))
    }

    /** 这一轮用过的能力组跟着存下(下一轮开局据此预先展开);没用过的不写这个字段。 */
    @Test fun persistsUsedGroups() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("打开设置", "好了", "succeeded", groups = listOf("phone", "web")))
        a.append(id, Turn("谢谢", "不客气", "succeeded"))
        assertEquals(listOf(listOf("phone", "web"), emptyList()), ConversationStore(dir).turns(id).map { it.groups })
        assertFalse("groups" in File(dir, "$id.jsonl").readText().substringAfter("谢谢"))
    }

    /** 插话打断的那段回复和插话一一对应存下;没打断过的轮次不写这个字段,旧文件读出来是空的。 */
    @Test fun persistsInterruptedReplies() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("查天气", "明天多云", "succeeded", steers = listOf("改成查明天", "顺便看看后天"), interruptedReplies = listOf("今天晴", "")))
        a.append(id, Turn("谢谢", "不客气", "succeeded", steers = listOf("好")))
        val read = ConversationStore(dir).turns(id)
        assertEquals(listOf(listOf("今天晴", ""), emptyList()), read.map { it.interruptedReplies })
        assertEquals(listOf("改成查明天", "顺便看看后天"), read.first().steers)
        assertFalse("interruptedReplies" in File(dir, "$id.jsonl").readText().substringAfter("谢谢"))
    }

    /** 插话之前已经说完的回复同样一一对应存下;没有时不写这个字段。 */
    @Test fun persistsFinishedReplies() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("查天气", "明天多云", "succeeded", steers = listOf("改成查明天"), finishedReplies = listOf("今天晴,最高 25 度。")))
        a.append(id, Turn("谢谢", "不客气", "succeeded", steers = listOf("好")))
        assertEquals(listOf(listOf("今天晴,最高 25 度。"), emptyList()), ConversationStore(dir).turns(id).map { it.finishedReplies })
        assertFalse("finishedReplies" in File(dir, "$id.jsonl").readText().substringAfter("谢谢"))
    }

    /** 删对话:对话文件、标题、挪到一边的损坏文件、临时文件都删掉,列表里没有了,别的对话不动;删完发一次改动。 */
    @Test fun deleteRemovesTheConversationAndItsSidecars() = kotlinx.coroutines.runBlocking {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val id = store.newConversation()
        val other = store.newConversation()
        store.append(id, Turn("查天气", "晴", "succeeded"))
        store.setTitle(id, "天气")
        store.setPinned(id, true, at = 5L)
        File(dir, "$id.json.corrupt-1").writeText("x")
        File(dir, "$id.jsonl.tmp").writeText("x")
        store.append(other, Turn("订票", "好了", "succeeded"))
        val changes = mutableListOf<String>()
        val deletions = mutableListOf<String>()
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { store.changes.collect { changes += it } }
        val gone = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { store.deletions.collect { deletions += it } }
        store.delete(id)
        kotlinx.coroutines.yield()
        job.cancel()
        gone.cancel()
        assertEquals(listOf(id), changes)
        assertEquals(listOf(id), deletions)
        assertEquals(setOf("$other.jsonl", "index.json"), dir.list()!!.toSet())
        assertEquals(listOf(other), store.list().map { it.id })
        assertEquals(emptyList<Turn>(), store.turns(id))
        assertEquals(null, store.title(id))
        assertEquals(null, store.pinnedAt(id))
    }

    /** 用户改的名字说了算:后台起标题晚到也不盖掉;起好了的标题用户照样能改;空的不改。 */
    @Test fun renameByUserWinsOverAutoTitle() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val id = store.newConversation()
        store.append(id, Turn("查一下明天上海的天气", "晴", "succeeded"))
        assertTrue(store.rename(id, "  天气  "))
        assertFalse(store.setTitle(id, "上海天气"))
        assertEquals("天气", ConversationStore(dir).title(id))

        val other = store.newConversation()
        store.append(other, Turn("订票", "好了", "succeeded"))
        assertTrue(store.setTitle(other, "订高铁票"))
        assertTrue(store.rename(other, "回家的票"))
        assertFalse(store.rename(other, "   "))
        assertEquals("回家的票", store.title(other))
        assertEquals(setOf("天气", "回家的票"), store.list().map { it.title }.toSet())

        store.delete(other)
        assertFalse(store.rename(other, "再改"))
    }

    /** 置顶记在 `.pin` 里(置顶的时间),重开还在;列表带上置顶时间,不改对话的先后;取消置顶就没了。 */
    @Test fun pinPersistsAndShowsInTheList() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        store.append("a", Turn("一", "好", "succeeded"))
        store.append("b", Turn("二", "好", "succeeded"))
        assertTrue(store.setPinned("a", true, at = 123L))
        val again = ConversationStore(dir)
        assertEquals(123L, again.pinnedAt("a"))
        assertEquals(null, again.pinnedAt("b"))
        assertEquals(mapOf("a" to 123L, "b" to null), again.list().associate { it.id to it.pinnedAt })
        assertTrue(again.setPinned("a", false))
        assertEquals(null, again.pinnedAt("a"))
        assertFalse(File(dir, "a.pin").exists())
        // 删掉的对话不再置顶回来
        again.delete("b")
        assertFalse(again.setPinned("b", true))
    }

    /** 删之后才到的补写(后台起好的标题、收尾后补的结局)不把对话写回来;再存进一轮就是重新用这个编号。 */
    @Test fun lateWritesDoNotReviveADeletedConversation() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val id = store.newConversation()
        store.append(id, Turn("查天气", "晴", "succeeded", runId = "r1"))
        store.delete(id)
        assertFalse(store.setTitle(id, "天气"))
        store.updateTurn(id, "r1") { it.copy(outcome = "补上的") }
        assertTrue(dir.list()!!.none { it.startsWith(id) })

        store.append(id, Turn("再来", "好", "succeeded"))
        assertTrue(store.setTitle(id, "再来"))
        assertEquals(listOf("再来"), store.list().map { it.title })
    }

    @Test fun corruptFileReadsAsEmpty() {
        val dir = tmp.newFolder("c")
        val s = ConversationStore(dir)
        File(dir, "x.json").writeText("{not json")
        assertEquals(emptyList<Turn>(), s.turns("x"))
        s.append("x", Turn("a", "b", "cancelled"))
        assertEquals(1, s.turns("x").size)
    }

    @Test fun corruptFileIsMovedAsideNotOverwritten() {
        val dir = tmp.newFolder("c")
        val s = ConversationStore(dir)
        File(dir, "x.json").writeText("{not json")
        s.append("x", Turn("a", "b", "cancelled"))
        val aside = dir.listFiles().orEmpty().filter { it.name.startsWith("x.json.corrupt-") }
        assertEquals(1, aside.size)
        assertEquals("{not json", aside.single().readText())
        assertEquals(listOf(Turn("a", "b", "cancelled")), s.turns("x"))
        assertEquals(listOf("x"), s.list().map { it.id })
    }

    @Test fun listsConversationsNewestFirst() {
        val dir = tmp.newFolder("c")
        val s = ConversationStore(dir)
        val a = s.newConversation()
        val b = s.newConversation()
        s.append(a, Turn("查天气", "晴", "succeeded"))
        s.append(b, Turn("订票", "好了", "succeeded"))
        s.append(b, Turn("改签", "好了", "succeeded"))
        File(dir, "$a.jsonl").setLastModified(1_000)
        File(dir, "$b.jsonl").setLastModified(2_000)
        File(dir, "broken.json").writeText("{")
        assertEquals(listOf(b to "订票", a to "查天气"), s.list().map { it.id to it.title })
    }

    @Test fun unverifiedTurnKeepsItsNote() {
        val dir = tmp.newFolder()
        val id = ConversationStore(dir).let { s -> s.newConversation().also { s.append(it, Turn("查余额", "余额 10 元", "unverified", note = "防截屏")) } }
        assertEquals(listOf(Turn("查余额", "余额 10 元", "unverified", note = "防截屏")), ConversationStore(dir).turns(id))
    }

    @Test fun turnKeepsItsRunId() {
        val dir = tmp.newFolder()
        val s = ConversationStore(dir)
        val id = s.newConversation()
        s.append(id, Turn("查天气", "晴", "succeeded", runId = "20261023-120000-abc123"))
        s.append(id, Turn("旧的", "好", "succeeded"))
        assertEquals(listOf("20261023-120000-abc123", null), ConversationStore(dir).turns(id).map { it.runId })
    }

    @Test fun memoryNotesStoredWithoutContentAndStripped() {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val note = com.hana.agent.core.memory.MemoryNote(com.hana.agent.core.memory.NoteKind.added, "m1", "t1", null)
        val other = com.hana.agent.core.memory.MemoryNote(com.hana.agent.core.memory.NoteKind.pending, "m2", "t2", "m2")
        store.append("c1", Turn("点外卖", "ok", "succeeded", runId = "r1"))
        store.updateTurn("c1", "r1") { it.copy(memoryNotes = listOf(note, other), usedMemory = listOf("m1", "m3")) }
        val raw = File(dir, "c1.jsonl").readText()
        assertTrue(raw.contains("\"memoryId\":\"m1\""))
        assertFalse(raw.contains("text"))
        assertEquals(listOf(note, other), ConversationStore(dir).turns("c1").single().memoryNotes)
        store.stripMemory(setOf("m1"))
        val t = ConversationStore(dir).turns("c1").single()
        assertEquals(listOf(other), t.memoryNotes)
        assertEquals(listOf("m3"), t.usedMemory)
        store.stripMemory(null)
        assertTrue(ConversationStore(dir).turns("c1").single().memoryNotes.isEmpty())
    }

    @Test fun filesKeptPerTurn() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        val f = SharedFile("图.png", 1234, "Download/Hana/图.png", "content://media/external/downloads/7")
        a.append(id, Turn("画图", "好了", "succeeded"), listOf(f))
        a.append(id, Turn("再来", "好", "succeeded"))
        val b = ConversationStore(dir)
        assertEquals(listOf(listOf(f), emptyList()), b.records(id).map { it.second })
        assertEquals(2, b.turns(id).size)
    }

    // 任务结束后补记忆提示、删记忆时,这一轮的文件卡片不能丢
    @Test fun filesSurviveNoteUpdatesAndStrip() {
        val dir = tmp.newFolder("d")
        val store = ConversationStore(dir)
        val f = SharedFile("表.csv", 10, "Download/Hana/表.csv", "content://media/external/downloads/8")
        store.append("c1", Turn("导出", "好了", "succeeded", runId = "r1"), listOf(f))
        val note = com.hana.agent.core.memory.MemoryNote(com.hana.agent.core.memory.NoteKind.added, "m1", "t1", null)
        store.updateTurn("c1", "r1") { it.copy(memoryNotes = listOf(note)) }
        store.stripMemory(null)
        val r = ConversationStore(dir).records("c1").single()
        assertEquals(listOf(f), r.second)
        assertTrue(r.first.memoryNotes.isEmpty())
    }

    // 写过哪个对话就发出它的编号:存进一轮、改一轮、去掉记忆提示
    @Test fun announcesChangedConversations() = kotlinx.coroutines.test.runTest {
        val s = ConversationStore(tmp.newFolder("c"))
        val seen = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { s.changes.collect { seen += it } }
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { s.deletions.collect { deleted += it } }
        s.append("a", Turn("查天气", "晴", "succeeded", runId = "r1", usedMemory = listOf("m1")))
        s.append("b", Turn("订票", "好了", "succeeded"))
        s.updateTurn("a", "r1") { it.copy(outcome = "晴转多云") }
        s.updateTurn("a", "missing") { it }
        s.stripMemory(setOf("m1"))
        assertEquals(listOf("a", "b", "a", "a"), seen)
        // 只有删对话才发 deletions
        assertEquals(emptyList<String>(), deleted)
    }

    /** 从哪一页开始的对话:记在旁边的 .page 文件里,列表里带着;旧对话没有它照常读;删对话一起删掉。 */
    @Test fun pageOriginPersistsAndIsOptional() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val plain = a.newConversation()
        a.append(plain, Turn("查天气", "晴", "succeeded"))
        val paged = a.newConversation()
        assertTrue(a.setPageOrigin(paged, PageOrigin("https://shop.example.com/s?k=1", "shop.example.com", "搜索")))
        a.append(paged, Turn("挑一个", "好", "succeeded"))
        val b = ConversationStore(dir)
        assertEquals(PageOrigin("https://shop.example.com/s?k=1", "shop.example.com", "搜索"), b.pageOrigin(paged))
        assertEquals(null, b.pageOrigin(plain))
        val listed = b.list().associateBy { it.id }
        assertEquals("shop.example.com", listed.getValue(paged).page?.host)
        assertEquals(null, listed.getValue(plain).page)
        // 对话文件的格式不变:没有多出来的字段
        assertFalse(File(dir, "$paged.jsonl").readText().contains("shop.example.com"))
        // 坏了的 .page 当作没有
        File(dir, "$plain.page").writeText("{not json")
        assertEquals(null, b.pageOrigin(plain))
        b.delete(paged)
        assertFalse(File(dir, "$paged.page").exists())
        assertFalse(b.setPageOrigin(paged, PageOrigin("https://a.example/", "a.example")))
    }

    /**
     * 「用户此刻在哪个对话」也记下来:进程重启后胶囊据此接着那个对话,而不是靠文件时间猜最近的;
     * 新开还没说话的对话记成 [CurrentChat.Fresh];删掉它时一起清掉;这个文件不进对话列表。
     */
    @Test fun remembersTheConversationOnScreen() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("查天气", "晴", "succeeded"))
        assertEquals(null, a.currentChat())

        a.setCurrent(CurrentChat.Fresh)
        assertEquals(CurrentChat.Fresh, a.currentChat())
        // 新开的对话还没有对话文件,重启后照样读得回来
        assertEquals(CurrentChat.Fresh, ConversationStore(dir).currentChat())
        assertEquals(listOf(id), ConversationStore(dir).list().map { it.id })

        a.setCurrent(CurrentChat.Open(id))
        assertEquals(CurrentChat.Open(id), ConversationStore(dir).currentChat())
        assertEquals(CurrentChat.Open(id), ConversationStore(dir).current.value)
        // 记在旁边的 current 文件里,不动对话文件、不进列表
        assertEquals(setOf("$id.jsonl", "current", "index.json"), dir.list()!!.toSet())
        assertEquals(listOf(id), a.list().map { it.id })

        // 删掉这个对话:指向它的记录一起清掉,胶囊回落到最近那个
        a.delete(id)
        assertEquals(null, ConversationStore(dir).currentChat())
    }

    @Test
    fun benchmarkConversationStoreIO() {
        val benchDir = tmp.newFolder("bench")
        val store = ConversationStore(benchDir)
        for (i in 1..50) {
            val id = "conv_$i"
            File(benchDir, "$id.json").writeText("""[{"userText":"测试会话$i","outcome":"ok","status":"succeeded"}]""")
            File(benchDir, "$id.title").writeText("标题 $i")
            File(benchDir, "$id.pin").writeText("1731218000000")
            File(benchDir, "$id.page").writeText("""{"url":"https://example.com","host":"example.com","title":"测试"}""")
        }

        // 首次加载预热并建立索引缓存
        val t0 = System.nanoTime()
        val firstList = store.list()
        val warmTimeMs = (System.nanoTime() - t0) / 1_000_000.0
        assertEquals(50, firstList.size)

        // 第二次加载命中内存索引缓存，验证极速响应 (< 10ms)
        val t1 = System.nanoTime()
        val cachedList = store.list()
        val cachedTimeMs = (System.nanoTime() - t1) / 1_000_000.0
        assertEquals(50, cachedList.size)
        assertTrue("Cached list should be ultra fast (< 10ms), was ${cachedTimeMs}ms", cachedTimeMs < 10.0)
    }

    /**
     * 验证旧版 .json 格式对话文件能够无缝读取并在 append 后自动平滑迁移至 .jsonl。
     */
    @Test
    fun migratesLegacyJsonToJsonlOnAppend() {
        val dir = tmp.newFolder("legacy")
        val id = "legacy_conv"
        val legacyFile = File(dir, "$id.json")
        val jsonlFile = File(dir, "$id.jsonl")

        // 手动构造旧版 JSON 数组文件
        val legacyJson = """
            [
                {"userText":"第一句","outcome":"结果1","status":"succeeded"},
                {"userText":"第二句","outcome":"结果2","status":"succeeded"}
            ]
        """.trimIndent()
        legacyFile.writeText(legacyJson)

        val store = ConversationStore(dir)
        // 1. 验证旧文件在尚未迁移前可正常完整读取
        val turns = store.turns(id)
        assertEquals(2, turns.size)
        assertEquals("第一句", turns[0].userText)
        assertEquals("第二句", turns[1].userText)
        assertTrue(legacyFile.exists())
        assertFalse(jsonlFile.exists())

        // 2. 追加新轮次，触发迁移至 .jsonl
        store.append(id, Turn("第三句", "结果3", "succeeded"))

        // 3. 验证旧 .json 文件已被删除，.jsonl 已建立
        assertFalse(legacyFile.exists())
        assertTrue(jsonlFile.exists())

        // 4. 验证全部 3 条历史无缝串联保留
        val allTurns = store.turns(id)
        assertEquals(3, allTurns.size)
        assertEquals(listOf("第一句", "第二句", "第三句"), allTurns.map { it.userText })
    }

    /**
     * 验证新建 ConversationStore(dir) 实例模拟冷启动时能直接从 index.json 恢复完整摘要，
     * 并且经过多次追加后元数据（标题、置顶、页面来源）不丢失。
     */
    @Test
    fun coldStartRecoversFromIndexJson() {
        val dir = tmp.newFolder("cold_start")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("你好", "你好呀", "succeeded"))
        a.setTitle(id, "打招呼")
        a.setPinned(id, true, at = 9999L)
        a.setPageOrigin(id, PageOrigin("https://example.com/hello", "example.com", "首页"))

        assertTrue(File(dir, "index.json").exists())

        // 新建实例模拟冷启动
        val b = ConversationStore(dir)
        val list = b.list()
        assertEquals(1, list.size)
        val summary = list.first()
        assertEquals(id, summary.id)
        assertEquals("打招呼", summary.title)
        assertEquals(9999L, summary.pinnedAt)
        assertEquals("example.com", summary.page?.host)

        // 多次追加写，验证元数据不被冲刷丢失
        b.append(id, Turn("吃了么", "吃了", "succeeded"))
        b.append(id, Turn("再见", "拜拜", "succeeded"))

        val c = ConversationStore(dir)
        val listAfter = c.list()
        assertEquals(1, listAfter.size)
        val summaryAfter = listAfter.first()
        assertEquals("打招呼", summaryAfter.title)
        assertEquals(9999L, summaryAfter.pinnedAt)
        assertEquals("example.com", summaryAfter.page?.host)
        assertEquals(3, c.turns(id).size)
    }

    /**
     * 验证 summaries StateFlow 与 list() 在各种变更操作下均保持完全一致。
     */
    @Test
    fun summariesStateFlowKeepsInSyncWithList() = kotlinx.coroutines.test.runTest {
        val dir = tmp.newFolder("sync")
        val store = ConversationStore(dir)
        val id1 = store.newConversation()
        val id2 = store.newConversation()

        assertEquals(emptyList<ConversationSummary>(), store.summaries.value)
        assertEquals(store.list(), store.summaries.value)

        store.append(id1, Turn("问天气", "晴", "succeeded"))
        assertEquals(store.list(), store.summaries.value)
        assertEquals(1, store.summaries.value.size)

        store.append(id2, Turn("订外卖", "好了", "succeeded"))
        assertEquals(store.list(), store.summaries.value)
        assertEquals(2, store.summaries.value.size)

        store.setTitle(id1, "天气预报")
        assertEquals(store.list(), store.summaries.value)
        assertEquals("天气预报", store.summaries.value.find { it.id == id1 }?.title)

        store.setPinned(id1, true, at = 888L)
        assertEquals(store.list(), store.summaries.value)
        assertEquals(888L, store.summaries.value.find { it.id == id1 }?.pinnedAt)

        store.setPageOrigin(id2, PageOrigin("https://food.com", "food.com", "美食"))
        assertEquals(store.list(), store.summaries.value)
        assertEquals("food.com", store.summaries.value.find { it.id == id2 }?.page?.host)

        store.delete(id1)
        assertEquals(store.list(), store.summaries.value)
        assertEquals(1, store.summaries.value.size)
        assertEquals(id2, store.summaries.value.single().id)
    }
}
