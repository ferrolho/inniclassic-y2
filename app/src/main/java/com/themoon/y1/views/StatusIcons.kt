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
import kotlin.math.roundToInt

/**
 * The status bar icons (headphones, Wi-Fi, Bluetooth), drawn in the style of the iPod play/pause icon
 * ([IpodPlayStateView]): a blue gradient from top to bottom with a 1 px dark blue edge.
 * An inactive icon (Wi-Fi on but not connected) is the same shape, empty, with an edge in the battery
 * outline colour. The icons are drawn as paths at the final pixel size, so they stay sharp.
 */
object StatusIcons {
    enum class Icon { HEADPHONES, WIFI, BLUETOOTH }

    // The palette of the iPod play/pause icon.
    const val FILL_TOP = 0xFFA6D6FF.toInt()
    const val FILL_BOTTOM = 0xFF1F70D6.toInt()
    const val EDGE = 0xFF1A5DB0.toInt()
    private const val RUNE = 0xFFFFFFFF.toInt()

    // The battery outline colours (BatteryIconView), at 70 %.
    private const val EMPTY_EDGE_LIGHT = 0xB33C3C43.toInt()
    private const val EMPTY_EDGE_DARK = 0xB38E8E93.toInt()

    /** Width in pixels of [icon] drawn [heightPx] tall. The shapes are designed in an 18 px box. */
    fun widthFor(icon: Icon, heightPx: Int): Int {
        val u = heightPx / 18f
        return when (icon) {
            Icon.WIFI -> ceil(22f * u).toInt()
            Icon.HEADPHONES -> ceil(18f * u).toInt()
            // Even, so that the centre line of the rune is on a pixel edge.
            Icon.BLUETOOTH -> 2 * ceil(6f * u).toInt()
        }
    }

    /** Draws [icon] into a new bitmap [heightPx] tall. [lightBar] = dark content on a light status bar. */
    fun render(icon: Icon, heightPx: Int, active: Boolean, lightBar: Boolean): Bitmap {
        val h = heightPx.coerceAtLeast(1)
        val w = widthFor(icon, h).coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val shape = shape(icon, w.toFloat(), h.toFloat())

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), FILL_TOP, FILL_BOTTOM, Shader.TileMode.CLAMP)
        }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = if (active) EDGE else if (lightBar) EMPTY_EDGE_LIGHT else EMPTY_EDGE_DARK
        }
        if (icon == Icon.HEADPHONES) {
            // Band and cups overlap. Draw a 2 px edge, then the fill over it: the fill covers the inner half of the
            // edge and the edges inside the overlap. (Path.op can loop for ever in the Skia of Android 4.4.)
            edge.strokeWidth = 2f
            canvas.drawPath(shape, edge)
            if (!active) fill.apply { shader = null; xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
            canvas.drawPath(shape, fill)
        } else {
            if (active) canvas.drawPath(shape, fill)
            canvas.drawPath(shape, edge)
        }

        if (icon == Icon.BLUETOOTH) {
            val rune = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = (2f * h / 18f).roundToInt().coerceAtLeast(1).toFloat()
                strokeJoin = Paint.Join.MITER
                color = if (active) RUNE else edge.color
            }
            canvas.drawPath(bluetoothRune(w.toFloat(), h.toFloat()), rune)
        }
        return bmp
    }

    private fun shape(icon: Icon, w: Float, h: Float): Path = when (icon) {
        Icon.WIFI -> wifi(w, h)
        Icon.HEADPHONES -> headphones(w, h)
        Icon.BLUETOOTH -> Path().apply {
            // A badge like the Bluetooth logo, with the rune on it.
            val r = (w - 1f) / 2f
            addRoundRect(RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f), r, (w - 1f) / 2.6f, Path.Direction.CW)
        }
    }

    /** A dot and two bands of a 90° fan, from the bottom centre. */
    private fun wifi(w: Float, h: Float): Path {
        val u = h / 18f
        val cx = w / 2f
        val dot = 2.6f * u
        val cy = h - 0.5f - dot
        val outer = cy - 0.5f
        val band = (outer - dot) * 0.31f
        val gap = (outer - dot - 2f * band) / 2f
        return Path().apply {
            addCircle(cx, cy, dot, Path.Direction.CW)
            addSector(this, cx, cy, dot + gap, dot + gap + band)
            addSector(this, cx, cy, outer - band, outer)
        }
    }

    private fun addSector(path: Path, cx: Float, cy: Float, rIn: Float, rOut: Float) {
        path.arcTo(RectF(cx - rOut, cy - rOut, cx + rOut, cy + rOut), -135f, 90f, true)
        path.arcTo(RectF(cx - rIn, cy - rIn, cx + rIn, cy + rIn), -45f, -90f, false)
        path.close()
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
        val cy = maxOf(0.5f + bandW / 2f + r, cupTop + 1.5f * u)

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

    private fun bluetoothRune(w: Float, h: Float): Path {
        val u = h / 18f
        val cx = (w / 2f).roundToInt().toFloat()
        val x0 = cx - 2.6f * u
        val x1 = cx + 2.6f * u
        val top = 3.6f * u
        val bottom = h - 3.6f * u
        val q = (bottom - top) / 4f
        return Path().apply {
            moveTo(x0, top + q)
            lineTo(x1, bottom - q)
            lineTo(cx, bottom)
            lineTo(cx, top)
            lineTo(x1, top + q)
            lineTo(x0, bottom - q)
        }
    }
}
