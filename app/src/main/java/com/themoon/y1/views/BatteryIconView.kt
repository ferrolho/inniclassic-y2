package com.themoon.y1.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.themoon.y1.ThemeManager

class BatteryIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var level = 100
    private var isCharging = false
    private var color = 0xFF1C1C1E.toInt()
    private var showPercentage = false
    private var thinOutline = false

    private val paintFill: Paint
    private val paintStroke: Paint
    private val paintTerminal: Paint
    private val paintText: Paint
    private val paintBolt: Paint
    private val rectShell = RectF()
    private val rectFill = RectF()
    private val rectTerminal = RectF()
    private val boltPath = Path()
    private val digitPath = Path()
    private val glyphPath = Path()
    private val glyphBounds = RectF()
    private val glyphMatrix = Matrix()
    private var inkRatiosTypeface: Typeface? = null
    private var inkRatiosValid = false
    private val inkRatios = FloatArray(10)

    init {
        paintFill = Paint(Paint.ANTI_ALIAS_FLAG)

        paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        paintTerminal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.LEFT
            try {
                val tf = ThemeManager.getCustomFontBold()
                typeface = tf ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            } catch (_: Exception) {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
        }

        paintBolt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF2E7D32.toInt()
        }
    }

    fun setShowPercentage(showPercentage: Boolean) {
        if (this.showPercentage != showPercentage) {
            this.showPercentage = showPercentage
            invalidate()
        }
    }

    fun isShowPercentage(): Boolean = showPercentage

    fun setBatteryLevel(level: Int, isCharging: Boolean) {
        this.level = maxOf(0, minOf(100, level))
        this.isCharging = isCharging
        invalidate()
    }

    /**
     * A 2 px outline (not 3 px), for a small battery (16 px tall in the iPod Classic Layout status bar): the digits
     * keep more room inside the outline.
     */
    fun setThinOutline(thin: Boolean) {
        if (thinOutline != thin) {
            thinOutline = thin
            invalidate()
        }
    }

    fun setColor(color: Int) {
        if (this.color != color) {
            this.color = color
            invalidate()
        }
    }

    /** All parts are on whole pixels (see [BatteryLayout]), so the outline, the nub, the fill and the digits are sharp. */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width
        val h = height
        if (w == 0 || h == 0) return

        val pillRadius = h * 0.12f

        var currentColor = color
        if (isCharging) {
            currentColor = Color.parseColor("#4CAF50")
        } else if (level <= 20) {
            currentColor = Color.parseColor("#F44336")
        }

        val strokeColor = if (ThemeManager.isClassicLightTheme()) 0xFF3C3C43.toInt() else 0xFF8E8E93.toInt()
        paintStroke.color = strokeColor
        paintTerminal.color = strokeColor
        paintFill.color = currentColor

        if (level <= 20 && !isCharging) {
            paintFill.shader = null
        } else {
            val topColor = if (isCharging) "#8FE070" else "#D0E9B7"
            val bottomColor = if (isCharging) "#4CAF50" else "#7CB342"
            paintFill.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                Color.parseColor(topColor), Color.parseColor(bottomColor), Shader.TileMode.CLAMP
            )
        }

        // The stroke is centred on the shell edge, so an inset of half the outline puts both of its sides on whole pixels.
        val outline = if (thinOutline) 2 else 3
        val body = BatteryLayout.body(w, h, outline)
        paintStroke.strokeWidth = outline.toFloat()
        val half = outline / 2f
        rectShell.set(half, half, body.shellRight - half, h - half)
        canvas.drawRoundRect(rectShell, pillRadius, pillRadius, paintStroke)

        // The nub starts under the outline (same colour), so only its right corners are round.
        rectTerminal.set(
            (body.nubLeft - outline).toFloat(), body.nubTop.toFloat(),
            body.nubRight.toFloat(), body.nubBottom.toFloat()
        )
        canvas.drawRoundRect(rectTerminal, 1f, 1f, paintTerminal)

        val fillLeft = body.fillLeft.toFloat()
        val fillWidth = BatteryLayout.fillWidth(level, body.fillWidth)
        if (fillWidth > 0) {
            rectFill.set(fillLeft, body.fillTop.toFloat(), fillLeft + fillWidth, body.fillBottom.toFloat())
            val r = maxOf(0f, pillRadius - 1f)
            canvas.drawRoundRect(rectFill, r, r, paintFill)
        }

        if (showPercentage) {
            try {
                val tf = ThemeManager.getCustomFontBold()
                if (tf != null) paintText.typeface = tf
            } catch (_: Exception) {
            }

            val text = level.toString()
            val group = BatteryLayout.group(text, inkRatios(), body.fillWidth, body.fillHeight, isCharging)
            var x = BatteryLayout.groupLeft(body.fillLeft, body.fillWidth, group.width)
            val top = BatteryLayout.digitTop(body.fillTop, body.fillHeight, group.digitHeight)

            boltPath.reset()
            if (isCharging) {
                buildBoltPath(x, top - 1, group.boltWidth, BatteryLayout.boltHeight(group.digitHeight))
                x += group.boltWidth + BatteryLayout.GAP
            }
            buildDigitPath(text, x, top, group)

            paintText.color = color
            paintBolt.color = color
            canvas.drawPath(boltPath, paintBolt)
            canvas.drawPath(digitPath, paintText)

            if (fillWidth > 0) {
                val filledColor = if (isCharging || level > 20) 0xFF1C1C1E.toInt() else Color.WHITE
                paintText.color = filledColor
                paintBolt.color = filledColor
                canvas.save()
                canvas.clipRect(fillLeft, 0f, fillLeft + fillWidth, h.toFloat())
                canvas.drawPath(boltPath, paintBolt)
                canvas.drawPath(digitPath, paintText)
                canvas.restore()
            }
        } else if (isCharging) {
            val boltH = h - 2 * outline
            val boltW = BatteryLayout.soloBoltWidth(boltH, body.fillWidth)
            buildBoltPath(BatteryLayout.groupLeft(body.fillLeft, body.fillWidth, boltW), outline, boltW, boltH)
            paintBolt.color = 0xFF2E7D32.toInt()
            canvas.drawPath(boltPath, paintBolt)
        }
    }

    /**
     * Builds [digitPath] from the outlines of [text]: each digit is mapped onto its box of whole pixels
     * (left [left], top [top], [BatteryLayout.Group.digitWidths] x [BatteryLayout.Group.digitHeight]), with
     * [BatteryLayout.GAP] between the digits. drawText() is not used: the hinting of Android 4.4 gave the digits
     * different heights, and the text did not start on a whole pixel.
     */
    private fun buildDigitPath(text: String, left: Int, top: Int, group: BatteryLayout.Group) {
        digitPath.reset()
        paintText.textSize = REFERENCE_TEXT_SIZE
        var x = left
        for (i in text.indices) {
            val boxW = group.digitWidths[i]
            paintText.getTextPath(text, i, i + 1, 0f, 0f, glyphPath)
            glyphPath.computeBounds(glyphBounds, true)
            if (glyphBounds.width() > 0f && glyphBounds.height() > 0f) {
                glyphMatrix.setTranslate(-glyphBounds.left, -glyphBounds.top)
                glyphMatrix.postScale(boxW / glyphBounds.width(), group.digitHeight / glyphBounds.height())
                glyphMatrix.postTranslate(x.toFloat(), top.toFloat())
                glyphPath.transform(glyphMatrix)
                digitPath.addPath(glyphPath)
            }
            x += boxW + BatteryLayout.GAP
        }
    }

    /** Ink width / flat digit height of the digits 0-9 in the current typeface (for [BatteryLayout.digitWidths]). */
    private fun inkRatios(): FloatArray {
        if (inkRatiosTypeface !== paintText.typeface || !inkRatiosValid) {
            paintText.textSize = REFERENCE_TEXT_SIZE
            var flat = Float.MAX_VALUE
            for (d in 0..9) {
                paintText.getTextPath(d.toString(), 0, 1, 0f, 0f, glyphPath)
                glyphPath.computeBounds(glyphBounds, true)
                inkRatios[d] = glyphBounds.width()
                if (-glyphBounds.top > 0f) flat = minOf(flat, -glyphBounds.top)
            }
            for (d in 0..9) inkRatios[d] = if (flat == Float.MAX_VALUE) 0.6f else inkRatios[d] / flat
            inkRatiosTypeface = paintText.typeface
            inkRatiosValid = true
        }
        return inkRatios
    }

    private fun buildBoltPath(left: Int, top: Int, width: Int, height: Int) {
        val p = BatteryLayout.boltPoints(width, height)
        boltPath.reset()
        boltPath.moveTo(left + p[0], top + p[1])
        var i = 2
        while (i < p.size) {
            boltPath.lineTo(left + p[i], top + p[i + 1])
            i += 2
        }
        boltPath.close()
    }

    private companion object {
        /** Text size of the outlines before they are fitted to the pixel boxes (large, so hinting has no effect). */
        const val REFERENCE_TEXT_SIZE = 100f
    }
}
