package com.hana.agent.core.uistate

/** 站点知识、App 知识里 `pace` 说的这个网站或 App 对浏览快慢的要求(App 里的节奏见 [com.hana.agent.core.cap.phone.AppPacing])。 */
enum class PlacePace {
    /** 没有特别说法。 */
    NORMAL,
    /** 这个网站或 App 限制浏览快慢(看得太快、太多会被拦):每页停得更久、一个任务里看的页、屏更少。 */
    CAREFUL;

    companion object {
        /** 常见高风控敏感应用包名（社交、短视频、电商、支付生活类），无论知识库是否标注，均自动提升为 CAREFUL 拟人防封节奏 */
        private val HIGH_RISK_PACKAGES = setOf(
            "com.tencent.mm",              // 微信
            "com.tencent.mobileqq",         // QQ
            "com.xingin.xhs",               // 小红书
            "com.ss.android.ugc.aweme",     // 抖音
            "com.ss.android.ugc.aweme.lite",// 抖音极速版
            "com.zhiliaoapp.musically",     // TikTok
            "com.taobao.taobao",           // 手机淘宝
            "com.taobao.idlefish",         // 闲鱼
            "com.xunmeng.pinduoduo",       // 拼多多
            "com.jingdong.app.mall",       // 京东
            "com.eg.android.AlipayGphone", // 支付宝
            "com.sankuai.meituan",         // 美团
            "com.sankuai.meituan.takeoutnew", // 美团外卖
            "com.dianping.v1",             // 大众点评
            "com.alibaba.wireless"         // 阿里巴巴
        )

        /** 站点知识头部的值(`careful`;不写为 null)以及目标应用包名。 */
        fun of(value: String?, packageName: String? = null): PlacePace =
            if (value == "careful" || (packageName != null && packageName in HIGH_RISK_PACKAGES)) CAREFUL else NORMAL
    }
}
