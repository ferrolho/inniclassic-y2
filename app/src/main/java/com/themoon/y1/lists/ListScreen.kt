package com.themoon.y1.lists

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.themoon.y1.ThemeManager
import com.themoon.y1.navigation.RowFocus
import com.themoon.y1.views.ListItemViewFactory

/**
 * The one renderer of the list screens ([ListSpec]). Short lists are views in [rows] (a LinearLayout in a
 * ScrollView); long lists go into [list] (a ListView), and only one of the two shows.
 *
 * - [show]: makes the rows and selects the first row (or the remembered row, [Focus.LAST]). Back then selects
 *   the row that opened the deeper list ([com.themoon.y1.navigation.NavigationManager] after onEnter).
 * - [update]: makes the rows again and keeps the selected row (by key, else by place): live lists, theme change.
 * - [wheel]: one step ([RowFocus]: Wheel Loop Scroll, rows that cannot take the focus are skipped,
 *   List Edges fade in the ListView), with the click feedback.
 * - Rows: one click feedback, then the row's action; a hold (long press) does the row's hold action.
 */
class ListScreen(
    private val rows: LinearLayout,
    private val list: ListView? = null,
    private val clickFeedback: () -> Unit,
    /** Told the text of each focused [RowLook.MENU] row (the letter box of the Music lists). */
    private val onRowFocus: ((String) -> Unit)? = null
) {
    private val context = rows.context
    private val scroll: View? = rows.parent as? View

    /** The list that shows now, or null before the first [show]. */
    var spec: ListSpec? = null
        private set

    private var shown: List<RowSpec> = emptyList()
    private val lastKeys = HashMap<String, String>()

    val title: String? get() = spec?.title

    fun show(spec: ListSpec) {
        build(spec)
        if (spec.isLong) {
            val lv = list ?: return
            lv.setSelection(0)
            lv.post { if (lv.childCount > 0) lv.getChildAt(0).requestFocus() }
            return
        }
        (scroll as? ScrollView)?.scrollTo(0, 0)
        if (spec.focus == Focus.NONE) {
            // The list has the focus, not a row: the first wheel step selects the first row (RowFocus.step)
            rows.isFocusable = true
            focusNowAndAfter(rows)
            return
        }
        val last = lastKeys[spec.id]
        val i = ListModel.firstRow(shown.map { it.key }, spec.focus, last, ::selectable)
        if (i < 0) return
        val row = rows.getChildAt(i)
        focusNowAndAfter(row)
        if (spec.focus == Focus.LAST && shown[i].key == last) centreAfterLayout(row)
    }

    /**
     * Focus [view] now (the slide draws the new screen with its row), and again after the posted steps of the screen
     * before (a first-row post of the old screen can take the focus back) if the list then has no focus.
     */
    private fun focusNowAndAfter(view: View) {
        view.requestFocus()
        rows.post { if ((view === rows || view.parent === rows) && !rows.hasFocus()) view.requestFocus() }
    }

    /** Selects the row with [key] (for example the row that a screen opens at). False if there is no such row. */
    fun select(key: String): Boolean {
        val i = shown.indexOfFirst { it.key == key }
        if (i < 0 || !selectable(i)) return false
        return rows.getChildAt(i).requestFocus()
    }

    /** Makes the rows of [spec] again and keeps the selected row (by its key, else at the same place). */
    fun update(spec: ListSpec) {
        val focused = focusedIndex()
        val listFocused = rows.isFocused
        val oldKey = shown.getOrNull(focused)?.key
        val scrollY = (scroll as? ScrollView)?.scrollY ?: 0
        build(spec)
        if (spec.isLong) return
        val i = ListModel.keptRow(shown.map { it.key }, oldKey, focused, ::selectable)
        if (i >= 0) {
            rows.getChildAt(i).requestFocus()
        } else if (listFocused) {
            // Still no row selected (Focus.NONE): the list keeps the focus
            rows.isFocusable = true
            rows.requestFocus()
        }
        (scroll as? ScrollView)?.scrollTo(0, scrollY)
    }

    /** One wheel step in the list that shows. True when this screen has a list (the wheel is used, also at an end). */
    fun wheel(delta: Int): Boolean {
        val s = spec ?: return false
        val moved = if (s.isLong) list?.let { RowFocus.step(it, delta) } else RowFocus.step(rows, delta)
        if (moved != null) clickFeedback()
        return true
    }

    /** Remove the rows (the screen exits). The custom views go back to no parent, for the next [show]. */
    fun clear() {
        rows.removeAllViews()
        rows.isFocusable = false
        shown = emptyList()
    }

    private fun build(spec: ListSpec) {
        this.spec = spec
        rows.removeAllViews()
        rows.isFocusable = false
        val long = spec.isLong
        scroll?.visibility = if (long) View.GONE else View.VISIBLE
        list?.visibility = if (long) View.VISIBLE else View.GONE
        if (long) {
            list?.adapter = spec.adapter
            shown = emptyList()
            return
        }
        shown = ListModel.shownRows(spec)
        for (row in shown) rows.addView(makeRow(spec, row))
    }

    private fun makeRow(spec: ListSpec, row: RowSpec): View {
        val view = when (row.look) {
            RowLook.MENU -> {
                val color = if (row.key == ListModel.EMPTY_KEY) ThemeManager.getTextColorSecondary() else row.color
                ListItemViewFactory.createListButtonWithIcon(context, "", row.label, color, onRowFocus)
            }
            RowLook.VALUE -> ListItemViewFactory.createSettingRow(context, row.label, row.value)
            RowLook.CHOICE -> ListItemViewFactory.createChoiceRowWithRightIcons(context, row.label, row.checked, row.glyphs)
            RowLook.CUSTOM -> row.view!!.also { (it.parent as? ViewGroup)?.removeView(it) }
            RowLook.HEADER -> headerRow(row.label)
            RowLook.INFO -> infoRow(row.label)
        }
        if (row.id != View.NO_ID) view.id = row.id
        val click = row.onClick
        if (click != null) {
            view.setOnClickListener {
                clickFeedback()
                lastKeys[spec.id] = row.key
                click()
            }
        }
        val hold = row.onHold
        if (hold != null) {
            view.isLongClickable = true
            view.setOnLongClickListener {
                clickFeedback()
                hold()
                true
            }
        }
        return view
    }

    /** A row that the wheel can select: it shows and can take the focus (not a header, an info row or a hidden row). */
    private fun selectable(i: Int): Boolean = RowFocus.isSelectable(rows.getChildAt(i))

    private fun headerRow(text: String): TextView = TextView(context).apply {
        val d = resources.displayMetrics.density
        this.text = "━ $text ━"
        setTextColor(ThemeManager.getTextColorSecondary())
        textSize = 12f
        gravity = Gravity.CENTER
        setPadding(0, (8 * d).toInt(), 0, (4 * d).toInt())
        isFocusable = false
    }

    private fun infoRow(text: String): TextView = TextView(context).apply {
        val d = resources.displayMetrics.density
        val classic = ThemeManager.isClassicTheme()
        val padX = ((if (classic) 8 else 12) * d).toInt()
        this.text = text
        setTextColor(ThemeManager.getTextColorSecondary())
        setTextSize(TypedValue.COMPLEX_UNIT_PX, (if (classic) 19f else 14f) * d)
        typeface = ThemeManager.getCustomFont()
        setPadding(padX, (6 * d).toInt(), padX, (6 * d).toInt())
        isFocusable = false
        isClickable = false
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun focusedIndex(): Int = (0 until rows.childCount).firstOrNull { rows.getChildAt(it).hasFocus() } ?: -1

    /** A remembered row in the middle of the list, after the rows have their places. */
    private fun centreAfterLayout(row: View) {
        val sv = scroll as? ScrollView ?: return
        sv.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                @Suppress("DEPRECATION")
                sv.viewTreeObserver.removeGlobalOnLayoutListener(this)
                if (row.parent !== rows) return
                sv.scrollTo(0, (row.top - (sv.height / 2 - row.height / 2)).coerceAtLeast(0))
            }
        })
    }

    /** The value text of the row with [key] (for a value that changes in place, for example "Wait..."). */
    fun valueView(key: String): TextView? {
        val i = shown.indexOfFirst { it.key == key }
        if (i < 0) return null
        return (rows.getChildAt(i) as? ViewGroup)?.getChildAt(1) as? TextView
    }
}
