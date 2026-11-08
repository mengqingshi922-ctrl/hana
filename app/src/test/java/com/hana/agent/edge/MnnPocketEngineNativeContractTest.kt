package com.hana.agent.edge

import com.hana.agent.core.agent.IntentGate
import com.hana.agent.core.edge.EdgeDecisionPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * 验证 MNN-Pocket Engine JNI Native 接口契约、反射签名完整性与平滑降级能力
 */
class MnnPocketEngineNativeContractTest {

    @Test
    fun verifiesFiveJniNativeMethodDeclarations() {
        val clazz = MnnPocketEngine::class.java

        // 1. nativeInit(String, int): long
        val nativeInitMethod = clazz.getDeclaredMethod("nativeInit", String::class.java, Int::class.javaPrimitiveType)
        assertNotNull("必须声明 nativeInit JNI 契约", nativeInitMethod)
        assertTrue(Modifier.isNative(nativeInitMethod.modifiers))
        assertEquals(Long::class.javaPrimitiveType, nativeInitMethod.returnType)

        // 2. nativeClassifyIntent(long, String): int
        val nativeClassifyMethod = clazz.getDeclaredMethod("nativeClassifyIntent", Long::class.javaPrimitiveType, String::class.java)
        assertNotNull("必须声明 nativeClassifyIntent JNI 契约", nativeClassifyMethod)
        assertTrue(Modifier.isNative(nativeClassifyMethod.modifiers))
        assertEquals(Int::class.javaPrimitiveType, nativeClassifyMethod.returnType)

        // 3. nativePickObstaclePointer(long, String, String[]): int
        val nativePickMethod = clazz.getDeclaredMethod(
            "nativePickObstaclePointer",
            Long::class.javaPrimitiveType,
            String::class.java,
            Array<String>::class.java
        )
        assertNotNull("必须声明 nativePickObstaclePointer JNI 契约", nativePickMethod)
        assertTrue(Modifier.isNative(nativePickMethod.modifiers))
        assertEquals(Int::class.javaPrimitiveType, nativePickMethod.returnType)

        // 4. nativeMatchReplayPointer(long, String, String[]): int
        val nativeMatchMethod = clazz.getDeclaredMethod(
            "nativeMatchReplayPointer",
            Long::class.javaPrimitiveType,
            String::class.java,
            Array<String>::class.java
        )
        assertNotNull("必须声明 nativeMatchReplayPointer JNI 契约", nativeMatchMethod)
        assertTrue(Modifier.isNative(nativeMatchMethod.modifiers))
        assertEquals(Int::class.javaPrimitiveType, nativeMatchMethod.returnType)

        // 5. nativeRelease(long): void
        val nativeReleaseMethod = clazz.getDeclaredMethod("nativeRelease", Long::class.javaPrimitiveType)
        assertNotNull("必须声明 nativeRelease JNI 契约", nativeReleaseMethod)
        assertTrue(Modifier.isNative(nativeReleaseMethod.modifiers))
        assertEquals(Void.TYPE, nativeReleaseMethod.returnType)
    }

    @Test
    fun nativeLoadedStateAndSmoothFallbackToKotlinHeuristics() = runTest {
        val engine = MnnPocketEngine(context = null, dispatcher = Dispatchers.Unconfined)
        engine.forceReadyForTesting = true

        // 验证 isNativeLoaded 标志存在
        assertNotNull(MnnPocketEngine.isNativeLoaded)

        // 在 Native 库未载入环境下（如普通 JVM 单元测试环境），平滑降级至高精度 Kotlin 启发式引擎
        val intent = engine.classifyIntent("帮我打开微信发给张三")
        assertEquals("当 Native 库缺失时，必须平滑无感降级至纯 Kotlin 规则引擎", IntentGate.Intent.Act, intent)

        val candidates = listOf(
            EdgeDecisionPort.EdgeCandidate("@c1", "Button", "先逛逛"),
            EdgeDecisionPort.EdgeCandidate("@c2", "Button", "去充值")
        )
        val pickedRef = engine.pickObstacleDismissRef("逛街", "开通会员", candidates)
        assertEquals("@c1", pickedRef)

        // 验证主动清理会话不抛出异常
        engine.purgeActiveSessions()
    }
}
