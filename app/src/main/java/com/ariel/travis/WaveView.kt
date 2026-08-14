package com.ariel.travis

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

class WaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint().apply {
        color = Color.parseColor("#2CA6A4")
        isAntiAlias = true
    }

    private val barCount = 5
    private val amplitudes = FloatArray(barCount) { 0.3f }
    private var animator: ValueAnimator? = null

    fun startAnimating() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                for (i in amplitudes.indices) {
                    amplitudes[i] = 0.3f + 0.7f * Random.nextFloat()
                }
                invalidate()
            }
            start()
        }
    }

    fun stopAnimating() {
        animator?.cancel()
        for (i in amplitudes.indices) amplitudes[i] = 0.3f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val barWidth = width / (barCount * 2f)
        val centerY = height / 2f

        for (i in 0 until barCount) {
            val barHeight = amplitudes[i] * height * 0.8f
            val left = barWidth * (i * 2 + 0.5f)
            val top = centerY - barHeight / 2
            val right = left + barWidth
            val bottom = centerY + barHeight / 2
            canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, paint)
        }
    }
}