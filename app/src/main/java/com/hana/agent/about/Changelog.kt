package com.hana.agent.about

/**
 * 更新记录（本地）。按模块化聚合展示版本演进：
 * 结构遵循【大版本亮点】+【功能模块分组】+【要点标题与明细】，
 * 告别冗长流水账，界面呈现现代精美卡片。
 */
object Changelog {

    enum class Tag(val label: String) {
        Feature("新增"),
        Perf("优化"),
        Fix("修复"),
    }

    /** 一条精简明了的改动项：标题 + 详细说明 */
    data class Item(
        val tag: Tag,
        val title: String,
        val detail: String = "",
    )

    /** 一个功能模块卡片（Bento 聚合） */
    data class Group(
        val title: String,
        val icon: String,
        val items: List<Item>,
    )

    /** 一次版本演进：版本号、日期、大版本亮点标语、模块分组列表、兼容文本列表 */
    data class Entry(
        val version: String,
        val date: String,
        val highlight: String = "",
        val groups: List<Group> = emptyList(),
        val items: List<String> = emptyList(),
    )

    val entries: List<Entry> = listOf(
        Entry(
            version = "0.1.34",
            date = "2026-11-04",
            highlight = "灵动小精灵 Mascot 交互体系与端侧全链路极速加速",
            groups = listOf(
                Group(
                    title = "灵动交互 · Mascot 体系",
                    icon = "✨",
                    items = listOf(
                        Item(Tag.Feature, "置顶常驻导航栏", "小精灵头像与状态胶囊居中紧密贴合，消息上下滚动不离屏"),
                        Item(Tag.Feature, "32 种动态表情与微动作", "支持专注思考、害羞、目瞪惊讶、元气眨眼与撒花等丰富情绪"),
                        Item(Tag.Feature, "4 款几何机甲造型随心切换", "经典圆球、机甲三角、超椭圆方块、璀璨菱星造型即时形变"),
                        Item(Tag.Feature, "流光特效与触控元气寄语", "手机操作中 60fps 旋转霓虹彩环；戳一戳弹性挤压并浮现治愈寄语"),
                    ),
                ),
                Group(
                    title = "执行引擎 · 极速直通",
                    icon = "⚡️",
                    items = listOf(
                        Item(Tag.Perf, "动作意图极速直通 (Fast-Path)", "高频动作关键词秒级判定，跳过开局 15~30 秒白等推演"),
                        Item(Tag.Perf, "步进节拍提速 85%+", "废除按字数死等，换屏与动画等待压缩至 200~400ms"),
                        Item(Tag.Perf, "应用启动毫秒级本地秒开", "支持本地轻量开屏等待重试，单步启动耗时减少 75 秒"),
                        Item(Tag.Perf, "应用名称模糊智能匹配", "直接说「打开小红书」自动匹配并启动对应包名"),
                    ),
                ),
                Group(
                    title = "模型调度 · 双模型容灾",
                    icon = "🤖",
                    items = listOf(
                        Item(Tag.Perf, "双通道自适应并发对冲", "P90 延迟感知触发，主通道 503/429 报错绝不误杀备用通道"),
                        Item(Tag.Feature, "Agnes 512K 中国站极速接入", "区分中国站与国际站，接入专用 Key 并升级为主力模型"),
                        Item(Tag.Perf, "上下文智能压缩门限调优", "修复触发点下限 Bug，Token 占用暴降 33%~48%"),
                        Item(Tag.Fix, "思维链标签自动清洗", "自动剥离 <think> 标签，避免内部推理草稿污染正文"),
                    ),
                ),
                Group(
                    title = "体验加固 · 稳定消错",
                    icon = "🛡️",
                    items = listOf(
                        Item(Tag.Feature, "新对话自动起短标题", "首条消息由后台轻量模型智能精炼为 16 字精短标题"),
                        Item(Tag.Fix, "任务流程卡片平稳悬挂", "彻底消除多步骤执行期间卡片抽搐突变与闪退问题"),
                        Item(Tag.Fix, "模型非标参数容错别名", "自动过滤 extra 字段，常见入参支持别名回退防报错"),
                        Item(Tag.Perf, "手机连接引导全面重做", "无线调试/配对/USB 指引，覆盖 10 种错误诊断与离线秒开"),
                    ),
                ),
            ),
            items = listOf(
                "新增：常驻置顶灵动小精灵导航栏与 32 种动态表情，支持 4 款几何造型切换",
                "优化：动作意图极速直通（Fast-Path）与 AppPacing 节拍重构，操作提速 85%+",
                "优化：双模型自适应对冲与平滑容灾，接入 Agnes 512K 极速模型",
                "修复：应用中文名模糊匹配启动与模型非标参数智能容错",
            ),
        ),
        Entry(
            version = "0.1.33",
            date = "2026-11-02",
            highlight = "Root 设备免配对启动与任务历史回溯",
            groups = listOf(
                Group(
                    title = "Root 赋能与任务回溯",
                    icon = "⚡️",
                    items = listOf(
                        Item(Tag.Feature, "Root 免配对一键启动", "已 Root 手机免电脑 USB 一键启动助手，重启后自主恢复"),
                        Item(Tag.Feature, "Root 系统命令执行", "支持自主执行 Root 命令（默认安全关闭，需手动授权）"),
                        Item(Tag.Feature, "任务历史回溯", "任务中断后再说「继续」可无缝衔接上一轮读到的内容"),
                        Item(Tag.Perf, "全链路网络代理统一管理", "模型调用、账号登录与更新下载全链路支持代理与局域网直连"),
                    ),
                ),
            ),
            items = listOf(
                "新增：已 Root 手机免配对、免电脑 USB 一键启动手机助手，重启后自主恢复",
                "新增：支持已 Root 手机自主执行 Root 系统命令（需手动授权）",
                "新增：任务历史回溯 history_read，任务中断后再说「继续」可无缝衔接",
                "优化：网络代理统一管理，全链路支持代理与局域网直连",
            ),
        ),
        Entry(
            version = "0.1.32",
            date = "2026-10-30",
            highlight = "arm64 精简安装包与进程级连接复用",
            groups = listOf(
                Group(
                    title = "体积精简与连接复用",
                    icon = "📦",
                    items = listOf(
                        Item(Tag.Feature, "独立 arm64 精简包", "按 CPU 架构发布 arm64-v8a 独立包，大幅缩减安装包体积"),
                        Item(Tag.Perf, "进程级 ADB 连接复用", "Agent 快速路径每个进程内 adb server 仅启动一次"),
                    ),
                ),
            ),
            items = listOf(
                "新增：发布包按 CPU 架构多出 arm64-v8a 独立精简包",
                "优化：Agent 快速路径按手机实际选择方式连接，复用连接池",
            ),
        ),
        Entry(
            version = "0.1.31",
            date = "2026-10-25",
            highlight = "Android 12/13 跨版本底层兼容性修复",
            groups = listOf(
                Group(
                    title = "跨版本系统兼容",
                    icon = "🛡️",
                    items = listOf(
                        Item(Tag.Fix, "安卓 12 配对崩溃修复", "解决 BigInteger 跨版本兼容性引发的配对闪退"),
                        Item(Tag.Fix, "低版本字节转换兼容", "适配低于安卓 13 的流读取方法，避免 NoSuchFieldError"),
                        Item(Tag.Perf, "防截屏窗口诊断增强", "提升无障碍服务防截屏窗口的截图错误码识别精确度"),
                    ),
                ),
            ),
            items = listOf(
                "修复：安卓 12 系统上进行配对时崩溃的严重问题",
                "修复：低于安卓 13 版本的流读取与字节转换方法兼容性",
                "优化：提升无障碍服务防截屏窗口的截图错误码识别精确度",
            ),
        ),
        Entry(
            version = "0.1.30",
            date = "2026-10-20",
            highlight = "基础手机操作底层注入与无障碍解析升级",
            groups = listOf(
                Group(
                    title = "基础操作注入优化",
                    icon = "📱",
                    items = listOf(
                        Item(Tag.Feature, "底层手势与点击注入", "优化点击、滑动与文本键入的底层注入稳定性"),
                        Item(Tag.Perf, "无障碍节点树解析提速", "提升复杂应用界面的元素识别与定位性能"),
                    ),
                ),
            ),
            items = listOf(
                "新增：基础手机操作能力升级，优化点击、滑动与文本键入稳定性",
                "优化：无障碍节点树解析性能，提升复杂界面元素的定位速度",
            ),
        ),
    )
}
