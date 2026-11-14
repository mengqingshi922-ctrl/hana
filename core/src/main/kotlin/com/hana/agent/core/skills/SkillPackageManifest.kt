package com.hana.agent.core.skills

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 标准化 Hana 技能包清单与分享模型（受 Operit 体系启发）。
 *
 * 用于跨设备分享、导入和导出预置或社区贡献的自动化技能包：
 * - 包含 SHA-256 数据完整性校验；
 * - 外部导入强制置为 [SkillStatus.trial] 试用态；
 * - 导入时强制走 [SkillRedactor] 脱敏清洗，防止携带敏感信息或越权配置。
 *
 * @property formatVersion 格式规范版本号（默认 1）
 * @property id 技能包唯一包 ID（如 "com.hana.skill.express_track"）
 * @property name 技能系统内部唯一标识符（符合连字符小写命名规范）
 * @property display 技能面向用户的中文展示名称
 * @property author 技能包作者或发布来源标识
 * @property sha256 技能包正文 [markdownBody] 的 SHA-256 摘要签名
 * @property apps 关联的应用包名列表
 * @property markdownBody 包含步骤与说明的 SKILL.md Markdown 全文正文
 * @property createdAt 技能包打包时间戳（毫秒）
 */
@Serializable
data class HanaSkillPackage(
    val formatVersion: Int = 1,
    val id: String,
    val name: String,
    val display: String,
    val author: String = "community",
    val sha256: String,
    val apps: List<String> = emptyList(),
    val markdownBody: String,
    val createdAt: Long = System.currentTimeMillis()
) {

    /**
     * 校验技能包正文数据完整性。
     *
     * @return true 表示哈希校验通过，内容未被篡改；false 表示校验失败
     */
    fun verifyIntegrity(): Boolean {
        val calculated = PersonalSkills.fingerprint(markdownBody)
        return sha256.equals(calculated, ignoreCase = true)
    }

    /**
     * 将外部技能包安全转换为待验证的本地个人技能 [PersonalSkill]。
     *
     * 安全规则：
     * 1. 外部导入的技能强制进入 [SkillStatus.trial] 试用态，必须经过用户实际办成事后方可转正；
     * 2. 强制经过 [SkillRedactor] 过滤潜在的敏感数字、金额与私密文本；
     * 3. 规范化 Frontmatter 元数据头。
     */
    fun toTrialSkill(): PersonalSkill {
        // 1. 调用 SkillRedactor 清洗可能的敏感字面量与长数字串
        val sanitized = SkillRedactor.scrubNumbers(markdownBody, keepDigits = emptySet())

        // 2. 规范化 Frontmatter 字段
        var formattedMd = PersonalSkills.withName(sanitized, name)
        formattedMd = PersonalSkills.withField(formattedMd, "display", display)
        formattedMd = PersonalSkills.withField(formattedMd, "package_id", id)
        formattedMd = PersonalSkills.withField(formattedMd, "author", author)

        val cleanHash = PersonalSkills.fingerprint(formattedMd)

        return PersonalSkill(
            name = name,
            markdown = formattedMd,
            status = SkillStatus.trial,
            learnedFrom = "package:$id",
            replacesDraftOf = null,
            createdAt = System.currentTimeMillis(),
            outcomes = emptyList(),
            baseHash = cleanHash,
            source = SOURCE_PACKAGE_IMPORT
        )
    }

    /**
     * 导出为 JSON 字符串。
     */
    fun toJson(): String {
        return json.encodeToString(this)
    }

    companion object {
        const val SOURCE_PACKAGE_IMPORT = "package_import"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        /**
         * 创建一个全新的规范化技能包，自动生成 SHA-256 校验和。
         */
        fun create(
            id: String,
            name: String,
            display: String,
            author: String = "community",
            apps: List<String> = emptyList(),
            markdownBody: String
        ): HanaSkillPackage {
            val hash = PersonalSkills.fingerprint(markdownBody)
            return HanaSkillPackage(
                formatVersion = 1,
                id = id,
                name = name,
                display = display,
                author = author,
                sha256 = hash,
                apps = apps,
                markdownBody = markdownBody,
                createdAt = System.currentTimeMillis()
            )
        }

        /**
         * 从 JSON 文本反序列化技能包。
         */
        fun fromJson(jsonText: String): HanaSkillPackage {
            return json.decodeFromString(serializer(), jsonText)
        }
    }
}
