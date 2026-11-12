package com.hana.agent.core.memory

import com.hana.agent.core.assembly.toolDispatcher
import com.hana.agent.core.model.Part
import com.hana.agent.core.record.RunEvent
import com.hana.agent.core.replay.Anchors
import com.hana.agent.core.replay.Selector
import com.hana.agent.core.tool.FakeScreen
import com.hana.agent.core.tool.ToolDispatcher
import com.hana.agent.core.tool.execute
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TaskMemoryStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private fun sampleMemory(
        id: String = "tm_001",
        goal: String = "在淘宝搜索购买白色运动鞋",
        conclusion: String = "已找到3款高评分白色运动鞋并加入购物车",
        apps: List<String> = listOf("com.taobao.taobao"),
        domains: List<String> = listOf("taobao.com"),
        steps: List<TaskMemoryStep> = listOf(
            TaskMemoryStep(step = 1, tool = "phone_launch_app", action = "启动淘宝应用", note = "主界面"),
            TaskMemoryStep(step = 2, tool = "phone_type_text", action = "在搜索栏输入白色运动鞋", note = "搜索"),
            TaskMemoryStep(step = 3, tool = "phone_tap", action = "点击 [加入购物车]", note = "商详页"),
        ),
        timestamp: Long = 1000L,
    ) = TaskMemory(
        id = id,
        goal = goal,
        conclusion = conclusion,
        apps = apps,
        domains = domains,
        keySteps = steps,
        timestamp = timestamp,
        conversationId = "conv_123",
        success = true,
    )

    @Test
    fun testTaskMemoryJsonSerialization() {
        val original = sampleMemory()
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<TaskMemory>(serialized)

        assertEquals(original.id, deserialized.id)
        assertEquals(original.goal, deserialized.goal)
        assertEquals(original.conclusion, deserialized.conclusion)
        assertEquals(original.apps, deserialized.apps)
        assertEquals(original.domains, deserialized.domains)
        assertEquals(original.keySteps.size, deserialized.keySteps.size)
        assertEquals(original.keySteps[0].action, deserialized.keySteps[0].action)
        assertEquals(original.timestamp, deserialized.timestamp)
        assertTrue(deserialized.success)
    }

    @Test
    fun testStoreFilePersistenceAndReload() {
        val file = File(tempFolder.root, "task_memory.json")
        val store1 = TaskMemoryStore(file)

        val mem1 = sampleMemory(id = "tm_1", goal = "美团外卖点咖啡", timestamp = 2000L)
        val mem2 = sampleMemory(id = "tm_2", goal = "京东比价买机械键盘", timestamp = 3000L)

        store1.add(mem1)
        store1.add(mem2)

        assertEquals(2, store1.getAll().size)
        assertEquals(mem2, store1.get("tm_2"))

        // 新建实例并从文件读取，验证持久化一致性
        val store2 = TaskMemoryStore(file)
        assertEquals(2, store2.getAll().size)
        assertEquals("京东比价买机械键盘", store2.get("tm_2")?.goal)
        assertEquals("美团外卖点咖啡", store2.get("tm_1")?.goal)
    }

    @Test
    fun testStoreLimitEviction() {
        val file = File(tempFolder.root, "limited_store.json")
        val store = TaskMemoryStore(file, limit = 2)

        store.add(sampleMemory(id = "tm_1", timestamp = 1000L))
        store.add(sampleMemory(id = "tm_2", timestamp = 2000L))
        store.add(sampleMemory(id = "tm_3", timestamp = 3000L))

        assertEquals(2, store.getAll().size)
        assertNotNull(store.get("tm_3"))
        assertNotNull(store.get("tm_2"))
        assertNull(store.get("tm_1")) // 最旧的被淘汰
    }

    @Test
    fun testTimelinePagination() {
        val file = File(tempFolder.root, "timeline_store.json")
        val store = TaskMemoryStore(file)

        val m1 = sampleMemory(id = "tm_1", timestamp = 1000L)
        val m2 = sampleMemory(id = "tm_2", timestamp = 2000L)
        val m3 = sampleMemory(id = "tm_3", timestamp = 3000L)

        store.add(m1)
        store.add(m2)
        store.add(m3)

        val timelineAll = store.timeline(limit = 10)
        assertEquals(3, timelineAll.size)
        assertEquals("tm_3", timelineAll[0].id) // 最新排在最前
        assertEquals("tm_2", timelineAll[1].id)
        assertEquals("tm_1", timelineAll[2].id)

        // 分页游标测试：早于 3000L 的记录
        val page2 = store.timeline(limit = 10, beforeTimestamp = 3000L)
        assertEquals(2, page2.size)
        assertEquals("tm_2", page2[0].id)
        assertEquals("tm_1", page2[1].id)
    }

    @Test
    fun testTaskMemoryIndexSearch() {
        val m1 = sampleMemory(
            id = "tm_1",
            goal = "淘宝搜索耐克球鞋",
            conclusion = "找到耐克球鞋商品",
            apps = listOf("com.taobao.taobao"),
            steps = listOf(TaskMemoryStep(1, "phone_tap", "点击耐克球鞋")),
        )
        val m2 = sampleMemory(
            id = "tm_2",
            goal = "京东购买机械键盘比价",
            conclusion = "完成机械键盘比价并下单",
            apps = listOf("com.jingdong.app.mall"),
            steps = listOf(TaskMemoryStep(1, "phone_tap", "点击机械键盘")),
        )
        val m3 = sampleMemory(
            id = "tm_3",
            goal = "美团外卖点星巴克拿铁咖啡",
            conclusion = "已点星巴克拿铁咖啡送达",
            apps = listOf("com.sankuai.meituan"),
            steps = listOf(TaskMemoryStep(1, "phone_tap", "点击拿铁咖啡")),
        )

        val index = TaskMemoryIndex(listOf(m1, m2, m3))

        // 1. 意图关键词模糊搜索
        val searchShoe = index.search("球鞋")
        assertEquals(1, searchShoe.size)
        assertEquals("tm_1", searchShoe.first().id)

        // 2. 咖啡与饮品
        val searchCoffee = index.search("拿铁咖啡")
        assertEquals(1, searchCoffee.size)
        assertEquals("tm_3", searchCoffee.first().id)

        // 3. 限定 App 搜索
        val searchJd = index.search(query = "键盘", app = "com.jingdong.app.mall")
        assertEquals(1, searchJd.size)
        assertEquals("tm_2", searchJd.first().id)

        // 4. 指定了不相关的 App，即使命中 query 也不匹配
        val searchMismatchApp = index.search(query = "球鞋", app = "com.sankuai.meituan")
        assertTrue(searchMismatchApp.isEmpty())
    }

    @Test
    fun testTaskMemoryIndexActionTermRecall() {
        val step1 = TaskMemoryStep(step = 1, tool = "phone_tap", action = "点击领券中心领取优惠券")
        val step2 = TaskMemoryStep(step = 2, tool = "phone_type_text", action = "输入优惠码VIP888")
        val memory = sampleMemory(id = "tm_coupon", goal = "在电商购买衣服", steps = listOf(step1, step2))

        val index = TaskMemoryIndex(listOf(memory))

        // 通过操作词「领券」和「优惠码」模糊召回
        val hits = index.search("领券")
        assertEquals(1, hits.size)
        assertEquals("tm_coupon", hits.first().id)

        val hitsCode = index.search("优惠码")
        assertEquals(1, hitsCode.size)
        assertEquals("tm_coupon", hitsCode.first().id)
    }

    @Test
    fun testTaskMemoryRecorderExtractDone() {
        val file = File(tempFolder.root, "recorder_store.json")
        val store = TaskMemoryStore(file)
        val recorder = TaskMemoryRecorder(store)

        val anchor = Anchors(packageName = "com.taobao.taobao", texts = listOf("淘宝"))
        val events = listOf(
            RunEvent.TaskStarted(atMs = 100L, goal = "帮我买一双耐克球鞋", model = "hana-main"),
            RunEvent.ActionTarget(
                atMs = 200L,
                step = 1,
                tool = "phone_launch_app",
                argsJson = "{\"package\":\"com.taobao.taobao\"}",
                target = Selector(role = "button", name = "淘宝", resourceId = null, bounds = listOf(0, 0, 100, 100), ordinal = 0),
                before = anchor,
                after = anchor,
                effective = true,
                incomplete = false,
                protected = false,
                callId = "c1",
            ),
            RunEvent.ActionTarget(
                atMs = 300L,
                step = 2,
                tool = "phone_type_text",
                argsJson = "{\"text\":\"耐克球鞋\"}",
                target = Selector(role = "edit", name = "搜索框", resourceId = null, bounds = listOf(10, 10, 200, 50), ordinal = 0),
                before = anchor,
                after = anchor,
                effective = true,
                incomplete = false,
                protected = false,
                callId = "c2",
            ),
            RunEvent.ActionTarget(
                atMs = 400L,
                step = 3,
                tool = "phone_tap",
                argsJson = "{}",
                target = Selector(role = "button", name = "搜索按钮", resourceId = null, bounds = listOf(210, 10, 260, 50), ordinal = 0),
                before = anchor,
                after = anchor,
                effective = true,
                incomplete = false,
                protected = false,
                callId = "c3",
            ),
            RunEvent.TaskEnded(atMs = 500L, outcome = "succeeded", answerOrReason = "已为您搜索耐克球鞋并选定款式"),
        )

        val recorded = recorder.recordTaskDone(
            goal = "帮我买一双耐克球鞋",
            conclusion = "已为您搜索耐克球鞋并选定款式",
            events = events,
            conversationId = "conv_test_1",
            timestamp = 500L,
            success = true,
        )

        assertNotNull(recorded)
        assertEquals("帮我买一双耐克球鞋", recorded!!.goal)
        assertEquals("已为您搜索耐克球鞋并选定款式", recorded.conclusion)
        assertEquals(listOf("com.taobao.taobao"), recorded.apps)
        assertEquals(3, recorded.keySteps.size)
        assertEquals("启动应用 com.taobao.taobao", recorded.keySteps[0].action)
        assertEquals("在 [搜索框] 输入 \"耐克球鞋\"", recorded.keySteps[1].action)
        assertEquals("点击 [搜索按钮]", recorded.keySteps[2].action)

        // 验证已自动入库且可从 store 中检索
        val retrieved = store.get(recorded.id)
        assertNotNull(retrieved)
        assertEquals(recorded.id, retrieved!!.id)

        val searchResult = store.search("耐克球鞋")
        assertEquals(1, searchResult.size)
        assertEquals(recorded.id, searchResult.first().id)
    }

    private fun ToolDispatcher.callTool(name: String, args: JsonObject) = runBlocking {
        execute(Part.ToolCall("call_1", name, args.toString())).content
    }

    @Test
    fun testThreeTierSearchToolsMountedInDispatcher() {
        val file = File(tempFolder.root, "tools_store.json")
        val store = TaskMemoryStore(file)

        val m1 = sampleMemory(
            id = "tm_task_1",
            goal = "在饿了么点奶茶",
            conclusion = "成功下单一杯中杯四季春奶茶少糖",
            apps = listOf("me.ele"),
            domains = emptyList(),
            steps = listOf(
                TaskMemoryStep(step = 1, tool = "phone_launch_app", action = "启动饿了么"),
                TaskMemoryStep(step = 2, tool = "phone_tap", action = "点击四季春奶茶"),
                TaskMemoryStep(step = 3, tool = "phone_tap", action = "点击结算"),
            ),
            timestamp = 1500L,
        )
        store.add(m1)

        // 1. 未装载 taskMemories 时，工具不提供
        val dispatcherWithoutMemory = toolDispatcher(FakeScreen())
        val searchUnavailable = dispatcherWithoutMemory.callTool("task_memory_search", buildJsonObject { put("query", "奶茶") })
        // 未提供的工具调用会被拒绝
        assertFalse(searchUnavailable["ok"]?.jsonPrimitive?.content == "true")

        // 2. 装载 taskMemories 时，三层工具可用
        val dispatcher = toolDispatcher(FakeScreen(), taskMemories = store)

        // 第一层：task_memory_search
        val searchRes = dispatcher.callTool("task_memory_search", buildJsonObject {
            put("query", "奶茶")
        })
        assertTrue(searchRes["ok"]?.jsonPrimitive?.content == "true")
        val tasks = searchRes["tasks"]?.jsonArray
        assertNotNull(tasks)
        assertEquals(1, tasks!!.size)
        val firstTask = tasks[0].jsonObject
        assertEquals("tm_task_1", firstTask["id"]?.jsonPrimitive?.content)
        assertEquals("在饿了么点奶茶", firstTask["goal"]?.jsonPrimitive?.content)
        assertEquals("成功下单一杯中杯四季春奶茶少糖", firstTask["conclusion"]?.jsonPrimitive?.content)

        // 第二层：task_memory_step 展开关键步骤
        val stepRes = dispatcher.callTool("task_memory_step", buildJsonObject {
            put("id", "tm_task_1")
        })
        assertTrue(stepRes["ok"]?.jsonPrimitive?.content == "true")
        val steps = stepRes["steps"]?.jsonArray
        assertNotNull(steps)
        assertEquals(3, steps!!.size)
        assertEquals("启动饿了么", steps[0].jsonObject["action"]?.jsonPrimitive?.content)
        assertEquals("点击四季春奶茶", steps[1].jsonObject["action"]?.jsonPrimitive?.content)
        assertEquals("点击结算", steps[2].jsonObject["action"]?.jsonPrimitive?.content)

        // 查询不存在的 id 时报 not_found
        val stepNotFound = dispatcher.callTool("task_memory_step", buildJsonObject {
            put("id", "tm_non_existent")
        })
        assertFalse(stepNotFound["ok"]?.jsonPrimitive?.content == "true")
        assertEquals("not_found", stepNotFound["error"]?.jsonPrimitive?.content)

        // 第三层：task_memory_timeline 浏览时间线足迹
        val timelineRes = dispatcher.callTool("task_memory_timeline", buildJsonObject {
            put("limit", 5)
        })
        assertTrue(timelineRes["ok"]?.jsonPrimitive?.content == "true")
        val timelineList = timelineRes["timeline"]?.jsonArray
        assertNotNull(timelineList)
        assertEquals(1, timelineList!!.size)
        assertEquals("tm_task_1", timelineList[0].jsonObject["id"]?.jsonPrimitive?.content)
    }
}
