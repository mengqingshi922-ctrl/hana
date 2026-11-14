package com.hana.agent.core.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 [GuideCatalog] 的字符匹配评分与 App 知识流程补充机制:
 * 1. 单个 ASCII 字符不会滥匹配包含该字母的英文/拼音词条 (除非精确全等);
 * 2. [GuideCatalog.supplementForPackage] 能够按包名/显示名补充不在已注入列表中的 App 知识.
 */
class GuideCatalogMatchTest {

    private val catalog by lazy { GuideCatalog.loadBundled() }

    @Test
    fun singleAsciiCharDoesNotMatchContainingNamesUnlessExact() {
        // 单个 ASCII 字符 "a" 不应命中包含字母 a 的所有英文 App (如 taobao, alipay, bilibili 等)
        val hitsForA = catalog.find("a", kind = KnowledgeIndex.APP, minScore = 60)
        // 应该没有任何名字为 "a" 的 App，因此命中列表应为空
        assertTrue("单个 ASCII 字符 'a' 不应命中如 taobao 等 App", hitsForA.none { it.name != "a" })

        // 两个及以上字符的英文前缀或全称可以正常命中
        val hitsForJd = catalog.find("jd", kind = KnowledgeIndex.APP, minScore = 60)
        assertTrue("jd 应能命中京东", hitsForJd.any { it.name == "jd" })

        // 单个非 ASCII (中文) 字符依然可以正常检索
        val hitsForB = catalog.find("哔", kind = KnowledgeIndex.APP, minScore = 60)
        assertTrue("中文字符 '哔' 应能命中哔哩哔哩", hitsForB.any { it.name == "bilibili" })
    }

    @Test
    fun supplementForPackageLoadsKnowledgeIfNotAlreadyInjected() {
        val already = setOf("bilibili", "alipay")

        // 1. 查找小黑盒 (不在 already 中)
        val foundByPackage = catalog.supplementForPackage("com.max.xiaoheihe", already)
        assertNotNull("按小黑盒包名应能找到补充知识", foundByPackage)
        assertEquals("xiaoheihe", foundByPackage?.info?.name)
        assertTrue("正文附注应包含常用流程补注", foundByPackage?.content?.contains("常用流程补注") == true)

        val foundByDisplay = catalog.supplementForPackage("小黑盒", already)
        assertNotNull("按显示名应能找到小黑盒", foundByDisplay)

        // 2. 已经在 alreadyInjected 中，返回 null
        val alreadyWithXhh = already + "xiaoheihe"
        val ignored = catalog.supplementForPackage("com.max.xiaoheihe", alreadyWithXhh)
        assertNull("已在 injected 集合中应返回 null", ignored)

        // 3. 不存在的包名返回 null
        val notFound = catalog.supplementForPackage("com.unknown.fake.app", already)
        assertNull("未知包名应返回 null", notFound)
    }
}
