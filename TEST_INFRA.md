# E2E Test Infra: Hana Architecture Evolution

## Test Philosophy
- 需求驱动、黑盒/灰盒验证与模块单元测试全覆盖。
- 遵循 4 层测试分层体系（Tier 1 功能覆盖、Tier 2 边界值与极端工况、Tier 3 跨模块协同、Tier 4 真实应用场景）。
- 门禁铁律：`:core:test` 与 `:app:testDebugUnitTest` 保持 100% 成功率无异常，真机部署 `NVZXMRRKPBNJQG7L` 成功启动无崩溃。

## Feature Inventory & Test Mapping
| # | Feature | Tier 1 (基础) | Tier 2 (边界/异常) | Tier 3 (组合) | Tier 4 (端到端/真机) |
|---|---------|:-------------:|:-----------------:|:-------------:|:-------------------:|
| 1 | 宏动作链批处理 (`action_chain`) | 5 | 5 | ✓ | ✓ |
| 2 | 原子前置断言机制 (Predicate) | 5 | 5 | ✓ | ✓ |
| 3 | KV-Cache 绝对前缀隔离 | 5 | 5 | ✓ | ✓ |
| 4 | 动态状态后置封装 | 5 | 5 | ✓ | ✓ |
| 5 | 视口拓扑几何剪枝 | 5 | 5 | ✓ | ✓ |
| 6 | 单子节点无语义容器折叠 | 5 | 5 | ✓ | ✓ |
| 7 | 相对坐标量化编码 | 5 | 5 | ✓ | ✓ |
| 8 | 三阶非均匀 B 样条轨迹 | 5 | 5 | ✓ | ✓ |
| 9 | 高斯微抖动注入 | 5 | 5 | ✓ | ✓ |
| 10 | 动态触控压力渐变 | 5 | 5 | ✓ | ✓ |
| 11 | 状态转移熵看门狗 | 5 | 5 | ✓ | ✓ |
| 12 | 三级阶梯自愈机制 | 5 | 5 | ✓ | ✓ |
| 13 | UI 交互图谱 (UiExperienceGraph) | 5 | 5 | ✓ | ✓ |
| 14 | 分层子目标检查点 (SubgoalCheckpoint) | 5 | 5 | ✓ | ✓ |
| 15 | 三态灵动悬浮胶囊 (Unified Capsule) | 5 | 5 | ✓ | ✓ |
| 16 | 神经核反射脉冲指示器 | 5 | 5 | ✓ | ✓ |
| 17 | 实时执行时序瀑布流 | 5 | 5 | ✓ | ✓ |
| 18 | 人机协同安全接管门 | 5 | 5 | ✓ | ✓ |
| 19 | ModelScope 分片断点下载器 | 5 | 5 | ✓ | ✓ |
| 20 | 权重 SHA-256 校验与磁盘清理 | 5 | 5 | ✓ | ✓ |
| 21 | Bento 卡片下载联动与无感降级 | 5 | 5 | ✓ | ✓ |
| 22 | JNI Native 接口契约与平滑降级 | 5 | 5 | ✓ | ✓ |

## Test Architecture & Execution
- **Unit Test Runner**:
  - JVM Core: `./gradlew :core:test` (必须 100% 通过)
  - Android App: `./gradlew :app:testDebugUnitTest` (必须 100% 通过)
- **APK Build & Real Device Verification**:
  - Build: `./gradlew :app:assembleDebug`
  - Deploy: `adb -s NVZXMRRKPBNJQG7L install -r app/build/outputs/apk/debug/app-debug.apk`
  - Smoke: `adb -s NVZXMRRKPBNJQG7L shell am start -n com.hana.agent/.MainActivity`
