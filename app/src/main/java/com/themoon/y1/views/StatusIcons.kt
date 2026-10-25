package com.themoon.y1.views

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The status bar icons (headphones, Wi-Fi, Bluetooth), drawn in the style of the iPod play/pause icon
 * ([IpodPlayStateView]): a blue gradient from top to bottom with a 1 px dark blue edge.
 * An inactive icon (Wi-Fi on but not connected) is the same shape, empty, with an edge in the battery
 * outline colour. The icons are drawn as paths at the final pixel size, so they stay sharp. The ink of each
 * icon fills its bitmap from the top row to the bottom row, so icons of one height share one box.
 *
 * The Wi-Fi fan and the Bluetooth rune have the proportions of the macOS symbols. The rune is a thin blue
 * line (a gradient and an edge do not fit in a 1.5 px line); with an audio device connected it has a dot
 * on each side at mid-height, like the Android icon.
 *
 * The geometry functions ([wifiGeometry], [runeGeometry], [bluetoothLayout]) have no Android dependencies,
 * so the unit tests can check them.
 */
object StatusIcons {
    enum class Icon { HEADPHONES, WIFI, BLUETOOTH }

    // The palette of the iPod play/pause icon.
    const val FILL_TOP = 0xFFA6D6FF.toInt()
    const val FILL_BOTTOM = 0xFF1F70D6.toInt()
    const val EDGE = 0xFF1A5DB0.toInt()
    // The rune is a line without an edge: its gradient starts darker than FILL_TOP, so it stays legible on a light bar.
    const val RUNE_TOP = 0xFF3C8EEA.toInt()

    // The battery outline colours (BatteryIconView), at 70 %.
    private const val EMPTY_EDGE_LIGHT = 0xB33C3C43.toInt()
    private const val EMPTY_EDGE_DARK = 0xB38E8E93.toInt()

    // Wi-Fi: a dot and two arcs with round ends. Stroke and gap are parts of the glyph height
    // (2 * (dot + gap + stroke) = height). Half the angle of the fan, from the vertical.
    const val WIFI_STROKE = 0.18f
    const val WIFI_GAP = 0.195f
    const val WIFI_HALF_ANGLE = 44f

    // Bluetooth rune: the distance from an arm end to a right point is RUNE_WIDTH * ink height;
    // the line is RUNE_STROKE * height (1.5 px at 15 px).
    const val RUNE_WIDTH = 0.5f
    const val RUNE_STROKE = 0.1f

    /** Rounds to a quarter pixel, so that the shapes are the same at each draw and in the tests. */
    internal fun quarter(x: Float): Float = floor(x * 4f + 0.5f) / 4f

    /**
     * Wi-Fi fan [height] tall, from the top of the outer arc to the bottom of the dot. Radii are from the
     * centre of the dot; the arc radii are to the centre line of each arc.
     */
    class WifiGeometry(val stroke: Float, val gap: Float, val dot: Float, val halfAngleDeg: Float) {
        val arcRadius1 = dot + gap + stroke / 2f
        val arcRadius2 = arcRadius1 + stroke + gap
        val height = dot + arcRadius2 + stroke / 2f
        /** Centre of the dot from the top of the glyph. */
        val centreY = height - dot
        /** Width of the ink: the round ends of the outer arc are the widest points. */
        val width = 2f * (arcRadius2 * sin(Math.toRadians(halfAngleDeg.toDouble())).toFloat() + stroke / 2f)
    }

    fun wifiGeometry(height: Float): WifiGeometry {
        val stroke = quarter(WIFI_STROKE * height)
        val gap = quarter(WIFI_GAP * height)
        return WifiGeometry(stroke, gap, height / 2f - stroke - gap, WIFI_HALF_ANGLE)
    }

    /**
     * Bluetooth rune with a line [stroke] wide and miter joins, [height] tall from the top tip to the bottom tip of
     * the ink. [points] = 6 points (x, y) of the line: x from the centre of the stem, y from the top of the ink.
     * The arms go from an arm end through the centre of the stem to a right point (at 1/4 and 3/4 of the line height).
     * [inkLeft] and [inkRight] = space of the ink left and right of the stem centre.
     */
    class RuneGeometry(
        val points: FloatArray,
        val stroke: Float,
        val halfWidth: Float,
        val tipRise: Float,
        val inkLeft: Float,
        val inkRight: Float
    )

    fun runeGeometry(height: Float, stroke: Float): RuneGeometry {
        val a = RUNE_WIDTH * height / 2f
        // The miter of the top and bottom tips goes past the line ends by s/2 / tan(phi/2), with phi = the angle
        // between the stem and an arm to a right point (it depends on the line height: iterate).
        var lineHeight = height
        var rise = 0f
        repeat(30) {
            val q = lineHeight / 4f
            val len = sqrt(a * a + q * q)
            rise = stroke / 2f * (len + q) / a // 1 / tan(phi / 2) = (len + q) / a
            lineHeight = height - 2f * rise
        }
        val q = lineHeight / 4f
        val len = sqrt(a * a + q * q)
        val y0 = rise
        val points = floatArrayOf(
            -a, y0 + q,
            a, y0 + 3f * q,
            0f, y0 + 4f * q,
            0f, y0,
            a, y0 + q,
            -a, y0 + 3f * q
        )
        // Right points: the miter tip is horizontal, s/2 / sin(theta/2) with sin(theta/2) = q / len.
        // Arm ends: the corners of the butt end, s/2 * q / len to the left.
        return RuneGeometry(points, stroke, a, rise, a + stroke / 2f * q / len, a + stroke / 2f * len / q)
    }

    /**
     * Places the rune in a bitmap [heightPx] tall: the stem on a pixel centre, and with [connected] a dot left and
     * right at mid-height, [dotGap] from the ink of the rune. The ink touches the left and right edges of the bitmap
     * (within one pixel, for the pixel grid).
     */
    class BluetoothLayout(
        val width: Int,
        val stemX: Float,
        val rune: RuneGeometry,
        val dotRadius: Float,
        val dotGap: Float,
        /** Centres of the dots, or empty when not connected. */
        val dotX: FloatArray,
        val dotY: Float
    )

    fun bluetoothLayout(heightPx: Int, connected: Boolean): BluetoothLayout {
        val h = heightPx.toFloat()
        val rune = runeGeometry(h, max(1f, quarter(RUNE_STROKE * h)))
        val dotRadius = max(1f, floor(h / 10f * 2f + 0.5f) / 2f)
        val dotGap = max(1f, (h / 15f).roundToInt().toFloat())
        var left = rune.inkLeft
        var right = rune.inkRight
        // Relative to the stem; whole pixels, so that the dots and the stem are all on pixel centres.
        val dl = -ceil(rune.inkLeft + dotGap + dotRadius)
        val dr = ceil(rune.inkRight + dotGap + dotRadius)
        if (connected) {
            left = -dl + dotRadius
            right = dr + dotRadius
        }
        val stemX = ceil(left - 0.5f) + 0.5f
        val width = ceil(stemX + right).toInt()
        val dots = if (connected) floatArrayOf(stemX + dl, stemX + dr) else FloatArray(0)
        return BluetoothLayout(width, stemX, rune, dotRadius, dotGap, dots, h / 2f)
    }

    /** Width in pixels of [icon] drawn [heightPx] tall. */
    fun widthFor(icon: Icon, heightPx: Int, connected: Boolean = false): Int = when (icon) {
        // The edge is 1 px: the shape is 1 px smaller than the ink.
        Icon.WIFI -> ceil(wifiGeometry(heightPx - 1f).width + 1f).toInt()
        // The shape is designed in an 18 x 18 box.
        Icon.HEADPHONES -> heightPx
        Icon.BLUETOOTH -> bluetoothLayout(heightPx, connected).width
    }

    /**
     * Draws [icon] into a new bitmap [heightPx] tall. [lightBar] = dark content on a light status bar.
     * [connected] = an audio device is connected (Bluetooth only).
     */
    fun render(icon: Icon, heightPx: Int, active: Boolean, lightBar: Boolean, connected: Boolean = false): Bitmap {
        val h = heightPx.coerceAtLeast(1)
        val w = widthFor(icon, h, connected).coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val inactive = if (lightBar) EMPTY_EDGE_LIGHT else EMPTY_EDGE_DARK

        if (icon == Icon.BLUETOOTH) {
            drawBluetooth(canvas, bluetoothLayout(h, connected), h.toFloat(), if (active) null else inactive)
            return bmp
        }

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), FILL_TOP, FILL_BOTTOM, Shader.TileMode.CLAMP)
        }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = if (active) EDGE else inactive
        }
        if (icon == Icon.HEADPHONES) {
            // Band and cups overlap. Draw a 2 px edge, then the fill over it: the fill covers the inner half of the
            // edge and the edges inside the overlap. (Path.op can loop for ever in the Skia of Android 4.4.)
            val shape = headphones(w.toFloat(), h.toFloat())
            edge.strokeWidth = 2f
            canvas.drawPath(shape, edge)
            if (!active) fill.apply { shader = null; xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
            canvas.drawPath(shape, fill)
        } else {
            val shape = wifi(w.toFloat(), h.toFloat())
            if (active) canvas.drawPath(shape, fill)
            canvas.drawPath(shape, edge)
        }
        return bmp
    }

    /** The dot and the two arcs (round ends) of [wifiGeometry], 0.5 px inside the bitmap for the edge. */
    private fun wifi(w: Float, h: Float): Path {
        val g = wifiGeometry(h - 1f)
        val cx = w / 2f
        val cy = 0.5f + g.centreY
        val line = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = g.stroke
            strokeCap = Paint.Cap.ROUND
        }
        return Path().apply {
            addCircle(cx, cy, g.dot, Path.Direction.CW)
            for (r in floatArrayOf(g.arcRadius1, g.arcRadius2)) {
                val arc = Path().apply {
                    arcTo(RectF(cx - r, cy - r, cx + r, cy + r), -90f - g.halfAngleDeg, 2f * g.halfAngleDeg, true)
                }
                val band = Path()
                line.getFillPath(arc, band)
                addPath(band)
            }
        }
    }

    /** The rune as a line with a blue gradient, and the dots of a connected device. [inactiveColor] = grey. */
    private fun drawBluetooth(canvas: Canvas, layout: BluetoothLayout, h: Float, inactiveColor: Int?) {
        val shader = LinearGradient(0f, 0f, 0f, h, RUNE_TOP, FILL_BOTTOM, Shader.TileMode.CLAMP)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = layout.rune.stroke
            strokeJoin = Paint.Join.MITER
            strokeCap = Paint.Cap.BUTT
            if (inactiveColor != null) color = inactiveColor else this.shader = shader
        }
        val p = layout.rune.points
        val path = Path().apply {
            moveTo(layout.stemX + p[0], p[1])
            var i = 2
            while (i < p.size) {
                lineTo(layout.stemX + p[i], p[i + 1])
                i += 2
            }
        }
        canvas.drawPath(path, line)
        if (layout.dotX.isEmpty()) return
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            if (inactiveColor != null) color = inactiveColor else this.shader = shader
        }
        for (x in layout.dotX) canvas.drawCircle(x, layout.dotY, layout.dotRadius, dot)
    }

    /** A head band and two ear cups (they overlap; render() hides the edges inside). */
    private fun headphones(w: Float, h: Float): Path {
        val u = h / 18f
        val cupW = 4.4f * u
        val cupH = 7.6f * u
        val bandW = 2.4f * u
        val left = 0.5f + 0.2f * u
        val right = w - 0.5f - 0.2f * u
        val cupTop = h - 0.5f - cupH
        val r = (right - left - bandW) / 2f
        val cx = w / 2f
        // The band touches the top of the box and the cups its bottom, like the ink of the other icons.
        val cy = 0.5f + bandW / 2f + r

        val arc = Path().apply {
            moveTo(cx - r, cupTop + 2f * u)
            lineTo(cx - r, cy)
            arcTo(RectF(cx - r, cy - r, cx + r, cy + r), 180f, 180f, false)
            lineTo(cx + r, cupTop + 2f * u)
        }
        val band = Path()
        Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = bandW
            strokeCap = Paint.Cap.BUTT
            strokeJoin = Paint.Join.MITER
        }.getFillPath(arc, band)

        val cups = Path().apply {
            val cr = 1.4f * u
            addRoundRect(RectF(left, cupTop, left + cupW, h - 0.5f), cr, cr, Path.Direction.CW)
            addRoundRect(RectF(right - cupW, cupTop, right, h - 0.5f), cr, cr, Path.Direction.CW)
        }
        // Not Path.op: see render()
        band.addPath(cups)
        return band
    }
}
