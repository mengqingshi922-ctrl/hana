package com.hana.agent.ui.components.mascot

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Hana 原创吉祥物「星灵花瓣」(Star-Petal Spirit) 矢量轮廓与表情数据层。
 *
 * 完全原创参数化矢量轮廓家族（零第三方角色轮廓引用）：
 * - `blob`   (`petal_blossom`) ：星灵花苞 — 五瓣微弧柔云花苞轮廓
 * - `wedge`  (`petal_sprout`)  ：萌芽花蕾 — 双耳叶尖花蕾轮廓
 * - `gem`    (`petal_star`)    ：四叶星灵 — 四瓣星芒花灵轮廓
 * - `square` (`petal_cushion`) ：花瓣麻薯 — 柔角四瓣花枕轮廓
 *
 * 坐标系：viewBox -15..244，头部中心 114.2705；保留 48 点眼环与 96 点身体平滑插值引擎。
 */
internal object MascotData {
    const val FAMILY_NAME = "Hana 星灵花瓣"
    const val HEAD_C = 114.2705f
    const val VIEW_MIN = -15f
    const val VIEW_SIZE = 259f
    const val BODY_POINT_COUNT = 96
    const val EYE_POINT_COUNT = 48

    /** 25 组原创表情眼环：每组 [左眼, 右眼]，各 48 点顺时针闭合序列（96 浮点数 x,y）。 */
    val rings: List<Array<FloatArray>> by lazy { buildOriginalEyeRings() }

    data class BodyShape(val ring: FloatArray, val fx: Float, val fy: Float, val sx: Float, val sy: Float, val eye: Float)

    /** 身体轮廓家族：花苞(blob) / 花蕾(wedge) / 星灵(gem) / 花枕(square)。 */
    val shapes: Map<String, BodyShape> by lazy { buildStarPetalShapes() }

    /** 支持经典键名与 Hana 星灵花瓣别名查询。 */
    fun resolveShape(key: String): BodyShape = when (key) {
        "petal_blossom", "blob" -> shapes.getValue("blob")
        "petal_sprout", "wedge" -> shapes.getValue("wedge")
        "petal_star", "gem" -> shapes.getValue("gem")
        "petal_cushion", "square" -> shapes.getValue("square")
        else -> shapes.getValue("blob")
    }

    data class Emotion(
        val id: String, val name: String, val group: String,
        val pool: IntArray, val poolMs: IntArray?, val poolSpeed: Float,
        val blinkMs: IntArray?, val openness: Float, val antics: Boolean,
        val bodyY: Float, val bodyRotate: Float, val breathe: Float, val bodyScale: Float,
        val color: String?, val zzz: Boolean, val orbit: Boolean,
        val ribbons: Boolean, val confetti: Float, val transition: Int,
    )

    /** 一帧关键帧：时间(ms)、左右眼开合(-1 = 不变)、眼睛缩放、眼睛纵向偏移、身体位移/缩放/旋转/换色。 */
    data class SeqFrame(
        val at: Int, val openL: Float, val openR: Float,
        val eyeSx: Float, val eyeSy: Float, val eyeY: Float,
        val bodyY: Float, val bodyScale: Float, val bodyRotate: Float, val bodyColor: String?,
    )

    /** 进入某个表情时的一次性演出。[next] 播完切到哪个表情，[hold] 定格末帧（不回落）。 */
    data class Sequence(val frames: List<SeqFrame>, val next: String?, val hold: Boolean)

    private fun F(at: Int, ol: Float, or_: Float, sx: Float, sy: Float, ey: Float, by: Float, bs: Float, br: Float, bc: String?) =
        SeqFrame(at, ol, or_, sx, sy, ey, by, bs, br, bc)

    val emotions: List<Emotion> by lazy { listOf(
        Emotion("00","睡眠","life",intArrayOf(13,22,4),intArrayOf(6000,10000),6f,null,0.08f,false,4f,-2f,0.02f,1f,"#EEEBE4",true,false,false,0f,900),
        Emotion("01","唤醒","life",intArrayOf(13),intArrayOf(800,800),6f,null,1f,false,0f,0f,0f,1f,null,false,false,false,0f,320),
        Emotion("02","待机放空","life",intArrayOf(0,8),intArrayOf(9000,16000),6f,intArrayOf(6000,14000),1f,true,0f,0f,0.01f,1f,null,false,false,false,0f,700),
        Emotion("03","好奇","life",intArrayOf(3,21,0,15),intArrayOf(1800,3200),6f,intArrayOf(2500,5500),1f,false,0f,4f,0.01f,1f,null,false,false,false,0f,420),
        Emotion("04","发呆","life",intArrayOf(4,22,0),intArrayOf(3500,6000),6f,intArrayOf(4000,8000),1f,true,0f,-3f,0.01f,1f,null,false,false,false,0f,800),
        Emotion("05","加载苏醒","life",intArrayOf(0,8),intArrayOf(6000,10000),6f,null,1f,false,0f,0f,0f,1f,null,false,false,false,0f,480),
        Emotion("06","休眠","life",intArrayOf(4,22,13),intArrayOf(4000,8000),6f,null,0.4f,false,6f,-1f,0.01f,0.98f,"#EBE8E1",false,false,false,0f,1200),
        Emotion("07","抖动唤醒","life",intArrayOf(13),intArrayOf(800,800),6f,null,1f,false,0f,0f,0f,1f,null,false,false,false,0f,220),
        Emotion("10","开心","emotion",intArrayOf(2,11,17,19),intArrayOf(2500,4500),6f,intArrayOf(2500,5000),1f,true,-3f,0f,0.01f,1f,"#F6EFE4",false,false,false,0f,380),
        Emotion("11","疑惑","emotion",intArrayOf(14,5,8),intArrayOf(2200,3800),6f,intArrayOf(2800,5500),1f,false,0f,-8f,0.01f,1f,null,false,false,false,0f,420),
        Emotion("12","失落","emotion",intArrayOf(4,13,22),intArrayOf(4000,7000),6f,intArrayOf(4000,8000),1f,false,5f,-4f,0.01f,1f,"#EDEAE3",false,false,false,0f,820),
        Emotion("13","惊讶","emotion",intArrayOf(3,21),intArrayOf(2500,4000),6f,intArrayOf(1800,3500),1f,false,-4f,0f,0.01f,1.03f,null,false,false,false,0f,180),
        Emotion("14","害羞","emotion",intArrayOf(0,24,13),intArrayOf(3000,5500),6f,intArrayOf(3000,6000),1f,false,0f,6f,0.01f,1f,"#F4D3D0",false,false,false,0f,560),
        Emotion("15","疲惫","emotion",intArrayOf(4,22,13),intArrayOf(4000,8000),6f,null,0.55f,false,4f,-3f,0.02f,1f,"#EFECE5",false,false,false,0f,900),
        Emotion("16","专注","emotion",intArrayOf(7,16,11,10),intArrayOf(1800,3200),6f,intArrayOf(2800,5500),1f,false,0f,0f,0f,1f,null,false,false,false,0f,320),
        Emotion("17","慌张","emotion",intArrayOf(3,21),intArrayOf(900,1800),6f,intArrayOf(1200,3000),1f,false,0f,0f,0.01f,1f,null,false,false,false,0f,200),
        Emotion("18","无奈","emotion",intArrayOf(14,5,23),intArrayOf(2600,4500),6f,intArrayOf(4500,8000),1f,false,2f,10f,0.01f,1f,null,false,false,false,0f,560),
        Emotion("19","满意","emotion",intArrayOf(15,8,2),intArrayOf(3500,6000),6f,intArrayOf(3500,7000),1f,true,0f,0f,0.01f,1f,"#F5EFE6",false,false,false,0f,580),
        Emotion("20","困惑","emotion",intArrayOf(14,5,8),intArrayOf(2200,3800),6f,intArrayOf(2800,5500),1f,false,0f,-5f,0.01f,1f,null,false,false,false,0f,480),
        Emotion("21","生气","emotion",intArrayOf(7,16),intArrayOf(2200,3800),6f,intArrayOf(3500,7000),1f,false,1f,0f,0f,1f,"#E4574A",false,false,false,0f,260),
        Emotion("30","思考中","agent",intArrayOf(8,16,14,17,5),intArrayOf(2000,3600),6f,intArrayOf(3500,7000),1f,false,0f,-3f,0.01f,1f,null,false,true,false,0f,480),
        Emotion("31","接收任务","agent",intArrayOf(19,0,8),intArrayOf(4000,8000),6f,null,1f,false,0f,0f,0.01f,1f,null,false,false,false,0f,220),
        Emotion("32","处理中忙碌","agent",intArrayOf(7,16,11,10),intArrayOf(1800,3200),6f,intArrayOf(2800,5500),1f,false,0f,0f,0.01f,1f,null,false,false,false,0f,360),
        Emotion("33","任务完成","agent",intArrayOf(2,8,17),intArrayOf(1400,2600),6f,intArrayOf(2200,4500),1f,false,0f,0f,0f,1f,null,false,false,true,0.95f,240),
        Emotion("34","出错","agent",intArrayOf(3,21),intArrayOf(2000,3600),6f,null,1f,false,0f,-6f,0f,1f,"#E25B5B",false,false,false,0f,220),
        Emotion("35","等待输入","agent",intArrayOf(10,1,19),intArrayOf(2800,5000),6f,intArrayOf(3000,7000),1f,false,0f,0f,0.01f,1f,null,false,false,false,0f,480),
        Emotion("36","联网加载","agent",intArrayOf(0,8),intArrayOf(6000,10000),6f,null,1f,false,0f,0f,0f,1f,null,false,false,false,0f,380),
        Emotion("37","复述回忆","agent",intArrayOf(10,1,19),intArrayOf(4000,8000),6f,null,1f,false,0f,-2f,0.01f,1f,null,false,false,false,0f,780),
        Emotion("38","拒绝/受限","agent",intArrayOf(14,5,23),intArrayOf(2600,4500),6f,intArrayOf(4500,8000),0.6f,false,2f,-2f,0f,1f,"#EFE8E4",false,false,false,0f,380),
        Emotion("39","输出回复","agent",intArrayOf(15,9),intArrayOf(4000,8000),6f,null,1f,false,0f,0f,0.01f,1f,null,false,false,false,0f,360),
        Emotion("40","检索资料","agent",intArrayOf(15,9,3,20,12,18),intArrayOf(1000,1800),10f,intArrayOf(1600,4000),1f,false,0f,0f,0.01f,1f,null,false,false,false,0f,320),
        Emotion("41","停止终止","agent",intArrayOf(13,22),intArrayOf(6000,9000),6f,null,1f,false,3f,0f,0f,1f,"#EBE8E2",false,false,false,0f,280),
    ) }

    /** 10 套进场演出（惊讶弹一下、害羞渐变成粉、唤醒睁眼后自动回待机…）。 */
    val sequences: Map<String, Sequence> by lazy { mapOf(
        "01" to Sequence(listOf(
            F(0,0.1f,0.1f,1f,1f,4f,0f,1f,0f,null),
            F(420,0.55f,0.12f,1f,1f,2f,0f,1f,0f,null),
            F(820,0.3f,0.3f,1f,1f,3f,0f,1f,0f,null),
            F(1400,1f,1f,1.12f,1.12f,-2f,0f,1f,0f,null),
            F(2100,1f,1f,1f,1f,0f,0f,1f,0f,null),
        ), next = "02", hold = false),
        "07" to Sequence(listOf(
            F(0,0.1f,0.1f,1f,1f,0f,0f,1f,0f,null),
            F(380,0.4f,0.12f,1f,1f,0f,0f,1f,0f,null),
            F(900,0.7f,0.7f,1f,1f,1f,0f,1f,0f,null),
            F(1600,1f,1f,1.08f,1.08f,-1f,0f,1f,0f,null),
        ), next = "02", hold = false),
        "13" to Sequence(listOf(
            F(0,-1f,-1f,0.92f,0.92f,0f,0f,1f,0f,null),
            F(150,-1f,-1f,1.45f,1.45f,-4f,-7f,1.05f,0f,null),
            F(420,-1f,-1f,1.22f,1.22f,-3f,0f,1f,0f,null),
        ), next = null, hold = false),
        "14" to Sequence(listOf(
            F(0,-1f,-1f,1f,1f,0f,0f,1f,0f,"#F3F0EA"),
            F(1500,-1f,-1f,1f,1f,0f,0f,1f,0f,"#F4D3D0"),
        ), next = null, hold = true),
        "21" to Sequence(listOf(
            F(0,-1f,-1f,1f,1f,0f,0f,1f,0f,"#F3F0EA"),
            F(250,-1f,-1f,1f,1f,0f,0f,1f,0f,"#E4574A"),
        ), next = null, hold = true),
        "31" to Sequence(listOf(
            F(0,-1f,-1f,1f,1f,0f,0f,1f,0f,null),
            F(100,0.1f,0.1f,1f,1f,0f,0f,1f,0f,null),
            F(280,1f,1f,1.12f,1.12f,-2f,-3f,1f,0f,null),
            F(700,1f,1f,1f,1f,0f,0f,1f,0f,null),
        ), next = null, hold = false),
        "33" to Sequence(listOf(
            F(0,-1f,-1f,1f,1f,0f,0f,1f,0f,null),
            F(300,-1f,-1f,1.1f,1.1f,-5f,-6f,1f,0f,null),
            F(700,-1f,-1f,1.05f,1.05f,-4f,-2f,1f,0f,null),
            F(1100,-1f,-1f,1f,1f,0f,0f,1f,0f,null),
        ), next = null, hold = false),
        "34" to Sequence(listOf(
            F(0,-1f,-1f,1f,1f,0f,0f,1f,-6f,"#E25B5B"),
            F(170,-1f,-1f,1f,1f,0f,0f,1f,-4f,"#F6F3EC"),
            F(340,-1f,-1f,1f,1f,0f,0f,1f,-7f,"#E25B5B"),
            F(510,-1f,-1f,1f,1f,0f,0f,1f,-5f,"#F6F3EC"),
            F(700,-1f,-1f,1f,1f,0f,0f,1f,-6f,"#DE5555"),
        ), next = null, hold = true),
        "38" to Sequence(listOf(
            F(0,-1f,-1f,1f,1f,0f,0f,1f,0f,null),
            F(130,-1f,-1f,1f,1f,0f,0f,1f,-6f,null),
            F(300,-1f,-1f,1f,1f,0f,0f,1f,2f,null),
            F(470,-1f,-1f,1f,1f,0f,0f,1f,-5f,null),
            F(630,-1f,-1f,1f,1f,0f,0f,1f,0f,null),
            F(800,-1f,-1f,1f,1f,0f,0f,1f,-2f,null),
        ), next = null, hold = false),
        "41" to Sequence(listOf(
            F(0,-1f,-1f,1f,1f,0f,0f,1f,0f,null),
            F(1500,0.35f,0.35f,0.6f,0.6f,3f,4f,0.97f,0f,null),
        ), next = null, hold = true),
    ) }

    fun emotion(id: String): Emotion = emotions.firstOrNull { it.id == id } ?: emotions.first { it.id == "02" }

    // ==================== Hana 原创「星灵花瓣」96 点矢量轮廓家族 ====================

    private fun samplePolarBody(radiusAt: (Float) -> Float): FloatArray {
        val out = FloatArray(BODY_POINT_COUNT * 2)
        val twoPi = (2.0 * PI).toFloat()
        for (i in 0 until BODY_POINT_COUNT) {
            // 顺时针从 +X 轴起采样，与外法线外扩轨道方向严格一致
            val theta = twoPi * i / BODY_POINT_COUNT
            val r = radiusAt(theta)
            out[i * 2] = HEAD_C + r * cos(theta)
            out[i * 2 + 1] = HEAD_C + r * sin(theta)
        }
        return out
    }

    private fun wrapAngleDiff(a: Float, b: Float): Float {
        var d = (a - b) % (2f * PI.toFloat())
        if (d > PI.toFloat()) d -= 2f * PI.toFloat()
        if (d < -PI.toFloat()) d += 2f * PI.toFloat()
        return d
    }

    private fun buildStarPetalShapes(): Map<String, BodyShape> {
        // 1. 星灵花苞 (blob / petal_blossom)：五瓣柔弧樱花苞，顶瓣饱满、下颊圆润
        val blossom = samplePolarBody { theta ->
            112f * (0.945f + 0.052f * cos(5f * theta - (PI / 2.0).toFloat()) + 0.018f * sin(theta))
        }
        // 2. 萌芽花蕾 (wedge / petal_sprout)：顶部双叶花蕾耳尖 + 饱满护托底座
        val sprout = samplePolarBody { theta ->
            val earLeft = 0.11f * exp(-((wrapAngleDiff(theta, -1.96f)) / 0.34f).pow(2))
            val earRight = 0.11f * exp(-((wrapAngleDiff(theta, -1.18f)) / 0.34f).pow(2))
            val plumpBase = 0.075f * sin(theta)
            104f * (0.91f + earLeft + earRight + plumpBase + 0.022f * cos(3f * theta))
        }
        // 3. 四叶星灵 (gem / petal_star)：四瓣柔角星光花灵
        val star = samplePolarBody { theta ->
            112f * (0.875f + 0.118f * cos(4f * theta) + 0.016f * cos(8f * theta))
        }
        // 4. 花瓣麻薯枕 (square / petal_cushion)：圆润超椭圆配四瓣微凹花腰
        val cushion = samplePolarBody { theta ->
            val c = abs(cos(theta)).pow(3.6f)
            val s = abs(sin(theta)).pow(3.6f)
            val superR = 109f / (c + s).coerceAtLeast(0.0001f).pow(1f / 3.6f)
            superR * (1f - 0.032f * cos(4f * theta))
        }
        return mapOf(
            "blob" to BodyShape(blossom, fx = 0f, fy = 2f, sx = 0.98f, sy = 0.98f, eye = 1f),
            "wedge" to BodyShape(sprout, fx = 0f, fy = 10f, sx = 0.88f, sy = 0.88f, eye = 0.92f),
            "gem" to BodyShape(star, fx = 0f, fy = 0f, sx = 0.88f, sy = 0.88f, eye = 0.96f),
            "square" to BodyShape(cushion, fx = 0f, fy = 0f, sx = 0.92f, sy = 0.92f, eye = 0.96f),
        )
    }

    // ==================== 原创 25 组 48 点表情眼环生成器 ====================

    private data class EyeSpec(
        val cx: Float,
        val cy: Float,
        val rx: Float,
        val ry: Float,
        val tiltDeg: Float = 0f,
        val crescent: Float = 0f,
        val archBottom: Float = 0f,
    )

    private fun sampleEyeRing(spec: EyeSpec): FloatArray {
        val out = FloatArray(EYE_POINT_COUNT * 2)
        val twoPi = (2.0 * PI).toFloat()
        val rad = spec.tiltDeg * PI.toFloat() / 180f
        val cr = cos(rad)
        val sr = sin(rad)
        for (i in 0 until EYE_POINT_COUNT) {
            val theta = twoPi * i / EYE_POINT_COUNT
            val cosT = cos(theta)
            val sinT = sin(theta)
            val lx = spec.rx * cosT
            var ly = spec.ry * sinT
            // 笑眼弯月：下缘向上弯曲，形成柔和的 ^ 弧线
            if (spec.crescent != 0f && sinT > 0f) {
                ly -= spec.crescent * spec.ry * sinT * (1f - 0.35f * cosT * cosT)
            }
            // D 形半圆笑眼：收平底部轮廓
            if (spec.archBottom != 0f && sinT > 0f) {
                ly *= (1f - spec.archBottom)
            }
            out[i * 2] = spec.cx + lx * cr - ly * sr
            out[i * 2 + 1] = spec.cy + lx * sr + ly * cr
        }
        return out
    }

    private fun eyePair(
        dx: Float = 0f,
        dy: Float = 0f,
        rxL: Float = 12f,
        ryL: Float = 19f,
        rxR: Float = rxL,
        ryR: Float = ryL,
        tiltL: Float = 0f,
        tiltR: Float = -tiltL,
        crescent: Float = 0f,
        archBottom: Float = 0f,
        dyL: Float = 0f,
        dyR: Float = 0f,
    ): Array<FloatArray> {
        val leftX = HEAD_C - 28f + dx
        val rightX = HEAD_C + 28f + dx
        val baseY = HEAD_C - 2f + dy
        return arrayOf(
            sampleEyeRing(EyeSpec(leftX, baseY + dyL, rxL, ryL, tiltL, crescent, archBottom)),
            sampleEyeRing(EyeSpec(rightX, baseY + dyR, rxR, ryR, tiltR, crescent, archBottom)),
        )
    }

    private fun buildOriginalEyeRings(): List<Array<FloatArray>> = listOf(
        // 0: 柔和居中默认凝视
        eyePair(rxL = 12.5f, ryL = 20f),
        // 1: 微仰右上轻望
        eyePair(dx = 10f, dy = -8f, rxL = 12f, ryL = 18.5f),
        // 2: 开心半圆 D 形笑眼
        eyePair(dy = -2f, rxL = 14.5f, ryL = 18f, archBottom = 0.55f),
        // 3: 好奇睁大圆眸
        eyePair(rxL = 16f, ryL = 22.5f),
        // 4: 困倦低垂柔椭
        eyePair(dy = 6f, rxL = 14f, ryL = 7.5f),
        // 5: 思索挑眉不对称
        eyePair(rxL = 12.5f, ryL = 18f, rxR = 13.5f, ryR = 10.5f, dyL = -2f, dyR = 3f),
        // 6: 左侧凝视
        eyePair(dx = -13f, rxL = 12.5f, ryL = 19f),
        // 7: 专注微斜内聚
        eyePair(rxL = 12f, ryL = 16f, tiltL = 14f, tiltR = -14f),
        // 8: 放松微向左看
        eyePair(dx = -6f, dy = 2f, rxL = 12.5f, ryL = 18.5f),
        // 9: 阅读回复右上
        eyePair(dx = 8f, dy = -5f, rxL = 12f, ryL = 17.5f),
        // 10: 温润回忆平视
        eyePair(dx = -3f, dy = -3f, rxL = 13f, ryL = 18.5f),
        // 11: 亮晶欢快竖椭
        eyePair(rxL = 13f, ryL = 21.5f),
        // 12: 左上检索张望
        eyePair(dx = -12f, dy = -9f, rxL = 12f, ryL = 18f),
        // 13: 恬静闭合睡眼线
        eyePair(dy = 3f, rxL = 15f, ryL = 2.6f, crescent = 0.35f),
        // 14: 疑惑微歪不对称
        eyePair(rxL = 12.5f, ryL = 15f, rxR = 12.5f, ryR = 18.5f, tiltL = -10f, tiltR = 8f, dyL = -3f, dyR = 3f),
        // 15: 满足微笑月牙
        eyePair(dy = 1f, rxL = 14.5f, ryL = 13.5f, crescent = 0.65f),
        // 16: 高专注锐利窄眸
        eyePair(rxL = 13f, ryL = 11.5f, tiltL = 10f, tiltR = -10f),
        // 17: 欣喜 ^^ 笑弯弧
        eyePair(dy = -1f, rxL = 15.5f, ryL = 13f, crescent = 0.85f),
        // 18: 右下检索张望
        eyePair(dx = 12f, dy = 9f, rxL = 12f, ryL = 18f),
        // 19: 期待星光大眼
        eyePair(rxL = 14f, ryL = 21f),
        // 20: 右侧张望
        eyePair(dx = 13f, rxL = 12.5f, ryL = 19f),
        // 21: 惊喜瞳孔放大
        eyePair(rxL = 17f, ryL = 24f),
        // 22: 慵懒微斜眠线
        eyePair(dy = 4f, rxL = 14f, ryL = 3f, tiltL = 6f, tiltR = -6f),
        // 23: 抱歉/无奈外垂柔眸
        eyePair(dy = 3f, rxL = 13f, ryL = 11f, tiltL = -16f, tiltR = 16f),
        // 24: 害羞弯弯暖眯眼
        eyePair(dy = 4f, rxL = 14.5f, ryL = 10f, crescent = 0.7f),
    )
}
