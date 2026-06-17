package de.ungethuem.flugsteuerung

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Virtueller Thumbstick mit automatischer Zentrierung.
 * Liefert normalisierte X/Y-Werte im Bereich [-1, 1].
 * X = Roll (links/rechts), Y = Pitch (hoch/runter).
 */
class JoystickView(
    context: Context,
    private val baseColor: Int,
    private val thumbColor: Int,
    private val ringColor: Int
) : View(context) {

    interface Listener {
        fun onJoystickMoved(x: Float, y: Float)
    }

    var listener: Listener? = null

    private var centerX = 0f
    private var centerY = 0f
    private var baseRadius = 0f
    private var thumbRadius = 0f
    private var thumbX = 0f
    private var thumbY = 0f
    private var activePointerId = -1

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = ringColor
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = ringColor
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        centerX = w / 2f
        centerY = h / 2f
        baseRadius = min(w, h) / 2f * 0.85f
        thumbRadius = baseRadius * 0.35f
        thumbX = centerX
        thumbY = centerY
    }

    override fun onDraw(canvas: Canvas) {
        // Basis-Kreis mit Gradient
        basePaint.shader = RadialGradient(
            centerX, centerY, baseRadius,
            baseColor, (baseColor and 0x00FFFFFF) or 0x80000000.toInt(),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(centerX, centerY, baseRadius, basePaint)

        // Äußerer Ring
        canvas.drawCircle(centerX, centerY, baseRadius, ringPaint)

        // Fadenkreuz
        canvas.drawLine(centerX - baseRadius * 0.6f, centerY, centerX + baseRadius * 0.6f, centerY, crosshairPaint)
        canvas.drawLine(centerX, centerY - baseRadius * 0.6f, centerX, centerY + baseRadius * 0.6f, crosshairPaint)

        // Thumb mit Gradient
        thumbPaint.shader = RadialGradient(
            thumbX, thumbY, thumbRadius,
            thumbColor, (thumbColor and 0x00FFFFFF) or 0xAA000000.toInt(),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                updateThumb(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex >= 0) {
                    updateThumb(event.getX(pointerIndex), event.getY(pointerIndex))
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = -1
                resetThumb()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                if (event.getPointerId(pointerIndex) == activePointerId) {
                    activePointerId = -1
                    resetThumb()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Dämpfungsfaktor: 0.0 = starr, 1.0 = sofortige Reaktion.
     * 0.5 = Thumb bewegt sich pro Frame nur 50% der Distanz zum Finger.
     */
    private val damping = 0.2f

    // Zielposition (wo der Finger ist, begrenzt auf Radius)
    private var targetX = 0f
    private var targetY = 0f

    private fun updateThumb(touchX: Float, touchY: Float) {
        val dx = touchX - centerX
        val dy = touchY - centerY
        val distance = sqrt(dx * dx + dy * dy)
        val maxDistance = baseRadius - thumbRadius

        // Zielposition berechnen (begrenzt auf Radius)
        if (distance <= maxDistance) {
            targetX = touchX
            targetY = touchY
        } else {
            targetX = centerX + dx / distance * maxDistance
            targetY = centerY + dy / distance * maxDistance
        }

        // Thumb per Lerp gedämpft zur Zielposition bewegen
        thumbX += (targetX - thumbX) * damping
        thumbY += (targetY - thumbY) * damping

        val normalizedX = (thumbX - centerX) / maxDistance
        val normalizedY = -(thumbY - centerY) / maxDistance // Y invertiert: oben = positiv
        listener?.onJoystickMoved(normalizedX, normalizedY)
        invalidate()
    }

    private fun resetThumb() {
        thumbX = centerX
        thumbY = centerY
        listener?.onJoystickMoved(0f, 0f)
        invalidate()
    }

    /** Aktueller normalisierter X-Wert [-1, 1] */
    fun getNormalizedX(): Float {
        val maxDistance = baseRadius - thumbRadius
        return if (maxDistance > 0) (thumbX - centerX) / maxDistance else 0f
    }

    /** Aktueller normalisierter Y-Wert [-1, 1] (oben = positiv) */
    fun getNormalizedY(): Float {
        val maxDistance = baseRadius - thumbRadius
        return if (maxDistance > 0) -(thumbY - centerY) / maxDistance else 0f
    }
}
