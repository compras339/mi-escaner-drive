package com.compras339.escanerdrive

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout

/**
 * Contenedor con forma de píldora, relleno oscuro y un borde degradado con los colores
 * de Google que se desplaza continuamente (equivalente al botón ".gload" del diseño HTML).
 * Los hijos (spinner + texto) se dibujan encima.
 */
class GlowBorderLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val strokeWidth = 3f * density
    private val fillColor = Color.parseColor("#16212E")
    private val gradientColors = intArrayOf(
        Color.parseColor("#F5A524"), Color.parseColor("#F26B3A"), Color.parseColor("#EA4335"),
        Color.parseColor("#A855F7"), Color.parseColor("#4285F4"), Color.parseColor("#34A853"),
        Color.parseColor("#F5A524")
    )

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 46 }   // ~18 %
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@GlowBorderLayout.strokeWidth
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@GlowBorderLayout.strokeWidth * 3
        alpha = 60
    }

    private val rect = RectF()
    private val matrix = Matrix()
    private var shader: LinearGradient? = null
    private var phase = 0f
    private var animator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        val pad = (strokeWidth * 1.5f).toInt()
        setPadding(pad, pad, pad, pad)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // El degradado mide 3 veces el ancho para poder desplazarlo sin cortes
        shader = LinearGradient(0f, 0f, w * 3f, 0f, gradientColors, null, Shader.TileMode.REPEAT)
        strokePaint.shader = shader
        glowPaint.shader = shader
        tintPaint.shader = shader
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel(); animator = null
        super.onDetachedFromWindow()
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility == VISIBLE) startAnimation() else { animator?.cancel(); animator = null }
    }

    private fun startAnimation() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val radius = h / 2f
        val inset = strokeWidth / 2f

        // Desplazamiento del degradado
        matrix.setTranslate(-phase * w * 2f, 0f)
        shader?.setLocalMatrix(matrix)

        rect.set(inset, inset, w - inset, h - inset)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)   // relleno oscuro
        canvas.drawRoundRect(rect, radius, radius, tintPaint)   // tinte suave interior
        canvas.drawRoundRect(rect, radius, radius, glowPaint)   // halo exterior
        canvas.drawRoundRect(rect, radius, radius, strokePaint) // borde nítido
    }
}
