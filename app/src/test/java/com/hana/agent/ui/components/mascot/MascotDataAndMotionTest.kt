package com.hana.agent.ui.components.mascot

import com.hana.agent.capsule.AgentTaskPhase
import com.hana.agent.ui.components.GrokBotMood
import com.hana.agent.ui.components.GrokBotShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MascotDataAndMotionTest {

    @Test
    fun starPetalBodyContoursHave96PointsAndDistinctSilhouettes() {
        val keys = listOf("blob", "wedge", "gem", "square")
        val shapes = keys.map { key ->
            val shape = MascotData.shapes.getValue(key)
            assertEquals("形状 $key 必须保持 96 点闭合轮廓(192 float)", MascotData.BODY_POINT_COUNT * 2, shape.ring.size)
            for (i in shape.ring.indices step 2) {
                val x = shape.ring[i]
                val y = shape.ring[i + 1]
                assertTrue("$key x=$x 越界", x in 0f..228.541f)
                assertTrue("$key y=$y 越界", y in 0f..228.541f)
            }
            shape
        }

        // 四种花瓣变体彼此轮廓不同，且支持 Hana 原创别名解析
        assertNotEquals(shapes[0].ring.toList(), shapes[1].ring.toList())
        assertNotEquals(shapes[1].ring.toList(), shapes[2].ring.toList())
        assertNotEquals(shapes[2].ring.toList(), shapes[3].ring.toList())
        assertEquals(MascotData.shapes["blob"], MascotData.resolveShape("petal_blossom"))
        assertEquals(MascotData.shapes["wedge"], MascotData.resolveShape("petal_sprout"))
        assertEquals(MascotData.shapes["gem"], MascotData.resolveShape("petal_star"))
        assertEquals(MascotData.shapes["square"], MascotData.resolveShape("petal_cushion"))
        assertEquals("Hana 星灵花瓣", MascotData.FAMILY_NAME)
    }

    @Test
    fun eyeRingsAndEmotionsMatchInterpolationContract() {
        assertEquals(25, MascotData.rings.size)
        MascotData.rings.forEachIndexed { idx, pair ->
            assertEquals("眼环 $idx 必须包含左右眼", 2, pair.size)
            assertEquals("左眼环 $idx 必须为 48 点(96 float)", MascotData.EYE_POINT_COUNT * 2, pair[0].size)
            assertEquals("右眼环 $idx 必须为 48 点(96 float)", MascotData.EYE_POINT_COUNT * 2, pair[1].size)
            pair[0].forEach { v -> assertTrue("左眼环值非有限数", v.isFinite()) }
            pair[1].forEach { v -> assertTrue("右眼环值非有限数", v.isFinite()) }
        }

        assertEquals(32, MascotData.emotions.size)
        assertEquals("blob", shapeKeyFor(GrokBotShape.Circle))
        assertEquals("wedge", shapeKeyFor(GrokBotShape.Triangle))
        assertEquals("square", shapeKeyFor(GrokBotShape.Square))
        assertEquals("gem", shapeKeyFor(GrokBotShape.Diamond))
        assertEquals("31", mascotEmotionId(GrokBotMood.Thinking, working = true, phase = AgentTaskPhase.Starting))
        assertEquals("33", mascotEmotionId(GrokBotMood.Happy, working = false))
    }

    @Test
    fun audioAmplitudeMicroHopRespondsProportionalAndClampsBounds() {
        val (sx0, sy0) = computeAudioBounceScale(0f)
        assertEquals(1f, sx0, 1e-5f)
        assertEquals(1f, sy0, 1e-5f)
        assertEquals(0f, computeAudioBounceOffsetY(0f), 1e-5f)

        val (sxMid, syMid) = computeAudioBounceScale(0.5f)
        assertTrue(sxMid > 1f && syMid > sxMid)
        assertTrue(computeAudioBounceOffsetY(0.5f) < 0f)

        val (sxMax, syMax) = computeAudioBounceScale(2.5f)
        val (sxOne, syOne) = computeAudioBounceScale(1f)
        assertEquals(sxOne, sxMax, 1e-5f)
        assertEquals(syOne, syMax, 1e-5f)
        assertEquals(computeAudioBounceOffsetY(1f), computeAudioBounceOffsetY(5f), 1e-5f)
    }
}
