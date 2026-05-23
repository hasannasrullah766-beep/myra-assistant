package com.myra.assistant.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.myra.assistant.R

// ─── ChatMessage ──────────────────────────────
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

// ─── WaveformView ─────────────────────────────
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val barCount = 20
    private val barHeights = FloatArray(barCount) { 0.1f }
    private val targetHeights = FloatArray(barCount) { 0.1f }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null
    private var isAnimating = false

    fun startAnimation() {
        isAnimating = true
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 50
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                for (i in 0 until barCount) {
                    barHeights[i] += (targetHeights[i] - barHeights[i]) * 0.3f
                }
                invalidate()
            }
            start()
        }
    }

    fun stopAnimation() {
        isAnimating = false
        for (i in 0 until barCount) targetHeights[i] = 0.1f
    }

    fun setAmplitude(rms: Float) {
        if (!isAnimating) return
        for (i in 0 until barCount) {
            targetHeights[i] = (rms * (0.5f + Math.random().toFloat() * 0.5f))
                .coerceIn(0.05f, 1f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val barWidth = width.toFloat() / (barCount * 2 - 1)
        val maxBarH = height.toFloat()

        for (i in 0 until barCount) {
            val barH = barHeights[i] * maxBarH
            val left = i * barWidth * 2
            val top = (maxBarH - barH) / 2f
            val alpha = (150 + (barHeights[i] * 105).toInt()).coerceIn(0, 255)
            paint.color = Color.argb(alpha, 255, 23, 68)
            canvas.drawRoundRect(
                left, top,
                left + barWidth, top + barH,
                barWidth / 2, barWidth / 2,
                paint
            )
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}

// ─── ChatAdapter ──────────────────────────────
class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()

    companion object {
        const val TYPE_USER = 0
        const val TYPE_MYRA = 1
    }

    fun addMessage(message: ChatMessage) {
        // Deduplication
        if (!message.isUser && messages.isNotEmpty()) {
            val last = messages.last()
            if (!last.isUser && last.text == message.text) return
        }
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun lastMyraText(): String? {
        return messages.lastOrNull { !it.isUser }?.text
    }

    fun clear() {
        messages.clear()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) =
        if (messages[position].isUser) TYPE_USER else TYPE_MYRA

    override fun onCreateViewHolder(
        parent: ViewGroup, viewType: Int
    ): RecyclerView.ViewHolder {
        return if (viewType == TYPE_USER) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_user, parent, false)
            UserViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_myra, parent, false)
            MyraViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        when (holder) {
            is UserViewHolder -> holder.bind(msg)
            is MyraViewHolder -> holder.bind(msg)
        }
    }

    override fun getItemCount() = messages.size

    class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val textView: TextView = view.findViewById(R.id.chatUserText)
        fun bind(msg: ChatMessage) { textView.text = msg.text }
    }

    class MyraViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val textView: TextView = view.findViewById(R.id.chatMyraText)
        fun bind(msg: ChatMessage) { textView.text = msg.text }
    }
}
