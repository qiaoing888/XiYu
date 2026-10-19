package com.keyscafe.unlock

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** 键盘语音丝绸动效视图 */
class KeyboardMeltView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    // 画笔
    private val frostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val warmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bloomMistPaints = Array(BLOOM_COUNT) { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    private val veilPaints = Array(RIBBONS) { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    private val glowPaints = Array(RIBBONS) { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    } }
    private val hazePaints = Array(RIBBONS) { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    } }
    private val ridgePaints = Array(RIBBONS) { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    } }

    // Path 与矩阵
    private val veilPaths = Array(RIBBONS) { Path() }
    private val ridgePaths = Array(RIBBONS) { Path() }
    private val warmMatrix = Matrix()
    private val mistMatrices = Array(BLOOM_COUNT) { Matrix() }
    private val glowMatrices = Array(RIBBONS) { Matrix() }

    // Shader 槽位
    private var bandShader: Shader? = null
    private var rimShader: Shader? = null
    private var warmShader: RadialGradient? = null
    private val mistShaders = arrayOfNulls<RadialGradient>(BLOOM_COUNT)
    private val veilMasks = arrayOfNulls<LinearGradient>(RIBBONS)
    private val veilShaders = arrayOfNulls<ComposeShader>(RIBBONS)

    // 几何采样
    private val sampleX = FloatArray(SAMPLES)
    private val env = FloatArray(SAMPLES)
    private val ribbonY = Array(RIBBONS) { FloatArray(SAMPLES) }

    // 状态
    private var nightMode = false

    private var frostTint = Color.argb(224, 0xF5, 0xF6, 0xFA)
    fun setFrostTint(color: Int) { frostTint = color; invalidateAll() }

    private var kbTop = 0f
    private var kbBottom = 0f
    fun setKeyboardRange(top: Float, bottom: Float) {
        kbTop = top; kbBottom = bottom; invalidateAll()
    }

    @Volatile private var pendingLevel = 0f

    private var smoothVol = 0f
    private var spread = SPREAD_MIN
    private var phase = 0f
    private var breathPhase = 0f
    private var lastFrameAt = 0L
    private var drawFailures = 0

    private var peakRms = 0
    private var peakNorm = 0f

    @Volatile private var running = false

    private var dirtyTop = -1f
    private var dirtyBottom = -1f

    // 音量输入（0~100）
    fun setLevel(rms: Int) {
        val norm = ((rms - RMS_FLOOR) / (RMS_FULL - RMS_FLOOR)).coerceIn(0f, 1f)
        if (rms > peakRms) peakRms = rms
        if (norm > peakNorm) peakNorm = norm
        pendingLevel = norm
    }

    // 重置音量
    fun reset() {
        if (peakRms > 0 || peakNorm > 0f) {
            XLog.i("本次会话音量：最大原始 $peakRms（归一化后峰值 ${"%.2f".format(peakNorm)}）")
            peakRms = 0
            peakNorm = 0f
        }
        pendingLevel = 0f
        smoothVol = 0f
        spread = SPREAD_MIN
        phase = 0f
        breathPhase = 0f
        lastFrameAt = 0L
    }

    fun fadeIn() {
        animate().cancel()
        alpha = 0f
        lastFrameAt = SystemClock.uptimeMillis()
        running = true
        invalidateAll()
        animate().alpha(1f).setDuration(220L).start()
    }

    fun fadeOut(onEnd: () -> Unit) {
        animate().cancel()
        animate().alpha(0f).setDuration(240L).withEndAction {
            running = false
            alpha = 1f
            onEnd()
        }.start()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    // 尺寸与主题变化

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0) return
        nightMode = isNight()
        buildGeometry(w)
        buildShaders(w)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val night = isNight()
        if (night != nightMode) {
            nightMode = night
            buildShaders(width)
            invalidateAll()
        }
    }

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun buildGeometry(w: Int) {
        val wf = w.toFloat()
        for (i in 0 until SAMPLES) {
            val nx = i.toFloat() / (SAMPLES - 1)
            sampleX[i] = nx * wf
            // 避免负数 pow 产生 NaN
            env[i] = sin(nx * PI_F).coerceAtLeast(0f).pow(ENV_POW)
        }
    }

    private fun buildShaders(w: Int) {
        if (w <= 0) return
        val wf = w.toFloat()

        // 横向色带
        val palette = if (nightMode) PALETTE_NIGHT else PALETTE_DAY
        val stops = if (nightMode) STOPS_NIGHT else STOPS_DAY
        bandShader = LinearGradient(
            0f, 0f, wf, 0f,
            intArrayOf(
                palette[0] and 0x00FFFFFF, palette[0],
                palette[1], palette[2], palette[2] and 0x00FFFFFF
            ),
            stops, Shader.TileMode.CLAMP
        )

        // 脊线亮芯
        val rimCenter = if (nightMode) Color.parseColor("#F2ECFF") else RIM
        rimShader = LinearGradient(
            0f, 0f, wf, 0f,
            intArrayOf(rimCenter and 0x00FFFFFF, rimCenter, rimCenter, rimCenter and 0x00FFFFFF),
            floatArrayOf(0f, 0.16f, 0.84f, 1f), Shader.TileMode.CLAMP
        )

        // 底部暖光
        val sCore = if (nightMode) Color.argb(58, 0xB8, 0xA0, 0xFF) else Color.argb(30, 0xFF, 0xFF, 0xFF)
        val sMid = if (nightMode) Color.argb(30, 0x9D, 0x5C, 0xFF) else Color.argb(18, 0xFF, 0xC6, 0xA0)
        val sOut = if (nightMode) Color.argb(12, 0xFF, 0x5C, 0x9E) else Color.argb(8, 0xBE, 0x8E, 0xFF)
        val warmStops = if (nightMode) floatArrayOf(0f, 0.36f, 0.70f, 1f)
        else floatArrayOf(0f, 0.38f, 0.72f, 1f)
        warmShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(sCore, sMid, sOut, 0x00000000),
            warmStops, Shader.TileMode.CLAMP
        )
        warmPaint.shader = warmShader
        warmPaint.xfermode = screenXfermode()

        // 极光光晕
        for (i in 0 until BLOOM_COUNT) {
            val c = palette[i]
            mistShaders[i] = if (nightMode) {
                RadialGradient(
                    0f, 0f, 1f,
                    intArrayOf(
                        withAlpha(c, 0xFF),
                        withAlpha(c, 0x6B),
                        withAlpha(c, 0x12),
                        withAlpha(c, 0x00)
                    ),
                    floatArrayOf(0f, 0.36f, 0.70f, 1f), Shader.TileMode.CLAMP
                )
            } else {
                RadialGradient(
                    0f, 0f, 1f,
                    intArrayOf(withAlpha(c, 0xFF), withAlpha(c, 0x6B), withAlpha(c, 0x00)),
                    floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP
                )
            }
            bloomMistPaints[i].shader = mistShaders[i]
        }

        // 丝绸描边
        for (i in 0 until RIBBONS) {
            veilMasks[i] = LinearGradient(
                0f, 0f, 0f, 1f,
                intArrayOf(-0x1, 0x38FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
            )
            val fill = if (COLORED_RIBBONS) bandShader!! else rimShader!!
            veilShaders[i] = ComposeShader(fill, veilMasks[i]!!, PorterDuff.Mode.DST_IN)
            veilPaints[i].shader = veilShaders[i]
            veilPaints[i].xfermode = screenXfermode()

            glowPaints[i].shader = fill
            glowPaints[i].strokeWidth = GLOW_WIDTH_DP[i] * density
            glowPaints[i].xfermode = screenXfermode()

            hazePaints[i].shader = fill
            hazePaints[i].strokeWidth = HAZE_WIDTH_DP[i] * density
            hazePaints[i].xfermode = screenXfermode()

            ridgePaints[i].shader = rimShader
            ridgePaints[i].strokeWidth = CORE_WIDTH_DP[i] * density
        }
    }

    private fun screenXfermode(): PorterDuffXfermode? =
        if (USE_SCREEN_BLEND && nightMode) SCREEN_XFER else null

    private fun withAlpha(color: Int, a: Int): Int =
        (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        if (kbBottom <= kbTop) return
        try {
            drawMelt(canvas)
        } catch (t: Throwable) {
            running = false
            if (drawFailures < DRAW_FAILURE_LOGS) {
                drawFailures++
                XLog.e("融化层绘制失败，已退回底幕（第 $drawFailures 次）", t)
            }
            runCatching {
                frostPaint.color = frostTint
                canvas.drawRect(0f, kbTop, width.toFloat(), kbBottom, frostPaint)
            }
        }
    }

    private fun drawMelt(canvas: Canvas) {

        // 计算音量与相位
        val now = SystemClock.uptimeMillis()
        if (lastFrameAt == 0L) lastFrameAt = now
        val dt = ((now - lastFrameAt) / 1000f).coerceIn(0.001f, 0.05f)
        lastFrameAt = now

        val attack = 1f - exp(-dt / ATTACK_S)
        val release = 1f - exp(-dt / RELEASE_S)
        val target = pendingLevel
        smoothVol += (target - smoothVol) * (if (target > smoothVol) attack else release)

        breathPhase = (breathPhase + dt * BREATH_RATE) % TWO_PI_F
        val vol = max(smoothVol, BREATH_BASE + BREATH_SWING * sin(breathPhase))

        val targetSpread = (SPREAD_MIN + vol * 1.15f).coerceIn(SPREAD_MIN, 1f)
        spread += (targetSpread - spread) *
            (if (targetSpread > spread) attack else release * 0.8f)

        phase = (phase + dt * (PHASE_IDLE + vol * PHASE_GAIN)) % PHASE_WRAP

        val kbH = kbBottom - kbTop
        val baseY = kbTop + kbH * (BASEY_IDLE - BASEY_TIDE * vol)
        val amp = (AMP_IDLE_DP + AMP_GAIN_DP * vol) * density

        // 底幕
        frostPaint.color = frostTint
        canvas.drawRect(0f, kbTop, width.toFloat(), kbBottom, frostPaint)

        // 底部暖光
        val warmCy = kbBottom - kbH * 0.06f
        val warmRy = kbH * (
            if (nightMode) WARM_RY_MIN_NIGHT + WARM_RY_GAIN_NIGHT * vol
            else WARM_RY_MIN + WARM_RY_GAIN * vol
            )
        val warmRx = width * (0.58f + 0.12f * vol)
        warmMatrix.reset()
        warmMatrix.postScale(warmRx, warmRy)
        warmMatrix.postTranslate(width * 0.5f, warmCy)
        warmShader?.setLocalMatrix(warmMatrix)
        warmPaint.alpha = if (nightMode) (110 + 40 * vol).toInt().coerceIn(0, 255)
        else (90 + 30 * vol).toInt().coerceIn(0, 255)
        canvas.drawRect(
            0f, (warmCy - warmRy).coerceAtLeast(kbTop), width.toFloat(), kbBottom, warmPaint
        )

        // 计算丝绸波形
        val meanY = XyCore.computeSilkWaves(
            ribbonY[0], ribbonY[1], ribbonY[2], env,
            SAMPLES, baseY, amp, spread, phase, SPREAD_STEP_DP * density
        )

        // 极光光晕
        val mistRy = kbH * (0.16f + 0.16f * vol)
        val mistRx = width * (0.36f + 0.08f * vol)
        for (i in 0 until BLOOM_COUNT) {
            val cx = width * MIST_X[i]
            mistMatrices[i].reset()
            mistMatrices[i].postScale(mistRx * MIST_SIZE[i], mistRy)
            mistMatrices[i].postTranslate(cx, meanY)
            mistShaders[i]?.setLocalMatrix(mistMatrices[i])
            bloomMistPaints[i].alpha = ((0.30f + 0.20f * vol) * 255f).toInt().coerceIn(0, 255)
            bloomMistPaints[i].xfermode = screenXfermode()
            canvas.drawCircle(cx, meanY, mistRx * MIST_SIZE[i], bloomMistPaints[i])
        }

        // 丝绸绘制
        val ribbonGain = if (nightMode) DARK_RIBBON_GAIN else 1f
        val veilGain = if (nightMode) DARK_VEIL_GAIN else 1f
        val coreGain = if (nightMode) CORE_GAIN_NIGHT else CORE_GAIN_DAY
        val fadeH = (if (nightMode) RIBBON_FADE_DP_NIGHT else RIBBON_FADE_DP) * density
        for (r in 0 until RIBBONS) {
            val ys = ribbonY[r]
            val veil = veilPaths[r]
            val ridge = ridgePaths[r]

            buildSmoothPath(veil, ys)
            buildSmoothPath(ridge, ys)

            var minY = ys[0]
            for (i in 1 until SAMPLES) if (ys[i] < minY) minY = ys[i]
            veil.lineTo(width.toFloat(), minY + fadeH)
            veil.lineTo(0f, minY + fadeH)
            veil.close()

            glowMatrices[r].reset()
            glowMatrices[r].postScale(1f, fadeH)
            glowMatrices[r].postTranslate(0f, minY)
            veilMasks[r]?.setLocalMatrix(glowMatrices[r])

            veilPaints[r].alpha = (VEIL_ALPHA[r] * veilGain).toInt().coerceIn(0, 255)
            canvas.drawPath(veil, veilPaints[r])

            hazePaints[r].alpha = ((60 + 80 * vol) * coreGain[r] * ribbonGain).toInt().coerceIn(0, 255)
            canvas.drawPath(ridge, hazePaints[r])
            glowPaints[r].alpha = ((95 + 110 * vol) * coreGain[r] * ribbonGain).toInt().coerceIn(0, 255)
            canvas.drawPath(ridge, glowPaints[r])

            ridgePaints[r].alpha = ((170 + 85 * vol) * coreGain[r] * ribbonGain).toInt().coerceIn(0, 255)
            canvas.drawPath(ridge, ridgePaints[r])
        }

        // 局部重绘
        val warmTop = warmCy - warmRy
        val silkTop = baseY - amp - 2f * SPRING_PAD_DP * density
        dirtyTop = min(warmTop, silkTop).coerceAtLeast(kbTop)
        dirtyBottom = kbBottom

        if (running) {
            if (dirtyTop < 0f) postInvalidateOnAnimation()
            else postInvalidateOnAnimation(0, dirtyTop.toInt(), width, dirtyBottom.toInt())
        }
    }

    // Catmull-Rom 样条插值
    private fun buildSmoothPath(path: Path, ys: FloatArray) {
        path.reset()
        path.moveTo(sampleX[0], ys[0])
        for (i in 0 until SAMPLES - 1) {
            val p0 = if (i > 0) ys[i - 1] else ys[i]
            val p1 = ys[i]
            val p2 = ys[i + 1]
            val p3 = if (i + 2 < SAMPLES) ys[i + 2] else ys[i + 1]
            val c1 = p1 + (p2 - p0) * ONE_SIXTH
            val c2 = p2 - (p3 - p1) * ONE_SIXTH
            val x1 = sampleX[i]
            val x2 = sampleX[i + 1]
            path.cubicTo(x1 + (x2 - x1) * ONE_THIRD, c1, x2 - (x2 - x1) * ONE_THIRD, c2, x2, p2)
        }
    }

    private fun invalidateAll() {
        dirtyTop = -1f
        invalidate()
    }

    private companion object {
        // 采样参数
        const val RIBBONS = 3
        const val BLOOM_COUNT = 3
        const val SAMPLES = 40

        const val PI_F = 3.1415927f
        const val TWO_PI_F = 6.2831855f
        const val ONE_SIXTH = 1f / 6f
        const val ONE_THIRD = 1f / 3f
        const val ENV_POW = 0.48f

        const val RMS_FLOOR = 20f
        const val RMS_FULL = 68f

        // 阻尼响应
        const val ATTACK_S = 0.045f
        const val RELEASE_S = 0.280f

        // 呼吸与波动参数
        const val BREATH_RATE = 1.57f
        const val BREATH_BASE = 0.04f
        const val BREATH_SWING = 0.03f
        const val PHASE_IDLE = 0.65f
        const val PHASE_GAIN = 2.55f
        const val PHASE_WRAP = 62.83f

        // 几何参数 (dp)
        const val AMP_IDLE_DP = 6.5f
        const val AMP_GAIN_DP = 34f
        const val SPREAD_STEP_DP = 8f
        const val SPRING_PAD_DP = 24f
        const val RIBBON_FADE_DP = 120f
        const val RIBBON_FADE_DP_NIGHT = 45f
        const val WARM_RY_MIN = 0.50f
        const val WARM_RY_GAIN = 0.20f
        const val WARM_RY_MIN_NIGHT = 0.34f
        const val WARM_RY_GAIN_NIGHT = 0.14f

        const val DARK_RIBBON_GAIN = 0.62f
        const val DARK_VEIL_GAIN = 0.85f
        const val BASEY_IDLE = 0.50f
        const val BASEY_TIDE = 0.08f

        // 配色方案
        val PALETTE_DAY = intArrayOf(
            Color.parseColor("#7CE8FF"),
            Color.parseColor("#C8B6FF"),
            Color.parseColor("#FFC8A2"),
        )
        val PALETTE_NIGHT = intArrayOf(
            Color.parseColor("#00D8FF"),
            Color.parseColor("#9D5CFF"),
            Color.parseColor("#FF5C9E"),
        )

        const val EDGE_FADE = 0.18f

        val STOPS_DAY = floatArrayOf(0f, EDGE_FADE, 0.62f, 0.90f, 1f)
        val STOPS_NIGHT = floatArrayOf(0f, 0.14f, 0.44f, 0.86f, 1f)

        val RIM = Color.parseColor("#FFFBF5")
        val MIST_X = floatArrayOf(0.22f, 0.50f, 0.79f)
        val MIST_SIZE = floatArrayOf(0.50f, 0.55f, 0.50f)
        const val SPREAD_MIN = 0.15f
        const val DRAW_FAILURE_LOGS = 3

        val GLOW_WIDTH_DP = floatArrayOf(10f, 10f, 12f)
        val HAZE_WIDTH_DP = floatArrayOf(5f, 5f, 6f)
        val CORE_WIDTH_DP = floatArrayOf(1.1f, 1.45f, 1.75f)
        val CORE_GAIN_DAY = floatArrayOf(0.80f, 0.92f, 1f)
        val CORE_GAIN_NIGHT = floatArrayOf(0.60f, 0.78f, 1f)
        val VEIL_ALPHA = intArrayOf(96, 150, 200)

        const val COLORED_RIBBONS = true
        const val USE_SCREEN_BLEND = true

        val SCREEN_XFER = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
}
