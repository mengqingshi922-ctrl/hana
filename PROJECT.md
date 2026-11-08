# Project: Hana Architecture Evolution (v5.0)

## Architecture
- **双层解耦架构**：
  - `:core`：纯 JVM Kotlin 核心逻辑层。包含宏动作执行流水线（`action_chain`）、KV-Cache 绝对前缀冻结、无障碍视口剪枝与折叠、B 样条拟人动力学手势、状态转移熵看门狗与自愈、UI 经验图谱（`UiExperienceGraph`）与子目标检查点（`SubgoalCheckpoint`）。不依赖任何 Android 专有运行时。
  - `:app`：Android Jetpack Compose + Accessibility UI/运行时层。包含三态灵动悬浮胶囊（`DynamicCapsule`）、流体化 Bento 控制台与执行时序瀑布流（`TelemetryWaterfall`）、安全接管门（`SafeGate` 触觉与呼吸光晕）、MNN-Pocket ModelScope 断点下载器与 JNI Native 神经核闭环引擎。
- **真机目标设备**：`NVZXMRRKPBNJQG7L` (Redmi K80, Android 16 / SDK 36)。

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | 宏动作链批处理 (`action_chain`) | 连续下发带前置断言的宏动作序列，满足条件时 0ms 停顿执行 | M1 | spec_miner_survey #1 |
| 2 | 原子前置断言机制 (Predicate) | 步骤执行前/后校验控件状态 (focused, text_updated, appeared 等) | M1 | spec_miner_survey #2 |
| 3 | KV-Cache 绝对前缀隔离 (Prefix Freeze) | 冻结角色人设、准则与 Tool Schema，保证字节级一致，动态状态严格后置 | M1 | spec_miner_survey #3 |
| 4 | 动态状态后置封装 (Dynamic Suffix) | 将时间、电量、当前屏幕等易变状态收拢至末尾 User 消息 | M1 | spec_miner_survey #4 |
| 5 | 视口拓扑几何剪枝 (Viewport Pruning) | 相交测试剔除屏幕可视区域外及零尺寸无意义组件 | M1 | spec_miner_survey #5 |
| 6 | 单子节点无语义容器折叠 (Container Collapse) | 剔除多层仅有 1 个子节点且自身无文本/事件的嵌套布局 | M1 | spec_miner_survey #6 |
| 7 | 相对坐标量化编码 (Quantized Coords) | 将物理绝对像素坐标归一化为 [0~1000] 整数相对范围 | M1 | spec_miner_survey #7 |
| 8 | 三阶非均匀 B 样条轨迹 (Cubic B-Spline) | 基于四点规划与 B 样条基函数模拟初速加速、巡航与减速过冲 | M1 | spec_miner_survey #8 |
| 9 | 高斯微抖动注入 (Gaussian Hand Jitter) | 在采样轨迹上注入 $\sigma = 1.2\text{px}$ 高斯物理噪声 | M1 | spec_miner_survey #9 |
| 10 | 动态触控压力渐变 (Dynamic Pressure) | 按下 0.2->0.8，滑动随速度波动，抬手骤降至 0.1 | M1 | spec_miner_survey #10 |
| 11 | 状态转移熵看门狗 (Watchdog) | 监控最近 4 步视觉指纹，识别连续原地踏步与 A-B-A-B 循环 | M1 | spec_miner_survey #11 |
| 12 | 三级阶梯自愈机制 (Healing Escalation) | Level 1 端侧弹窗消除 -> Level 2 Back 回退 -> Level 3 杀进程冷启 | M1 | spec_miner_survey #12 |
| 13 | UI 交互图谱 (UiExperienceGraph) | 持久化高频 App 关键页签名与宏动作序列，二次操作 0ms 直通 | M1 | spec_miner_survey #13 |
| 14 | 分层子目标检查点 (SubgoalCheckpoint) | 长程任务按 DAG 分层拆解并在关键子目标保存不可变快照 | M1 | spec_miner_survey #14 |
| 15 | 三态灵动悬浮胶囊 (Unified Capsule) | 融合 Pip 与 Mascot，支持 Mini(小球)/Pill(胶囊)/Expanded(全息舱) | M2 | spec_miner_survey #15 |
| 16 | 神经核反射脉冲指示器 (Edge Pulse) | Canvas 动态绘制 10ms~18ms 端侧决策耗时折线波形 | M2 | spec_miner_survey #16 |
| 17 | 实时执行时序瀑布流 (Telemetry Waterfall) | 展示 Perceive / Edge Filter / Cloud TTFT / Inject 四阶段耗时 | M2 | spec_miner_survey #17 |
| 18 | 人机协同安全接管门 (Safe Gate UX) | 拦截不可逆破坏性操作，显示琥珀色呼吸光晕与两段式马达振动 | M2 | spec_miner_survey #18 |
| 19 | ModelScope 分片断点下载器 | 支持 HTTP Range 分片续传、国内镜像源与 Wi-Fi 预取 | M3 | spec_miner_survey #19 |
| 20 | 权重 SHA-256 校验与磁盘清理 | 计算文件哈希防篡改，提供一键物理空间释放 | M3 | spec_miner_survey #20 |
| 21 | Bento 卡片下载联动与无感降级 | 下载前一键触发、下载中流体进度，未就绪自动无感降级云端 | M3 | spec_miner_survey #21 |
| 22 | JNI Native 接口契约与平滑降级 | C++ JNI 绑定 MNN 运行时，无 Native .so 时降级到 Kotlin 规则引擎 | M3 | spec_miner_survey #22 |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | 阶段一：核心流水线与执行效率重构 | 宏动作链(0ms流水线与断言)、KV-Cache前缀冻结、视口剪枝与容器折叠、B样条拟人手势+微抖动+动态压力、状态熵看门狗+三级自愈、UI经验图谱与子目标检查点，及 `:core` 单元测试 | none | DONE |
| M2 | 阶段二：UI/UX 质感飞跃与多态交互 | 三态灵动悬浮胶囊(Mini/Pill/Expanded)、流体化 Bento 卡片与神经核反射脉冲指示器、实时执行时序瀑布流、安全接管流(琥珀色呼吸光晕与触觉振动反馈) | M1 (契约对齐) | DONE |
| M3 | 阶段三：MNN-Pocket 0.3B 端侧闭环 | ModelScope 断点续传下载器、StateFlow 进度、SHA-256校验与存储清理、Bento下载联动与无感降级、JNI Native契约与Dec Pointer快速匹配 | M2 (卡片就绪) | DONE |
| M4 | 阶段四：全量双轨单测与端到端验证 | 全量执行 `:core:test` 与 `:app:testDebugUnitTest` 达到 100% 成功率无失败，验证四层 E2E 测试集 | M1, M2, M3 | DONE |
| M5 | 阶段五：真机构建安装与在线核心链路验证 | 执行 assembleDebug 构建 APK，通过 ADB 部署至目标真机设备 NVZXMRRKPBNJQG7L，执行免崩溃启动与链路联调 | M4 | DONE |

## Interface Contracts

### 1. 宏动作链与断言契约 (`com.hana.agent.core.action`)
- `ChainPredicateKind`: `NONE`, `FOCUSED`, `TEXT_UPDATED`, `PAGE_NAVIGATED`, `ELEMENT_APPEARED`, `ELEMENT_GONE`
- `MacroStep(val tool: String, val args: JsonObject, val predicate: ChainPredicateKind, val expectText: String?, val timeoutMs: Long)`
- `MacroActionChain(val steps: List<MacroStep>, val fallbackStrategy: String, val comment: String?)`

### 2. KV-Cache 绝对前缀冻结契约 (`com.hana.agent.core.prompt`)
- `StaticPrefixBlock`: 静态系统提示词，严禁包含任何动态变量，全局字节级冻结。
- `DynamicSuffixBlock`: 将 `Local time`, `Current place`, `Battery`, `Network`, `Screen diff` 严格封装在末尾 User 消息。

### 3. 拟人动力学手势契约 (`com.hana.agent.core.gesture`)
- `CubicBSplineDynamics`: 四控制点 B 样条，初速加速、巡航、过冲回正（2~4px）。
- `GaussianJitter`: $\sigma = 1.2\text{px}$ 白噪声扰动。
- `PressureProfile`: 按下 0.2->0.8，滑动随速度波动，抬手降至 0.1。

### 4. 状态转移熵与看门狗 (`com.hana.agent.core.watchdog`)
- `VisualEntropyWatchdog`: 4 步视觉指纹差值 $\le 0.05$ 判定原地踏步，8 步周期判定 A-B-A-B 循环。
- `HealingEscalation`: Level 1 弹窗消除 -> Level 2 KEYCODE_BACK -> Level 3 杀进程冷启。

### 5. UI 经验图谱与子目标 (`com.hana.agent.core.experience` & `subgoal`)
- `UiExperienceGraph`: `lookup(appId, pageSignature, intent): UiExperienceEntry?`, `recordSuccess()`, `invalidate()`
- `SubgoalCheckpoint`: `checkpointId`, `subgoalName`, `packageName`, `activityName`, `uiSnapshotHash`, `registers`, `timestamp`

### 6. 三态悬浮胶囊契约 (`com.hana.agent.ui.capsule`)
- `CapsuleState`: `MINI` (48dp 灵动小球), `PILL` (动作文字 + 暂停/急停), `EXPANDED` (透视全息舱)

### 7. 端侧神经核下载与引擎契约 (`com.hana.agent.edge`)
- `DownloadState`: `NotDownloaded`, `Downloading(progress, speedBps, downloadedBytes, totalBytes)`, `Verifying(sha256)`, `Ready`, `Error(message, canRetry)`
- `MnnPocketEngine`: `nativeInit(modelPath, threadNum): Long`, `nativeClassifyIntent()`, `nativePickObstaclePointer()`, `nativeMatchReplayPointer()`, `nativeRelease()`

## Code Layout
- `:core`:
  - `src/main/java/com/hana/agent/core/action/` -> 宏动作链与断言
  - `src/main/java/com/hana/agent/core/prompt/` -> KV-Cache 绝对前缀冻结
  - `src/main/java/com/hana/agent/core/tree/` -> 视口拓扑剪枝与单子节点折叠
  - `src/main/java/com/hana/agent/core/gesture/` -> 三阶 B 样条与拟人动力学
  - `src/main/java/com/hana/agent/core/watchdog/` -> 状态转移熵与三级自愈
  - `src/main/java/com/hana/agent/core/experience/` -> UI 经验图谱
  - `src/main/java/com/hana/agent/core/subgoal/` -> 分层子目标检查点
- `:app`:
  - `src/main/java/com/hana/agent/ui/capsule/` -> 三态灵动悬浮胶囊
  - `src/main/java/com/hana/agent/ui/components/` -> Bento 卡片与 Telemetry Waterfall
  - `src/main/java/com/hana/agent/ui/safegate/` -> 安全接管流 (呼吸光晕与触觉马达)
  - `src/main/java/com/hana/agent/edge/` -> ModelScope 断点下载器与 JNI Native 引擎
