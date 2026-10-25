package com.themoon.y1.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.TextView
import com.themoon.y1.ThemeManager
import com.themoon.y1.core.extensions.onOffState
import com.themoon.y1.core.extensions.withoutOnOffMark

/**
 * The value on the right of a setting row ([ListItemViewFactory.createSettingRow]).
 * With Toggle Style = Switch ([ToggleStyle]), an on/off value (text from `onOff()`) shows as a small switch.
 * [getText] always gives the plain text ("On"/"Off"), for tests and accessibility.
 *
 * The switch uses only API 19 drawing calls (drawRoundRect, drawCircle), at whole pixels.
 */
class SettingValueView(context: Context) : TextView(context) {
    // setText() is also called from the TextView constructor, before these initialisers: they then set the defaults.
    private var ready = false
    private var state: Boolean? = null
    /** The look of the last onMeasure(): a switch is narrower than the text, so a new look needs a new layout. */
    private var measuredLook: ToggleStyle.Look? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()

    init {
        // The row has the focus: take its state, so the switch draws again when the row is selected.
        isDuplicateParentStateEnabled = true
        ToggleStyle.register(this)
        ready = true
    }

    override fun setText(text: CharSequence?, type: TextView.BufferType?) {
        if (!ready) {
            super.setText(withoutOnOffMark(text), type)
            return
        }
        val before = look()
        state = ToggleStyle.nextState(state, text, this.text)
        super.setText(withoutOnOffMark(text), type)
        // TextView itself does not lay out again when it has no text layout yet (switch shown until now).
        if (look() != before || look() != measuredLook) requestLayout()
        invalidate()
    }

    private fun look(): ToggleStyle.Look = ToggleStyle.look(ToggleStyle.isSwitch(context), state)

    private fun px(dp: Float): Int = Math.max(1, Math.round(dp * resources.displayMetrics.density))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measuredLook = look()
        if (measuredLook == ToggleStyle.Look.TEXT) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val w = px(SWITCH_WIDTH_DP) + paddingLeft + paddingRight
        val h = px(SWITCH_HEIGHT_DP) + paddingTop + paddingBottom
        setMeasuredDimension(View.resolveSize(w, widthMeasureSpec), View.resolveSize(h, heightMeasureSpec))
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val look = look()
        // The size is for the other look (text in a switch-wide view breaks into lines): measure again.
        if (look != measuredLook) requestLayout()
        if (look == ToggleStyle.Look.TEXT) {
            super.onDraw(canvas)
            return
        }
        val on = look == ToggleStyle.Look.SWITCH_ON
        val w = px(SWITCH_WIDTH_DP)
        val h = px(SWITCH_HEIGHT_DP)
        val inset = px(KNOB_INSET_DP)
        // Centre in the full view height: some layouts make the value as high as the row, with a top padding.
        val right = width - paddingRight
        val left = right - w
        val top = (height - h) / 2
        track.set(left.toFloat(), top.toFloat(), right.toFloat(), (top + h).toFloat())

        val selected = (parent as? View)?.isFocused == true || isFocused
        val accent = if (ThemeManager.isClassicTheme()) CLASSIC_ACCENT else ThemeManager.getListButtonFocusedBg()
        val colors = ToggleStyle.colors(
            on, selected, accent, ThemeManager.getListButtonFocusedTextColor(), ThemeManager.getTextColorSecondary()
        )

        val radius = h / 2f
        paint.style = Paint.Style.FILL
        paint.color = colors.track
        canvas.drawRoundRect(track, radius, radius, paint)

        val cx = if (on) right - radius else left + radius
        paint.color = colors.knob
        canvas.drawCircle(cx, top + radius, radius - inset, paint)
    }

    companion object {
        const val SWITCH_WIDTH_DP = 30f
        const val SWITCH_HEIGHT_DP = 18f
        const val KNOB_INSET_DP = 2f

        /** The middle colour of the Classic themes' selected-row gradient (ListItemViewFactory). */
        private const val CLASSIC_ACCENT = 0xFF2588DC.toInt()
    }
}
