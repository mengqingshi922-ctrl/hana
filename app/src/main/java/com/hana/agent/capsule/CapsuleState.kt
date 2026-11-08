package com.hana.agent.capsule

/**
 * 统一智能体悬浮胶囊三态枚举 (Unified Agent Dynamic Capsule State)
 *
 * - [MINI]: 48dp 灵动拟人小球，贴边呼吸与微表情眨眼；
 * - [PILL]: 跨应用动态操作胶囊，展示实时动作文字、[暂停/继续] 与 [急停] 控制按键；
 * - [EXPANDED]: 透视全息舱，小球思考脉冲 + 虚拟屏实时画面 + 一键紧急接管 + 提速模式。
 */
enum class CapsuleState {
    MINI,      // 48dp 灵动小球
    PILL,      // 跨应用动态操作胶囊 (文字 + 暂停/急停)
    EXPANDED   // 透视全息舱 (小球思考脉冲 + 虚拟屏画面 + 紧急接管)
}
