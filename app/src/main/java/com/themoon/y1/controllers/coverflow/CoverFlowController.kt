package com.themoon.y1.controllers.coverflow

import android.app.Activity
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.managers.AlbumCoverManager
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.views.CoverFlowLayout

/**
 * Controller for the 3D CoverFlow carousel view.
 * Uses a 9-slot sliding buffer (-4 to +4) to prevent pop-in at the edges,
 * renders reflected album cards, and binds album art asynchronously via AlbumCoverManager.
 */
class CoverFlowController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val listVirtualSongs: View?,
    private val uniqueAlbumList: List<SongItem>,
    private val clickFeedback: () -> Unit,
    private val onAlbumSelected: (albumItem: SongItem) -> Unit,
    private val onNavigateBack: () -> Unit,
    private val onRebuildUniqueAlbumList: (() -> Unit)? = null,
    private val localize: (String) -> String
) : ScreenController {

    private val containerBrowserItems: LinearLayout = rootView.findViewById(R.id.container_browser_items)
    private val scrollViewBrowser: View? = containerBrowserItems.parent as? View
    private val tvBrowserPath: TextView = rootView.findViewById(R.id.tv_browser_path)

    private val visibleCoversCount = 9
    var currentCoverFlowIndex = 0

    private var coverFlowContainer: CoverFlowLayout? = null
    private var cfViews: Array<View?>? = null

    private var coverFlowActiveDirection = 0
    private var coverFlowDirectionChangeTime = 0L
    private var lastCoverFlowWheelTime = 0L

    // Speed scrolling: a fast wheel turn moves through the artist letters (see CoverFlowLetterJump).
    private val letterJump = CoverFlowLetterJump(uniqueAlbumList, object : CoverFlowLetterJump.Ui {
        override fun showLetter(letter: String) { MainActivity.instance?.showJumpLetter(letter) }
        override fun hideLetter() { MainActivity.instance?.hideFastScrollLetter() }
        override fun goTo(index: Int) = settleOnAlbum(index)
        override fun click() = clickFeedback()
        override fun postDelayed(task: Runnable, delayMs: Long) { rootView.postDelayed(task, delayMs) }
        override fun cancel(task: Runnable) { rootView.removeCallbacks(task) }
    })

    // Fly-by at the end of a letter jump (see CoverFlowFlyBy). Positions are album numbers that can be
    // fractional, and can be outside 0 until total after wheel steps (the cards show them modulo total).
    private var flying = false
    private var flyStartPos = 0f
    private var flyTarget = 0
    private var flyStartTime = 0L
    private var flyDurationMs = 0L
    /** The position on the screen now. */
    private var flyPos = 0f
    /** The album of the centre card (views[centre]) during a fly-by; the other cards have the next albums. */
    private var cardsBase = 0
    private val flyFrame = Runnable { onFlyFrame() }
    // Card position, angle, scale and alpha at each slot (-4..4), made at the start of a fly-by: no work for them in a frame.
    private val slotX = FloatArray(visibleCoversCount)
    private val slotRotY = FloatArray(visibleCoversCount)
    private val slotScale = FloatArray(visibleCoversCount)
    private val slotAlpha = FloatArray(visibleCoversCount)

    override fun onEnter(state: ScreenState) {
        buildCoverFlowUI()
    }

    override fun onRefreshUI() {
        buildCoverFlowUI()
    }

    override fun onExit() {
        letterJump.reset()
        stopFlyBy()
        cancelAllAnimations()
        coverFlowContainer?.removeAllViews()
        containerBrowserItems.removeAllViews()
    }

    fun buildCoverFlowUI() {
        letterJump.reset()
        stopFlyBy()
        val statusBar = activity.findViewById<View>(R.id.layout_status_bar)
        statusBar?.setBackgroundColor(0x00000000)

        // Ensure split-screen right cover pane from browser mode is hidden in Cover Flow
        rootView.findViewById<View>(R.id.frame_browser_cover)?.visibility = View.GONE

        scrollViewBrowser?.visibility = View.VISIBLE
        if (scrollViewBrowser is ScrollView) {
            scrollViewBrowser.scrollTo(0, 0)
        }
        listVirtualSongs?.visibility = View.GONE
        containerBrowserItems.removeAllViews()

        if (uniqueAlbumList.isEmpty()) {
            onRebuildUniqueAlbumList?.invoke()
        }

        AlbumCoverManager.getInstance().pregenerateThumbnails(uniqueAlbumList, false)

        if (uniqueAlbumList.isEmpty()) {
            val tvEmpty = TextView(activity).apply {
                text = localize("No Albums Found")
                textSize = 18f
                setTextColor(ThemeManager.getTextColorSecondary())
                gravity = Gravity.CENTER
                setPadding(0, 50, 0, 0)
            }
            containerBrowserItems.addView(tvEmpty)
            return
        }

        if (currentCoverFlowIndex >= uniqueAlbumList.size) {
            currentCoverFlowIndex = 0
        }

        val cfLayout = CoverFlowLayout(activity).apply {
            clipChildren = false
            clipToPadding = false
        }
        coverFlowContainer = cfLayout
        containerBrowserItems.clipChildren = false
        containerBrowserItems.clipToPadding = false
        if (scrollViewBrowser is ViewGroup) {
            scrollViewBrowser.clipChildren = false
            scrollViewBrowser.clipToPadding = false
        }

        val containerHeight = (380 * activity.resources.displayMetrics.density).toInt()
        containerBrowserItems.addView(
            cfLayout,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, containerHeight)
        )

        val views = arrayOfNulls<View>(visibleCoversCount)
        for (i in 0 until visibleCoversCount) {
            val card = createSingleCoverView()
            views[i] = card
            cfLayout.addView(card)
        }
        cfViews = views

        initCoverFlowPositions()
    }

    private fun initCoverFlowPositions() {
        val total = uniqueAlbumList.size
        if (total == 0) return
        val views = cfViews ?: return

        val centerIdx = visibleCoversCount / 2

        for (i in 0 until visibleCoversCount) {
            val offsetFromCenter = i - centerIdx
            val targetIdx = (currentCoverFlowIndex + offsetFromCenter + total * 10) % total
            views[i]?.let { bindCoverData(it, targetIdx) }
        }

        val d = activity.resources.displayMetrics.density

        for (i in 0 until visibleCoversCount) {
            val dist = Math.abs(i - centerIdx)
            val sign = if (i < centerIdx) -1f else 1f

            val transX = sign * getTransXForDist(dist, d)
            val rotY = -sign * getRotYForDist(dist)
            val scale = getScaleForDist(dist)
            val alpha = getAlphaForDist(dist)

            applyTransform(views[i], transX, rotY, scale, alpha)
        }

        arrangeZIndex()

        for (i in 0 until visibleCoversCount) {
            views[i]?.let { setCardTitleAlpha(it, i == centerIdx, 0) }
        }

        val statusTitle = localize("Cover Flow") + " (" + (currentCoverFlowIndex + 1) + "/" + total + ")"
        tvBrowserPath.text = statusTitle
        activity.findViewById<TextView>(R.id.tv_status_clock)?.text = statusTitle
    }

    private fun getTransXForDist(dist: Int, d: Float): Float {
        return when (dist) {
            0 -> 0f
            1 -> 130 * d
            2 -> 170 * d
            3 -> 215 * d
            else -> 270 * d
        }
    }

    private fun getRotYForDist(dist: Int): Float {
        return if (dist == 0) 0f else 65f
    }

    private fun getScaleForDist(dist: Int): Float {
        return when (dist) {
            0 -> 1.0f
            1, 2, 3 -> 0.8f
            else -> 0.75f
        }
    }

    private fun getAlphaForDist(dist: Int): Float {
        return if (dist <= 3) 1.0f else 0.0f
    }

    private fun bindCoverData(card: View, dataIndex: Int) {
        if (uniqueAlbumList.isEmpty() || dataIndex < 0 || dataIndex >= uniqueAlbumList.size) return
        val item = uniqueAlbumList[dataIndex]

        val ivCover = card.findViewById<ImageView>(COVER_VIEW_ID)
        val ivReflection = card.findViewById<ImageView>(REFLECTION_VIEW_ID)
        val tvTitle = card.findViewById<TextView>(TITLE_VIEW_ID)
        val tvArtist = card.findViewById<TextView>(ARTIST_VIEW_ID)

        tvTitle?.text = item.album
        tvArtist?.text = item.artist

        val albumKey = AlbumCoverManager.getAlbumKey(item.file, item.album)
        ivCover?.tag = albumKey

        val manager = AlbumCoverManager.getInstance()
        val cachedBmp = manager.getMemoryCover(albumKey)
        val cachedRef = manager.getMemoryReflection(albumKey)

        if (cachedBmp != null) {
            ivCover?.animate()?.cancel()
            ivCover?.alpha = 1.0f
            ivCover?.setImageBitmap(cachedBmp)
            if (ivReflection != null) {
                ivReflection.animate().cancel()
                ivReflection.alpha = 1.0f
                ivReflection.setImageBitmap(cachedRef)
                ivReflection.visibility = if (cachedRef != null) View.VISIBLE else View.INVISIBLE
            }
            return
        }

        ivCover?.animate()?.cancel()
        ivCover?.alpha = 1.0f
        ivCover?.setImageBitmap(
            ThemeManager.getCustomIcon("icon_default_album.png", activity, R.drawable.default_album)
        )
        if (ivReflection != null) {
            ivReflection.animate().cancel()
            ivReflection.alpha = 1.0f
            ivReflection.setImageBitmap(null)
            ivReflection.visibility = View.INVISIBLE
        }

        manager.loadCoverAsync(item, object : AlbumCoverManager.CoverCallback {
            override fun onCoverLoaded(loadedKey: String, cover: Bitmap?, reflection: Bitmap?) {
                if (albumKey == ivCover?.tag) {
                    if (cover != null) {
                        ivCover.setImageBitmap(cover)
                    }
                    if (ivReflection != null) {
                        if (reflection != null) {
                            ivReflection.setImageBitmap(reflection)
                            ivReflection.visibility = View.VISIBLE
                        } else {
                            ivReflection.visibility = View.INVISIBLE
                        }
                    }
                }
            }
        })
    }

    private fun createSingleCoverView(): View {
        val d = activity.resources.displayMetrics.density
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val lp = FrameLayout.LayoutParams((350 * d).toInt(), (320 * d).toInt()).apply {
            gravity = Gravity.CENTER
            topMargin = (-20 * d).toInt()
        }
        card.layoutParams = lp
        card.setLayerType(View.LAYER_TYPE_NONE, null)

        val ivCover = ImageView(activity).apply {
            id = COVER_VIEW_ID
            layoutParams = LinearLayout.LayoutParams((200 * d).toInt(), (200 * d).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        val ivReflection = ImageView(activity).apply {
            id = REFLECTION_VIEW_ID
            layoutParams = LinearLayout.LayoutParams((200 * d).toInt(), (50 * d).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (2 * d).toInt()
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        val tvTitle = TextView(activity).apply {
            id = TITLE_VIEW_ID
            setTextColor(ThemeManager.getTextColorPrimary())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * d)
            typeface = ThemeManager.getCustomFontBold()
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (-40 * d).toInt()
                bottomMargin = 0
            }
        }

        val tvArtist = TextView(activity).apply {
            id = ARTIST_VIEW_ID
            setTextColor(ThemeManager.getTextColorSecondary())
            textSize = 16.5f
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (2 * d).toInt()
            }
        }

        card.addView(ivCover)
        card.addView(ivReflection)
        card.addView(tvTitle)
        card.addView(tvArtist)

        card.setOnClickListener { v ->
            val centerIdx = visibleCoversCount / 2
            val views = cfViews
            if (views != null && v == views[centerIdx] && currentCoverFlowIndex in uniqueAlbumList.indices) {
                clickFeedback()
                val chosen = uniqueAlbumList[currentCoverFlowIndex]
                onAlbumSelected(chosen)
            }
        }

        return card
    }

    private fun setCardTitleAlpha(card: View, isCenter: Boolean, duration: Int) {
        val tvTitle = card.findViewById<View>(TITLE_VIEW_ID)
        val tvArtist = card.findViewById<View>(ARTIST_VIEW_ID)
        if (tvTitle != null && tvArtist != null) {
            val targetAlpha = if (isCenter) 1.0f else 0.0f
            if (duration > 0) {
                tvTitle.animate().alpha(targetAlpha).setDuration(duration.toLong()).start()
                tvArtist.animate().alpha(targetAlpha).setDuration(duration.toLong()).start()
            } else {
                tvTitle.alpha = targetAlpha
                tvArtist.alpha = targetAlpha
            }
        }
    }

    private fun applyTransform(v: View?, transX: Float, rotY: Float, scale: Float, alpha: Float) {
        if (v == null) return
        v.animate().cancel()
        v.translationX = transX
        v.rotationY = rotY
        v.scaleX = scale
        v.scaleY = scale
        v.alpha = alpha
    }

    private fun animateTransform(v: View?, transX: Float, rotY: Float, scale: Float, alpha: Float, duration: Int) {
        if (v == null) return
        if (duration <= 0) {
            applyTransform(v, transX, rotY, scale, alpha)
        } else {
            v.animate().translationX(transX).rotationY(rotY).scaleX(scale).scaleY(scale).alpha(alpha)
                .setDuration(duration.toLong()).start()
        }
    }

    private fun arrangeZIndex() {
        coverFlowContainer?.invalidate()
    }

    private fun cancelAllAnimations() {
        cfViews?.forEach { it?.animate()?.cancel() }
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean {
        handleCoverFlowWheelInput(if (delta > 0) 22 else 21, event)
        return true
    }

    private fun handleCoverFlowWheelInput(keyCode: Int, event: KeyEvent) {
        val dir = if (keyCode == 22) 1 else -1
        val eventTime = event.eventTime
        val now = SystemClock.uptimeMillis()

        if (now - lastCoverFlowWheelTime > 250) {
            coverFlowActiveDirection = dir
            coverFlowDirectionChangeTime = eventTime
        } else if (coverFlowActiveDirection != 0 && dir != coverFlowActiveDirection) {
            coverFlowActiveDirection = dir
            coverFlowDirectionChangeTime = eventTime
            cancelAllAnimations()
        } else if (coverFlowActiveDirection == 0) {
            coverFlowActiveDirection = dir
            coverFlowDirectionChangeTime = eventTime
        }

        if (dir != coverFlowActiveDirection || eventTime < coverFlowDirectionChangeTime) {
            return
        }

        val lag = now - eventTime
        val diff = now - lastCoverFlowWheelTime
        lastCoverFlowWheelTime = now
        if (letterJump.onStep(dir, eventTime, currentCoverFlowIndex)) return
        if (flying) {
            // The step takes over from where the covers are now: the fly-by goes one album past its target.
            flyTo(flyTarget + dir)
            if (lag <= 40) clickFeedback()
            return
        }

        val duration: Int = when {
            lag > 40 || diff < 40 -> 0
            diff < 90 -> 45
            else -> 160
        }

        scrollCoverFlow(dir > 0, duration)

        if (lag <= 40) {
            clickFeedback()
        }
    }

    /** End of a letter jump: the covers scroll past fast to album [index]. */
    private fun settleOnAlbum(index: Int) {
        val total = uniqueAlbumList.size
        if (index !in 0 until total) return
        if (flying) {
            // In the numbers of the running fly-by (its target can be outside 0 until total after wheel steps).
            flyTo(index + flyTarget - wrap(flyTarget, total))
        } else if (index != currentCoverFlowIndex) {
            flyTo(index)
        }
    }

    /**
     * Starts a fly-by to album [target] from the position on the screen, or changes the target of the running
     * fly-by (the covers continue from where they are). The album count and select use [target] immediately.
     */
    private fun flyTo(target: Int) {
        val total = uniqueAlbumList.size
        val views = cfViews ?: return
        val container = coverFlowContainer ?: return
        if (total == 0) return
        if (!flying) {
            cancelAllAnimations()
            makeSlots()
            cardsBase = currentCoverFlowIndex
            flyPos = currentCoverFlowIndex.toFloat()
            for (card in views) card?.let { setCardTitleAlpha(it, false, CoverFlowFlyBy.TEXT_FADE_MS) }
            flying = true
        }
        flyStartPos = flyPos
        flyTarget = target
        flyStartTime = SystemClock.uptimeMillis()
        flyDurationMs = CoverFlowFlyBy.durationMs(target - flyPos)
        currentCoverFlowIndex = wrap(target, total)
        container.removeCallbacks(flyFrame)
        container.postOnAnimation(flyFrame)
    }

    private fun onFlyFrame() {
        if (!flying) return
        val elapsed = SystemClock.uptimeMillis() - flyStartTime
        val done = elapsed >= flyDurationMs
        flyPos = if (done) flyTarget.toFloat()
        else flyStartPos + CoverFlowFlyBy.offsetAt(elapsed, flyDurationMs, flyTarget - flyStartPos)
        placeCards(flyPos)
        if (!done) {
            coverFlowContainer?.postOnAnimation(flyFrame)
            return
        }
        stopFlyBy()
        // Exact positions, the album count, then the text of the centre album fades in.
        initCoverFlowPositions()
        cfViews?.get(visibleCoversCount / 2)?.let {
            setCardTitleAlpha(it, false, 0)
            setCardTitleAlpha(it, true, CoverFlowFlyBy.TEXT_FADE_MS)
        }
    }

    private fun stopFlyBy() {
        if (!flying) return
        flying = false
        coverFlowContainer?.removeCallbacks(flyFrame)
    }

    /**
     * Puts the cards at position [pos] (album number, can be fractional). The cards move in the views array as in
     * a wheel step; only the cards that come in get a new album (from the cover cache, else the placeholder).
     */
    private fun placeCards(pos: Float) {
        val views = cfViews ?: return
        val total = uniqueAlbumList.size
        if (total == 0) return
        val centerIdx = visibleCoversCount / 2
        val base = Math.floor(pos.toDouble()).toInt()
        val shift = base - cardsBase
        if (shift >= visibleCoversCount || shift <= -visibleCoversCount) {
            for (i in 0 until visibleCoversCount) views[i]?.let { bindCoverData(it, wrap(base + i - centerIdx, total)) }
        } else if (shift > 0) {
            repeat(shift) {
                val first = views[0]
                for (i in 0 until visibleCoversCount - 1) views[i] = views[i + 1]
                views[visibleCoversCount - 1] = first
            }
            for (i in visibleCoversCount - shift until visibleCoversCount) {
                views[i]?.let { bindCoverData(it, wrap(base + i - centerIdx, total)) }
            }
        } else if (shift < 0) {
            repeat(-shift) {
                val last = views[visibleCoversCount - 1]
                for (i in visibleCoversCount - 1 downTo 1) views[i] = views[i - 1]
                views[0] = last
            }
            for (i in 0 until -shift) views[i]?.let { bindCoverData(it, wrap(base + i - centerIdx, total)) }
        }
        cardsBase = base
        val frac = pos - base
        for (i in 0 until visibleCoversCount) views[i]?.let { placeCard(it, i - centerIdx - frac) }
    }

    /** Puts [card] at [offset] albums from the centre: between two slots, the values are interpolated. */
    private fun placeCard(card: View, offset: Float) {
        val maxDist = visibleCoversCount / 2
        val o = offset.coerceIn(-maxDist.toFloat(), maxDist.toFloat())
        val k = Math.floor(o.toDouble()).toInt()
        val t = o - k
        val a = k + maxDist
        val b = if (t > 0f) a + 1 else a
        card.translationX = slotX[a] + (slotX[b] - slotX[a]) * t
        card.rotationY = slotRotY[a] + (slotRotY[b] - slotRotY[a]) * t
        val scale = slotScale[a] + (slotScale[b] - slotScale[a]) * t
        card.scaleX = scale
        card.scaleY = scale
        // A card at alpha 0 is not drawn.
        card.alpha = slotAlpha[a] + (slotAlpha[b] - slotAlpha[a]) * t
    }

    /** The slot values, the same as in [initCoverFlowPositions]. */
    private fun makeSlots() {
        val d = activity.resources.displayMetrics.density
        val centerIdx = visibleCoversCount / 2
        for (i in 0 until visibleCoversCount) {
            val dist = Math.abs(i - centerIdx)
            val sign = if (i < centerIdx) -1f else 1f
            slotX[i] = sign * getTransXForDist(dist, d)
            slotRotY[i] = -sign * getRotYForDist(dist)
            slotScale[i] = getScaleForDist(dist)
            slotAlpha[i] = getAlphaForDist(dist)
        }
    }

    private fun wrap(index: Int, total: Int): Int = ((index % total) + total) % total

    private fun scrollCoverFlow(isNext: Boolean, duration: Int) {
        val total = uniqueAlbumList.size
        if (total == 0) return
        val views = cfViews ?: return

        val d = activity.resources.displayMetrics.density
        val centerIdx = visibleCoversCount / 2

        val offScreenX = getTransXForDist(centerIdx, d)
        val offScreenRot = getRotYForDist(centerIdx)
        val offScreenScale = getScaleForDist(centerIdx)

        if (isNext) {
            currentCoverFlowIndex = (currentCoverFlowIndex + 1) % total
            val oldLeft = views[0]

            for (i in 0 until visibleCoversCount - 1) {
                views[i] = views[i + 1]
            }
            views[visibleCoversCount - 1] = oldLeft

            val recycled = views[visibleCoversCount - 1]
            if (recycled != null) {
                bindCoverData(recycled, (currentCoverFlowIndex + centerIdx + total * 10) % total)
                applyTransform(recycled, offScreenX, -offScreenRot, offScreenScale, 0f)
            }
        } else {
            currentCoverFlowIndex = (currentCoverFlowIndex - 1 + total) % total
            val oldRight = views[visibleCoversCount - 1]

            for (i in visibleCoversCount - 1 downTo 1) {
                views[i] = views[i - 1]
            }
            views[0] = oldRight

            val recycled = views[0]
            if (recycled != null) {
                bindCoverData(recycled, (currentCoverFlowIndex - centerIdx + total * 10) % total)
                applyTransform(recycled, -offScreenX, offScreenRot, offScreenScale, 0f)
            }
        }

        arrangeZIndex()

        for (i in 0 until visibleCoversCount) {
            views[i]?.let { setCardTitleAlpha(it, i == centerIdx, duration) }

            val dist = Math.abs(i - centerIdx)
            val sign = if (i < centerIdx) -1f else 1f

            val transX = sign * getTransXForDist(dist, d)
            val rotY = -sign * getRotYForDist(dist)
            val scale = getScaleForDist(dist)
            val alpha = getAlphaForDist(dist)

            animateTransform(views[i], transX, rotY, scale, alpha, duration)
        }

        val statusTitle = localize("Cover Flow") + " (" + (currentCoverFlowIndex + 1) + "/" + total + ")"
        tvBrowserPath.text = statusTitle
        activity.findViewById<TextView>(R.id.tv_status_clock)?.text = statusTitle
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && (event.keyCode == 21 || event.keyCode == 22)) {
            handleCoverFlowWheelInput(event.keyCode, event)
            return true
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == 21 || keyCode == 22) {
            handleCoverFlowWheelInput(keyCode, event)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (currentCoverFlowIndex in uniqueAlbumList.indices) {
                clickFeedback()
                val chosen = uniqueAlbumList[currentCoverFlowIndex]
                onAlbumSelected(chosen)
            }
            return true
        }
        return false
    }

    override fun onBackPressed(): Boolean {
        onNavigateBack()
        return true
    }

    companion object {
        private const val COVER_VIEW_ID = 1001
        private const val TITLE_VIEW_ID = 1002
        private const val ARTIST_VIEW_ID = 1003
        private const val REFLECTION_VIEW_ID = 1004
    }
}
