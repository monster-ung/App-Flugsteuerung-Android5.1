package de.ungethuem.flugsteuerung

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.min

/**
 * Künstlicher Horizont (Attitude Indicator) im Infineon-Stil.
 * Zeigt Roll- und Pitch-Ausschlag visuell an.
 */
class ArtificialHorizonView(context: Context) : View(context) {

    private var rollDeg = 0f   // -90..+90
    private var pitchDeg = 0f  // -90..+90

    private val skyColor = 0xFF003865.toInt()
    private val skyHighColor = 0xFF004D8A.toInt()  // Intensiv-Blau bei starkem Steigen
    private val groundColor = 0xFF0F141C.toInt()
    private val groundDeepColor = 0xFF1A0A00.toInt()  // Warm-Dunkel bei starkem Sinken
    private val horizonLineColor = 0xFF00A3A3.toInt()
    private val crosshairColor = 0xFFFFFFFF.toInt()
    private val scaleColor = 0xFFA0AAB5.toInt()
    private val accentColor = 0xFF00A3A3.toInt()
    private val climbTint = 0xFF00D4FF.toInt()   // Cyan-Tint bei Steigen
    private val diveTint = 0xFFFF6B35.toInt()    // Orange-Tint bei Sinken

    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = horizonLineColor
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = crosshairColor
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }
    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = scaleColor
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }
    private val scaleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = scaleColor
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
    }

    private val clipPath = Path()

    fun setAttitude(roll: Float, pitch: Float) {
        this.rollDeg = roll * 30f
        this.pitchDeg = pitch * 30f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f * 0.88f

        // Clip to circle
        clipPath.reset()
        clipPath.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        // Rotate canvas for roll
        canvas.save()
        canvas.rotate(-rollDeg, cx, cy)

        // Pitch offset (pixels per degree)
        val pitchPxPerDeg = radius / 45f
        val pitchOffset = pitchDeg * pitchPxPerDeg

        // Pitch intensity: 0 = neutral, 1 = max climb, -1 = max dive
        val pitchIntensity = (pitchDeg / 90f).coerceIn(-1f, 1f)

        // Sky gradient – gets brighter/more cyan when climbing
        val skyTop = if (pitchIntensity > 0) lerpColor(0xFF004080.toInt(), climbTint, pitchIntensity) else 0xFF004080.toInt()
        val skyBottom = if (pitchIntensity > 0) lerpColor(skyColor, skyHighColor, pitchIntensity) else skyColor
        skyPaint.shader = LinearGradient(
            0f, cy - radius + pitchOffset, 0f, cy + pitchOffset,
            skyTop, skyBottom, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), cy + pitchOffset, skyPaint)

        // Ground gradient – gets warmer/orange when diving
        val gndTop = if (pitchIntensity < 0) lerpColor(groundColor, groundDeepColor, -pitchIntensity) else groundColor
        val gndBottom = if (pitchIntensity < 0) lerpColor(groundColor, diveTint, -pitchIntensity * 0.3f) else groundColor
        groundPaint.shader = LinearGradient(
            0f, cy + pitchOffset, 0f, cy + radius + pitchOffset,
            gndTop, gndBottom, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, cy + pitchOffset, width.toFloat(), height.toFloat(), groundPaint)

        // Horizon line
        canvas.drawLine(0f, cy + pitchOffset, width.toFloat(), cy + pitchOffset, horizonPaint)

        // Pitch ladder
        for (deg in listOf(-20, -10, 10, 20)) {
            val y = cy + pitchOffset - deg * pitchPxPerDeg
            val halfLen = if (deg % 20 == 0) radius * 0.3f else radius * 0.15f
            canvas.drawLine(cx - halfLen, y, cx + halfLen, y, scalePaint)
        }

        canvas.restore() // undo roll rotation

        // Outer ring
        canvas.drawCircle(cx, cy, radius, ringPaint)

        // Fixed crosshair (aircraft reference)
        val wingLen = radius * 0.25f
        val gap = radius * 0.08f
        // Left wing
        canvas.drawLine(cx - gap - wingLen, cy, cx - gap, cy, crosshairPaint)
        canvas.drawLine(cx - gap, cy, cx - gap, cy + radius * 0.06f, crosshairPaint)
        // Right wing
        canvas.drawLine(cx + gap, cy, cx + gap + wingLen, cy, crosshairPaint)
        canvas.drawLine(cx + gap, cy, cx + gap, cy + radius * 0.06f, crosshairPaint)
        // Center dot
        canvas.drawCircle(cx, cy, 4f, crosshairPaint)

        canvas.restore() // undo clip

        // Roll/Pitch bar indicators below the horizon
        val barWidth = radius * 0.6f
        val barHeight = dp(6f)
        val barY = cy + radius + dp(16f)

        // Roll bar background
        val rollBgRect = RectF(cx - barWidth / 2, barY, cx + barWidth / 2, barY + barHeight)
        barPaint.color = 0x33A0AAB5.toInt()
        canvas.drawRoundRect(rollBgRect, barHeight / 2, barHeight / 2, barPaint)
        // Roll bar fill
        val rollFill = (rollDeg / 90f).coerceIn(-1f, 1f) * (barWidth / 2)
        barPaint.color = accentColor
        if (rollFill >= 0) {
            canvas.drawRoundRect(RectF(cx, barY, cx + rollFill, barY + barHeight), barHeight / 2, barHeight / 2, barPaint)
        } else {
            canvas.drawRoundRect(RectF(cx + rollFill, barY, cx, barY + barHeight), barHeight / 2, barHeight / 2, barPaint)
        }

        // Pitch bar background
        val pitchBarY = barY + barHeight + dp(8f)
        val pitchBgRect = RectF(cx - barWidth / 2, pitchBarY, cx + barWidth / 2, pitchBarY + barHeight)
        barPaint.color = 0x33A0AAB5.toInt()
        canvas.drawRoundRect(pitchBgRect, barHeight / 2, barHeight / 2, barPaint)
        // Pitch bar fill
        val pitchFill = (pitchDeg / 90f).coerceIn(-1f, 1f) * (barWidth / 2)
        barPaint.color = accentColor
        if (pitchFill >= 0) {
            canvas.drawRoundRect(RectF(cx, pitchBarY, cx + pitchFill, pitchBarY + barHeight), barHeight / 2, barHeight / 2, barPaint)
        } else {
            canvas.drawRoundRect(RectF(cx + pitchFill, pitchBarY, cx, pitchBarY + barHeight), barHeight / 2, barHeight / 2, barPaint)
        }

        // Labels
        scaleTextPaint.textSize = dp(10f)
        scaleTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("ROLL", cx - barWidth / 2, barY - dp(3f), scaleTextPaint)
        canvas.drawText("PITCH", cx - barWidth / 2, pitchBarY - dp(3f), scaleTextPaint)
        scaleTextPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("${rollDeg.toInt()}°", cx + barWidth / 2, barY - dp(3f), scaleTextPaint)
        canvas.drawText("${pitchDeg.toInt()}°", cx + barWidth / 2, pitchBarY - dp(3f), scaleTextPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun lerpColor(from: Int, to: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        val a = ((from shr 24 and 0xFF) + f * ((to shr 24 and 0xFF) - (from shr 24 and 0xFF))).toInt()
        val r = ((from shr 16 and 0xFF) + f * ((to shr 16 and 0xFF) - (from shr 16 and 0xFF))).toInt()
        val g = ((from shr 8 and 0xFF) + f * ((to shr 8 and 0xFF) - (from shr 8 and 0xFF))).toInt()
        val b = ((from and 0xFF) + f * ((to and 0xFF) - (from and 0xFF))).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
