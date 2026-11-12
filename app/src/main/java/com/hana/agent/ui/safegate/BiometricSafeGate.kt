package com.hana.agent.ui.safegate

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hana.agent.core.risk.IrrevAction
import com.hana.agent.ui.theme.HanaShapes
import com.hana.agent.ui.theme.HanaTheme
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.Base64
import kotlin.math.roundToInt

/**
 * T3 级高危操作分类：
 * 需要叠加「85% 防误触阻尼滑动 + 硬件生物特征/锁屏凭据认证」双因素协同门控。
 */
enum class T3RiskCategory(val labelZh: String) {
    TRANSFER("大额转账/汇款"),
    PAYMENT("支付结算/买单"),
    ACCOUNT_DELETE("删号/注销/不可逆清除"),
    SECURITY_OVERRIDE("安全权限/密码变更"),
}

/**
 * 硬件生物特征与设备凭据可用状态。
 */
enum class BiometricAvailability {
    /** 强/弱生物特征可用（指纹、面容等）。 */
    BIOMETRIC_READY,
    /** 无生物特征，但系统已启用锁屏 PIN/图案/密码凭据。 */
    DEVICE_CREDENTIAL_ONLY,
    /** 硬件支持但用户尚未录入任何生物特征或锁屏密码。 */
    NONE_ENROLLED,
    /** 设备无生物识别硬件。 */
    NO_HARDWARE,
    /** 运行环境受限或服务不可用（如无 Activity/Mock 上下文）。 */
    UNAVAILABLE,
}

/**
 * 当设备未录入生物特征或锁屏凭据时的降级策略。
 */
enum class BiometricFallbackPolicy {
    /** 严格模式：硬件未设防时拒绝自动放行，要求用户先设置系统锁屏/指纹。 */
    REQUIRE_DEVICE_AUTH,
    /** 兼容性模式：设备本身未设锁屏密码时，仅依赖 85% 阻尼滑动确认并记录降级标记。 */
    ALLOW_SLIDE_ONLY_IF_UNENROLLED,
}

/**
 * 单次生物特征验证请求载体，绑定操作摘要与防重放随机挑战码。
 */
data class BiometricGateRequest(
    val title: String,
    val subtitle: String,
    val description: String,
    val riskCategory: T3RiskCategory = T3RiskCategory.PAYMENT,
    val negativeButtonText: String = "取消",
    val allowDeviceCredentialFallback: Boolean = true,
    val challengeNonce: String = generateChallengeNonce(),
) {
    companion object {
        private val rng = SecureRandom()

        fun generateChallengeNonce(): String {
            val bytes = ByteArray(16)
            rng.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}

/**
 * 生物特征验证结果。
 */
sealed interface BiometricAuthResult {
    data class Success(
        val authType: String,
        val attestedNonce: String,
        val timestampMs: Long = System.currentTimeMillis(),
    ) : BiometricAuthResult

    data class Canceled(val reason: String = "user_canceled") : BiometricAuthResult
    data class Failed(val errorCode: Int, val message: String) : BiometricAuthResult
    data class Unavailable(val availability: BiometricAvailability, val message: String) : BiometricAuthResult
}

/**
 * 生物特征门控与阻尼滑动协同阶段状态。
 */
enum class BiometricGatePhase {
    /** 等待用户完成 85% 阻尼滑动。 */
    SLIDE_LOCKED,
    /** 滑动达标，正在调起系统原生生物特征/锁屏凭据弹窗。 */
    VERIFYING_BIOMETRIC,
    /** 双因素校验全部通过，准予放行。 */
    AUTHORIZED,
    /** 用户取消或主动拒绝。 */
    REJECTED,
    /** 校验失败或硬件未就绪，允许重新滑动重试。 */
    ERROR,
}

/**
 * T3 高危操作判定器：根据动作枚举与标题/描述判断是否必须强制开启生物特征硬件门控。
 */
object T3RiskClassifier {
    private val TRANSFER_KEYWORDS = listOf("转账", "汇款", "发红包", "转出", "提现", "transfer", "remit")
    private val PAYMENT_KEYWORDS = listOf("支付", "付款", "结账", "买单", "收银台", "充值", "付款确认", "pay", "checkout")
    private val DELETE_KEYWORDS = listOf("删号", "注销", "删除账号", "删除账户", "永久删除", "清空数据", "delete account")
    private val SECURITY_KEYWORDS = listOf("修改密码", "关闭验证", "关闭安全锁", "root", "授信")

    fun classify(
        irrevAction: IrrevAction? = null,
        title: String = "",
        description: String = "",
    ): T3RiskCategory? {
        val text = "$title $description".lowercase()
        if (DELETE_KEYWORDS.any { it in text } || irrevAction == IrrevAction.delete && ("账号" in text || "账户" in text || "注销" in text)) {
            return T3RiskCategory.ACCOUNT_DELETE
        }
        if (TRANSFER_KEYWORDS.any { it in text }) {
            return T3RiskCategory.TRANSFER
        }
        if (irrevAction == IrrevAction.pay || irrevAction == IrrevAction.order || PAYMENT_KEYWORDS.any { it in text }) {
            return T3RiskCategory.PAYMENT
        }
        if (SECURITY_KEYWORDS.any { it in text }) {
            return T3RiskCategory.SECURITY_OVERRIDE
        }
        return null
    }

    fun requiresBiometricGate(
        irrevAction: IrrevAction? = null,
        title: String = "",
        description: String = "",
    ): Boolean = classify(irrevAction, title, description) != null
}

/**
 * 抽象系统生物特征/锁屏凭据认证器，便于测试注入与跨机型适配。
 */
interface BiometricAuthenticator {
    fun checkAvailability(context: Context): BiometricAvailability

    fun authenticate(
        context: Context,
        request: BiometricGateRequest,
        onResult: (BiometricAuthResult) -> Unit,
    ): CancellationSignal?
}

/**
 * Android 原生系统级生物特征/锁屏凭据门控实现（基于 API 30+ 原生 `BiometricManager` + `BiometricPrompt` + `KeyguardManager`）。
 * 支持指纹、面容，以及在允许降级时自动无缝兼容系统锁屏 PIN/图案/密码认证。
 */
object AndroidBiometricAuthenticator : BiometricAuthenticator {
    private const val STRONG_OR_CREDENTIAL =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    private const val BIOMETRIC_ONLY =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK

    override fun checkAvailability(context: Context): BiometricAvailability {
        val bm = runCatching { context.getSystemService(BiometricManager::class.java) }.getOrNull()
        val km = runCatching { context.getSystemService(KeyguardManager::class.java) }.getOrNull()

        if (bm != null) {
            val bioStatus = runCatching { bm.canAuthenticate(BIOMETRIC_ONLY) }.getOrNull()
            if (bioStatus == BiometricManager.BIOMETRIC_SUCCESS) {
                return BiometricAvailability.BIOMETRIC_READY
            }
            val credStatus = runCatching { bm.canAuthenticate(STRONG_OR_CREDENTIAL) }.getOrNull()
            if (credStatus == BiometricManager.BIOMETRIC_SUCCESS) {
                return BiometricAvailability.DEVICE_CREDENTIAL_ONLY
            }
            return when (bioStatus) {
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                    if (km?.isDeviceSecure == true) BiometricAvailability.DEVICE_CREDENTIAL_ONLY
                    else BiometricAvailability.NONE_ENROLLED
                }
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                    if (km?.isDeviceSecure == true) BiometricAvailability.DEVICE_CREDENTIAL_ONLY
                    else BiometricAvailability.NO_HARDWARE
                }
                else -> {
                    if (km?.isDeviceSecure == true) BiometricAvailability.DEVICE_CREDENTIAL_ONLY
                    else BiometricAvailability.UNAVAILABLE
                }
            }
        }

        return if (km?.isDeviceSecure == true) {
            BiometricAvailability.DEVICE_CREDENTIAL_ONLY
        } else {
            BiometricAvailability.UNAVAILABLE
        }
    }

    override fun authenticate(
        context: Context,
        request: BiometricGateRequest,
        onResult: (BiometricAuthResult) -> Unit,
    ): CancellationSignal? {
        val availability = checkAvailability(context)
        if (availability == BiometricAvailability.NONE_ENROLLED ||
            availability == BiometricAvailability.NO_HARDWARE ||
            availability == BiometricAvailability.UNAVAILABLE
        ) {
            onResult(BiometricAuthResult.Unavailable(availability, "设备生物特征或锁屏凭据不可用: $availability"))
            return null
        }

        val executor = runCatching { context.mainExecutor }.getOrNull()
        if (executor == null) {
            onResult(BiometricAuthResult.Unavailable(BiometricAvailability.UNAVAILABLE, "当前上下文缺少主线程执行器"))
            return null
        }

        return try {
            val builder = BiometricPrompt.Builder(context)
                .setTitle(request.title.ifBlank { "Hana 安全生物特征核验" })
                .setSubtitle(request.subtitle.ifBlank { request.riskCategory.labelZh })
                .setDescription(request.description)

            val useCredentialFallback = request.allowDeviceCredentialFallback ||
                availability == BiometricAvailability.DEVICE_CREDENTIAL_ONLY

            if (useCredentialFallback) {
                // API 30+ 允许 DEVICE_CREDENTIAL 时严禁再调用 setNegativeButton，否则系统直接抛 IllegalArgumentException
                builder.setAllowedAuthenticators(STRONG_OR_CREDENTIAL)
            } else {
                builder.setAllowedAuthenticators(BIOMETRIC_ONLY)
                builder.setNegativeButton(request.negativeButtonText, executor) { _, _ ->
                    onResult(BiometricAuthResult.Canceled("negative_button"))
                }
            }

            val cancellationSignal = CancellationSignal()
            val prompt = builder.build()
            prompt.authenticate(
                cancellationSignal,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        val method = when (result?.authenticationType) {
                            BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC -> "biometric"
                            BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL -> "device_credential"
                            else -> "hardware_verified"
                        }
                        onResult(
                            BiometricAuthResult.Success(
                                authType = method,
                                attestedNonce = request.challengeNonce,
                            )
                        )
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED ||
                            errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                        ) {
                            onResult(BiometricAuthResult.Canceled(errString?.toString().orEmpty().ifEmpty { "canceled" }))
                        } else {
                            onResult(
                                BiometricAuthResult.Failed(
                                    errorCode = errorCode,
                                    message = errString?.toString().orEmpty().ifEmpty { "认证失败 ($errorCode)" },
                                )
                            )
                        }
                    }

                    override fun onAuthenticationFailed() {
                        // 单次指纹/面容不匹配时系统弹窗会保持并提示重试，此处仅保留回调能力不提前终结
                    }
                },
            )
            cancellationSignal
        } catch (se: SecurityException) {
            onResult(BiometricAuthResult.Unavailable(BiometricAvailability.UNAVAILABLE, se.message ?: "缺少生物特征权限"))
            null
        } catch (t: Throwable) {
            onResult(BiometricAuthResult.Failed(-1, t.message ?: "调起系统验证失败"))
            null
        }
    }
}

/**
 * T3 双因素安全门控状态协同器：
 * 将 85% 防误触阻尼滑动与硬件生物特征验证串联为原子状态机，防止未滑动或未认证的越权放行。
 */
class BiometricSafeGateController(
    private val authenticator: BiometricAuthenticator = AndroidBiometricAuthenticator,
    private val fallbackPolicy: BiometricFallbackPolicy = BiometricFallbackPolicy.REQUIRE_DEVICE_AUTH,
) {
    var phase: BiometricGatePhase = BiometricGatePhase.SLIDE_LOCKED
        private set

    var lastErrorMessage: String? = null
        private set

    var lastSuccess: BiometricAuthResult.Success? = null
        private set

    private var activeCancellation: CancellationSignal? = null

    /**
     * 当用户完成 ≥85% 防误触阻尼滑动时调用，进入生物特征/锁屏凭据校验阶段。
     */
    fun onSlideThresholdReached(
        context: Context,
        request: BiometricGateRequest,
        slideFraction: Float,
        onAuthorized: (BiometricAuthResult.Success) -> Unit,
        onPhaseChanged: (BiometricGatePhase, String?) -> Unit = { _, _ -> },
    ) {
        if (slideFraction < SLIDE_AUTH_THRESHOLD) {
            SafeGateHaptics.triggerElasticSnap(context)
            phase = BiometricGatePhase.SLIDE_LOCKED
            onPhaseChanged(phase, null)
            return
        }
        if (phase == BiometricGatePhase.VERIFYING_BIOMETRIC || phase == BiometricGatePhase.AUTHORIZED) {
            return
        }

        SafeGateHaptics.triggerGateWarning(context)
        val availability = authenticator.checkAvailability(context)
        if ((availability == BiometricAvailability.NONE_ENROLLED ||
                availability == BiometricAvailability.NO_HARDWARE ||
                availability == BiometricAvailability.UNAVAILABLE) &&
            fallbackPolicy == BiometricFallbackPolicy.ALLOW_SLIDE_ONLY_IF_UNENROLLED
        ) {
            val attested = BiometricAuthResult.Success(
                authType = "slide_fallback_unenrolled",
                attestedNonce = request.challengeNonce,
            )
            lastSuccess = attested
            lastErrorMessage = null
            phase = BiometricGatePhase.AUTHORIZED
            SafeGateHaptics.triggerSuccessBloom(context)
            onPhaseChanged(phase, null)
            onAuthorized(attested)
            return
        }

        phase = BiometricGatePhase.VERIFYING_BIOMETRIC
        lastErrorMessage = null
        onPhaseChanged(phase, null)

        activeCancellation = authenticator.authenticate(context, request) { result ->
            activeCancellation = null
            when (result) {
                is BiometricAuthResult.Success -> {
                    if (result.attestedNonce != request.challengeNonce) {
                        phase = BiometricGatePhase.ERROR
                        lastErrorMessage = "安全挑战校验不匹配，已拒绝放行"
                        SafeGateHaptics.triggerGateWarning(context)
                        onPhaseChanged(phase, lastErrorMessage)
                    } else {
                        phase = BiometricGatePhase.AUTHORIZED
                        lastSuccess = result
                        lastErrorMessage = null
                        SafeGateHaptics.triggerSuccessBloom(context)
                        onPhaseChanged(phase, null)
                        onAuthorized(result)
                    }
                }
                is BiometricAuthResult.Canceled -> {
                    phase = BiometricGatePhase.SLIDE_LOCKED
                    lastErrorMessage = "已取消生物特征验证，请重新滑动确认"
                    SafeGateHaptics.triggerElasticSnap(context)
                    onPhaseChanged(phase, lastErrorMessage)
                }
                is BiometricAuthResult.Failed -> {
                    phase = BiometricGatePhase.ERROR
                    lastErrorMessage = result.message
                    SafeGateHaptics.triggerGateWarning(context)
                    onPhaseChanged(phase, lastErrorMessage)
                }
                is BiometricAuthResult.Unavailable -> {
                    phase = BiometricGatePhase.ERROR
                    lastErrorMessage = result.message
                    SafeGateHaptics.triggerGateWarning(context)
                    onPhaseChanged(phase, lastErrorMessage)
                }
            }
        }
    }

    fun reject(context: Context, onRejected: () -> Unit) {
        runCatching { activeCancellation?.cancel() }
        activeCancellation = null
        phase = BiometricGatePhase.REJECTED
        lastErrorMessage = null
        SafeGateHaptics.triggerElasticSnap(context)
        onRejected()
    }

    fun reset() {
        runCatching { activeCancellation?.cancel() }
        activeCancellation = null
        phase = BiometricGatePhase.SLIDE_LOCKED
        lastErrorMessage = null
    }

    companion object {
        const val SLIDE_AUTH_THRESHOLD = 0.85f
    }
}

/**
 * T3 高危操作生物特征增强门控卡片：
 * 保留 85% 防误触物理阻尼滑动，滑动达标后自动拉起 Android 系统原生指纹/面容或锁屏凭据验证，
 * 仅在硬件认证成功后才触发 [onAuthorized] 放行。
 */
@Composable
fun BiometricSlideToAuthorize(
    onAuthorized: (BiometricAuthResult.Success) -> Unit,
    onRejected: () -> Unit,
    title: String,
    riskDescription: String,
    riskCategory: T3RiskCategory = T3RiskCategory.PAYMENT,
    fallbackPolicy: BiometricFallbackPolicy = BiometricFallbackPolicy.REQUIRE_DEVICE_AUTH,
    authenticator: BiometricAuthenticator = AndroidBiometricAuthenticator,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val c = HanaTheme.colors
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(20.dp)

    val controller = remember(authenticator, fallbackPolicy) {
        BiometricSafeGateController(authenticator, fallbackPolicy)
    }
    var phase by remember { mutableStateOf(controller.phase) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val transition = rememberInfiniteTransition(label = "T3BiometricGateHalo")
    val haloPulse by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "t3HaloPulse",
    )

    val t3CrimsonAmber = Color(0xFFEA580C)

    Column(
        modifier = modifier
            .testTag("t3-biometric-safe-gate-card")
            .fillMaxWidth()
            .drawBehind {
                val strokePx = 4.dp.toPx()
                drawRoundRect(
                    color = t3CrimsonAmber.copy(alpha = haloPulse * 0.5f),
                    size = size,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()),
                    style = Stroke(width = strokePx),
                )
            }
            .clip(shape)
            .background(c.surface)
            .border(1.dp, t3CrimsonAmber.copy(alpha = 0.55f), shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(t3CrimsonAmber.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("🔐", style = TextStyle(fontSize = 18.sp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title.ifEmpty { "T3 高危操作生物特征授权" },
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                    color = c.ink,
                )
                Text(
                    "${riskCategory.labelZh} · 指纹/面容二次核验",
                    style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
                    color = t3CrimsonAmber,
                )
            }
        }

        Text(
            riskDescription.ifEmpty { "此操作属于高危不可逆动作，请向右滑动并完成系统指纹/面容验证后放行。" },
            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            color = c.ink2,
        )

        if (!statusMessage.isNullOrBlank()) {
            Text(
                statusMessage!!,
                style = TextStyle(fontSize = 12.sp),
                color = t3CrimsonAmber,
                modifier = Modifier.testTag("t3-biometric-status"),
            )
        }

        var trackWidthPx by remember { mutableFloatStateOf(0f) }
        val thumbSize = 48.dp
        val density = LocalDensity.current
        val thumbSizePx = with(density) { thumbSize.toPx() }
        val maxDragPx = (trackWidthPx - thumbSizePx).coerceAtLeast(0f)
        val dragOffset = remember { Animatable(0f) }

        val sliderLabel = when (phase) {
            BiometricGatePhase.SLIDE_LOCKED, BiometricGatePhase.ERROR -> "滑动并验证指纹/面容 ❯❯❯"
            BiometricGatePhase.VERIFYING_BIOMETRIC -> "正在核验生物特征…"
            BiometricGatePhase.AUTHORIZED -> "硬件验证通过 ✓"
            BiometricGatePhase.REJECTED -> "已拒绝执行"
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(HanaShapes.Pill)
                .background(c.fill)
                .border(1.dp, c.edge, HanaShapes.Pill)
                .onSizeChanged { trackWidthPx = it.width.toFloat() }
                .testTag("t3-slide-track"),
            contentAlignment = Alignment.CenterStart,
        ) {
            val dragFraction = if (maxDragPx > 0) (dragOffset.value / maxDragPx).coerceIn(0f, 1f) else 0f
            Text(
                sliderLabel,
                style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                color = t3CrimsonAmber.copy(alpha = (1f - dragFraction).coerceIn(0.25f, 1f)),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )

            Box(
                Modifier
                    .offset { IntOffset(dragOffset.value.roundToInt(), 0) }
                    .padding(4.dp)
                    .size(thumbSize)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(t3CrimsonAmber)
                    .draggable(
                        enabled = phase == BiometricGatePhase.SLIDE_LOCKED || phase == BiometricGatePhase.ERROR,
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            scope.launch {
                                val prev = dragOffset.value
                                val next = (prev + delta).coerceIn(0f, maxDragPx)
                                val threshold = maxDragPx * BiometricSafeGateController.SLIDE_AUTH_THRESHOLD
                                if (prev < threshold && next >= threshold && maxDragPx > 0) {
                                    SafeGateHaptics.triggerLightTick(context)
                                }
                                dragOffset.snapTo(next)
                            }
                        },
                        onDragStopped = {
                            val fraction = if (maxDragPx > 0f) dragOffset.value / maxDragPx else 0f
                            if (maxDragPx > 0f && fraction >= BiometricSafeGateController.SLIDE_AUTH_THRESHOLD) {
                                scope.launch {
                                    dragOffset.animateTo(maxDragPx, tween(100))
                                    val req = BiometricGateRequest(
                                        title = title.ifEmpty { "Hana 高危操作授权" },
                                        subtitle = riskCategory.labelZh,
                                        description = riskDescription,
                                        riskCategory = riskCategory,
                                    )
                                    controller.onSlideThresholdReached(
                                        context = context,
                                        request = req,
                                        slideFraction = fraction,
                                        onAuthorized = onAuthorized,
                                        onPhaseChanged = { nextPhase, msg ->
                                            phase = nextPhase
                                            statusMessage = msg
                                            if (nextPhase == BiometricGatePhase.SLIDE_LOCKED || nextPhase == BiometricGatePhase.ERROR) {
                                                scope.launch { dragOffset.animateTo(0f, tween(250)) }
                                            }
                                        },
                                    )
                                }
                            } else {
                                scope.launch { dragOffset.animateTo(0f, tween(250)) }
                            }
                        },
                    )
                    .testTag("t3-slide-thumb"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (phase == BiometricGatePhase.AUTHORIZED) "✓" else "➔",
                    style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { controller.reject(context, onRejected) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag("t3-safe-gate-reject"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "拒绝执行",
                    style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
                    color = c.ink3,
                )
            }
        }
    }
}
