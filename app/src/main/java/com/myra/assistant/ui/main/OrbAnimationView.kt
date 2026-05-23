package com.myra.assistant.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.*

class OrbAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class State { IDLE, LISTENING, SPEAKING, THINKING }

    private var currentState = State.IDLE
    private var amplitude = 0f

    // Animators
    private val pulseAnimator = ValueAnimator.ofFloat(1f, 1.15f, 1f).apply {
        duration = 1500; repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }
    private val rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 3000; repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }
    private val waveAnimator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 1200; repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }
    private val thinkingAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 800; repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }
    private val particleAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 4000; repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        pulseAnimator.start()
        rotationAnimator.start()
        waveAnimator.start()
        thinkingAnimator.start()
        particleAnimator.start()
    }

    fun setState(state: State) {
        currentState = state
        invalidate()
    }

    fun setAmplitude(amp: Float) {
        amplitude = amp
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = minOf(width, height) / 2f * 0.6f

        val scale = pulseAnimator.animatedValue as Float
        val radius = if (currentState == State.IDLE)
            baseRadius * scale else baseRadius

        val rotation = rotationAnimator.animatedValue as Float
        val waveOffset = waveAnimator.animatedValue as Float
        val thinkAngle = thinkingAnimator.animatedValue as Float
        val particleAngle = particleAnimator.animatedValue as Float

        // ─── Colors by State ──────────────────────
        val coreColor1: Int
        val coreColor2: Int
        when (currentState) {
            State.IDLE -> {
                coreColor1 = Color.parseColor("#B71C1C")
                coreColor2 = Color.parseColor("#880E4F")
            }
            State.LISTENING -> {
                coreColor1 = Color.parseColor("#FF1744")
                coreColor2 = Color.parseColor("#D500F9")
            }
            State.SPEAKING -> {
                coreColor1 = Color.parseColor("#E040FB")
                coreColor2 = Color.parseColor("#FF1744")
            }
            State.THINKING -> {
                coreColor1 = Color.parseColor("#40C4FF")
                coreColor2 = Color.parseColor("#00B0FF")
            }
        }

        // ─── 1. Radial Glow ───────────────────────
        paint.shader = RadialGradient(
            cx, cy, radius * 1.6f,
            intArrayOf(coreColor1 and 0x55FFFFFF, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 1.6f, paint)

        // ─── 2. Core Orb ──────────────────────────
        paint.shader = RadialGradient(
            cx - radius * 0.3f, cy - radius * 0.3f, radius,
            intArrayOf(coreColor1, coreColor2),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, paint)

        // ─── 3. Rotating Rings ────────────────────
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)

        for (i in 0..2) {
            paint.color = coreColor1 and 0x66FFFFFF or ((80 + i * 30) shl 24)
            canvas.save()
            canvas.rotate(rotation + i * 60f, cx, cy)
            canvas.drawOval(
                cx - radius * (1.1f + i * 0.15f),
                cy - radius * (0.4f + i * 0.05f),
                cx + radius * (1.1f + i * 0.15f),
                cy + radius * (0.4f + i * 0.05f),
                paint
            )
            canvas.restore()
        }

        paint.pathEffect = null

        // ─── 4. Wave Rings ────────────────────────
        if (currentState != State.IDLE) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            paint.color = coreColor1 and 0x44FFFFFF or (0x44 shl 24)
            for (ring in 1..3) {
                val waveRadius = radius * (1.2f + ring * 0.2f) +
                        amplitude * 20f * sin(waveOffset + ring)
                canvas.drawCircle(cx, cy, waveRadius, paint)
            }
        }

        // ─── 5. Thinking Arc ──────────────────────
        if (currentState == State.THINKING) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = Color.parseColor("#40C4FF")
            val rect = RectF(
                cx - radius * 1.3f, cy - radius * 1.3f,
                cx + radius * 1.3f, cy + radius * 1.3f
            )
            canvas.drawArc(rect, thinkAngle, 120f, false, paint)
            paint.color = Color.parseColor("#00B0FF")
            canvas.drawArc(rect, thinkAngle + 180f, 120f, false, paint)
        }

        // ─── 6. Particles ─────────────────────────
        if (currentState == State.SPEAKING || currentState == State.LISTENING) {
            paint.style = Paint.Style.FILL
            paint.shader = null
            for (i in 0..11) {
                val angle = Math.toRadians(
                    (particleAngle + i * 30f).toDouble()
                )
                val dist = radius * (1.4f + amplitude * 0.3f)
                val px = cx + (dist * cos(angle)).toFloat()
                val py = cy + (dist * sin(angle)).toFloat()
                paint.color = if (i % 2 == 0) coreColor1 else coreColor2
                paint.alpha = 180
                canvas.drawCircle(px, py, 4f, paint)
            }
        }

        // ─── 7. Inner Highlight ───────────────────
        paint.shader = RadialGradient(
            cx - radius * 0.35f, cy - radius * 0.35f,
            radius * 0.5f,
            intArrayOf(0x55FFFFFF, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.style = Paint.Style.FILL
        canvas.drawCircle(
            cx - radius * 0.2f,
            cy - radius * 0.2f,
            radius * 0.4f, paint
        )

        paint.shader = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator.cancel()
        rotationAnimator.cancel()
        waveAnimator.cancel()
        thinkingAnimator.cancel()
        particleAnimator.cancel()
    }
}
