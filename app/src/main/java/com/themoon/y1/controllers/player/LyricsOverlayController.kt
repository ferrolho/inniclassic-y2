package com.themoon.y1.controllers.player

import android.content.Context
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.themoon.y1.MainActivity
import com.themoon.y1.ThemeManager
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.M4bChapterParser
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.util.ArrayList
import java.util.Collections
import java.util.TreeMap
import java.util.regex.Pattern

/**
 * Controller and parser for synchronized LRC, embedded USLT ID3, and M4B chapter lyrics.
 * Manages the overlay ScrollView and synchronized highlight updates.
 */
class LyricsOverlayController(
    private val context: Context,
    private val playerInnerLayout: ViewGroup,
    private val albumViews: List<View> = emptyList()
) {

    val lyricScrollView: ScrollView = ScrollView(context).apply {
        visibility = View.GONE
        isScrollbarFadingEnabled = true
    }

    val tvLyrics: TextView = TextView(context).apply {
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 16f
        gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
        setLineSpacing(10f, 1.2f)
        setPadding(20, 40, 20, 40)
        typeface = ThemeManager.getCustomFontBold()
    }

    private val currentLyrics = TreeMap<Int, String>()
    private var lyricTimestamps = ArrayList<Int>()
    private var plainLyrics: String? = null
    private var lastLyricIndex = -1
    private var lineStarts = IntArray(0)
    private var lineEnds = IntArray(0)
    private var activeSpan: ForegroundColorSpan? = null
    private var plainColor = 0xFFFFFFFF.toInt()
    private var activeLineColor = 0xFF2588DC.toInt()
    private var otherLineColor = 0x8CFFFFFF.toInt()
    private var colorsOverridden = false

    // Playback position from the last progress tick; frames extrapolate from it while playing
    private var progressPos = 0
    private var progressDuration = 0
    private var progressTime = 0L
    private var progressPlaying = false

    // Scroll position drawn on the last frame (-1 = jump to the target), and the wheel target (-1 = follow playback)
    private var scrollPos = -1f
    private var manualScrollY = -1f
    private var manualLyricIndex = -1
    private var frameScheduled = false
    private val frameTask = Runnable { onFrame() }
    private val followPlayback = Runnable {
        manualLyricIndex = -1
        manualScrollY = -1f
        scheduleFrame()
    }

    val hasLyrics: Boolean
        get() = currentLyrics.isNotEmpty() || plainLyrics != null

    /** Synced LRC lyrics and M4B chapters follow playback; plain lyrics do not. */
    val isSynced: Boolean
        get() = currentLyrics.isNotEmpty()

    init {
        // Take the place and size of the album art (and its reflection) when shown
        val visLp = ViewGroup.MarginLayoutParams(
            albumViews.firstOrNull()?.layoutParams?.width ?: ViewGroup.LayoutParams.MATCH_PARENT,
            if (albumViews.isEmpty()) ViewGroup.LayoutParams.MATCH_PARENT
            else albumViews.sumOf { it.layoutParams.height + ((it.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0) }
        )
        lyricScrollView.addView(
            tvLyrics,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        playerInnerLayout.addView(lyricScrollView, 0, visLp)
    }

    fun show() {
        if (!hasLyrics) return
        if (!colorsOverridden) applyThemeColors()
        albumViews.forEach { it.visibility = View.GONE }
        lyricScrollView.visibility = View.VISIBLE
        manualLyricIndex = -1
        manualScrollY = -1f
        scrollPos = -1f
        lyricScrollView.removeCallbacks(followPlayback)
        if (isSynced) {
            buildSyncedText()
        } else {
            tvLyrics.setTextColor(plainColor)
            tvLyrics.setPadding(20, 40, 20, 40)
        }
        scheduleFrame()
    }

    fun hide() {
        lyricScrollView.visibility = View.GONE
        lyricScrollView.removeCallbacks(followPlayback)
        lyricScrollView.removeCallbacks(frameTask)
        frameScheduled = false
        albumViews.forEach { it.visibility = View.VISIBLE }
    }

    /** Overrides the theme colours that show() uses by default. */
    fun applyColors(primary: Int, secondary: Int, highlight: Int) {
        colorsOverridden = true
        setColors(primary, secondary, highlight)
    }

    /** Same text colours as Now Playing; the line being sung uses the focus colour of list rows. */
    private fun applyThemeColors() {
        val classic = ThemeManager.isClassicTheme()
        setColors(
            if (classic) 0xFF000000.toInt() else ThemeManager.getTextColorPrimary(),
            if (classic) 0xFF3C3C43.toInt() else ThemeManager.getTextColorSecondary(),
            if (classic) 0xFF2588DC.toInt() else ThemeManager.getListButtonFocusedBg()
        )
    }

    private fun setColors(primary: Int, secondary: Int, highlight: Int) {
        plainColor = primary
        activeLineColor = highlight or 0xFF000000.toInt()
        otherLineColor = (secondary and 0x00FFFFFF) or 0x8C000000.toInt()
    }

    /**
     * Wheel scrolling. Synced lyrics move by whole lines and follow playback again after
     * 3 s without input; plain lyrics stop the autoscroll until the overlay shows again.
     */
    fun scrollLines(lines: Int) {
        if (!isShowing() || tvLyrics.layout == null) return
        if (isSynced) {
            val from = if (manualLyricIndex != -1) manualLyricIndex else lastLyricIndex
            manualLyricIndex = Math.max(0, Math.min(lyricTimestamps.size - 1, from + lines))
            manualScrollY = clampScroll(lineY(manualLyricIndex) - lyricScrollView.height / 3f)
            lyricScrollView.removeCallbacks(followPlayback)
            lyricScrollView.postDelayed(followPlayback, 3000)
        } else {
            val from = if (manualScrollY >= 0f) manualScrollY else lyricScrollView.scrollY.toFloat()
            manualScrollY = clampScroll(from + lines * tvLyrics.lineHeight)
        }
        scheduleFrame()
    }

    fun isShowing(): Boolean = lyricScrollView.visibility == View.VISIBLE

    fun loadLyrics(audioFile: File?) {
        currentLyrics.clear()
        lyricTimestamps.clear()
        lastLyricIndex = -1
        lineStarts = IntArray(0)
        manualLyricIndex = -1
        plainLyrics = null
        tvLyrics.text = ""

        if (audioFile == null || !audioFile.exists()) return
        val path = audioFile.absolutePath

        // 1. M4B audiobooks: extract chapter timestamps
        if (path.endsWith(".m4b", ignoreCase = true)) {
            val chResult = M4bChapterParser.extractChapters(audioFile)
            if (chResult.chaptersMap.isNotEmpty()) {
                currentLyrics.putAll(chResult.chaptersMap)
                lyricTimestamps.addAll(chResult.timestamps)
                return
            }
        }

        // 2. External .lrc file in same directory
        val dotIdx = path.lastIndexOf(".")
        if (dotIdx > 0) {
            val lrcFile = File(path.substring(0, dotIdx) + ".lrc")
            if (lrcFile.exists()) {
                try {
                    val br = BufferedReader(InputStreamReader(FileInputStream(lrcFile), "UTF-8"))
                    val pattern = Pattern.compile("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)")
                    var line: String?

                    while (br.readLine().also { line = it } != null) {
                        val m = pattern.matcher(line ?: "")
                        if (m.find()) {
                            val min = m.group(1)?.toInt() ?: 0
                            val sec = m.group(2)?.toInt() ?: 0
                            val msStr = m.group(3) ?: "0"
                            var ms = msStr.toInt()
                            if (msStr.length == 2) ms *= 10
                            val totalMs = (min * 60 * 1000) + (sec * 1000) + ms
                            val text = m.group(4)?.trim() ?: ""
                            if (text.isNotEmpty()) {
                                currentLyrics[totalMs] = text
                            }
                        }
                    }
                    br.close()
                    lyricTimestamps = ArrayList(currentLyrics.keys)
                    return
                } catch (ignored: Exception) {}
            }
        }

        // 3. Embedded tags: ALAC / FLAC / MP3 ID3 USLT
        val lower = path.lowercase()
        if (lower.endsWith(".m4a") || lower.endsWith(".alac")) {
            val alacTags = AudioPlayerManager.getInstance().extractAlacMetadata(audioFile)
            if (alacTags.size > 7 && alacTags[7] != null) {
                plainLyrics = alacTags[7] as? String
            }
        } else if (lower.endsWith(".flac")) {
            val flacTags = AudioPlayerManager.getInstance().extractFlacMetadata(audioFile)
            if (flacTags.size > 7 && flacTags[7] != null) {
                plainLyrics = flacTags[7] as? String
            }
        } else {
            plainLyrics = extractEmbeddedLyrics(audioFile)
        }

        // 4. Test if plain lyrics string contains embedded LRC timestamps
        val candidate = plainLyrics
        if (!candidate.isNullOrEmpty()) {
            val pattern = Pattern.compile("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)")
            var isSyncedLrc = false
            val lines = candidate.split("\\r?\\n".toRegex())

            for (l in lines) {
                val m = pattern.matcher(l)
                if (m.find()) {
                    isSyncedLrc = true
                    val min = m.group(1)?.toInt() ?: 0
                    val sec = m.group(2)?.toInt() ?: 0
                    val msStr = m.group(3) ?: "0"
                    var ms = msStr.toInt()
                    if (msStr.length == 2) ms *= 10
                    val totalMs = (min * 60 * 1000) + (sec * 1000) + ms
                    val text = m.group(4)?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        currentLyrics[totalMs] = text
                    }
                }
            }

            if (isSyncedLrc) {
                lyricTimestamps = ArrayList(currentLyrics.keys)
                plainLyrics = null
                return
            }

            tvLyrics.text = candidate
        }
    }

    private fun extractEmbeddedLyrics(file: File): String? {
        try {
            val raf = RandomAccessFile(file, "r")
            val header = ByteArray(10)
            raf.readFully(header)

            if (header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
                val majorVersion = header[3].toInt()
                val tagSize = ((header[6].toInt() and 0x7F) shl 21) or
                        ((header[7].toInt() and 0x7F) shl 14) or
                        ((header[8].toInt() and 0x7F) shl 7) or
                        (header[9].toInt() and 0x7F)

                val readSize = Math.min(tagSize, 512 * 1024)
                val tagData = ByteArray(readSize)
                val actualRead = raf.read(tagData)
                raf.close()

                var pos = 0
                while (pos < actualRead - 10) {
                    val frameId = String(tagData, pos, 4)
                    val frameSize: Int = if (majorVersion == 4) {
                        ((tagData[pos + 4].toInt() and 0x7F) shl 21) or
                                ((tagData[pos + 5].toInt() and 0x7F) shl 14) or
                                ((tagData[pos + 6].toInt() and 0x7F) shl 7) or
                                (tagData[pos + 7].toInt() and 0x7F)
                    } else {
                        ((tagData[pos + 4].toInt() and 0xFF) shl 24) or
                                ((tagData[pos + 5].toInt() and 0xFF) shl 16) or
                                ((tagData[pos + 6].toInt() and 0xFF) shl 8) or
                                (tagData[pos + 7].toInt() and 0xFF)
                    }

                    if (frameSize <= 0 || frameSize > actualRead - pos - 10) break

                    if (frameId == "USLT") {
                        val encoding = tagData[pos + 10].toInt()
                        var textPos = pos + 14

                        if (encoding == 1 || encoding == 2) {
                            while (textPos < pos + 10 + frameSize - 1) {
                                if (tagData[textPos].toInt() == 0 && tagData[textPos + 1].toInt() == 0) {
                                    textPos += 2
                                    break
                                }
                                textPos++
                            }
                        } else {
                            while (textPos < pos + 10 + frameSize) {
                                if (tagData[textPos].toInt() == 0) {
                                    textPos += 1
                                    break
                                }
                                textPos++
                            }
                        }

                        val lyricsLength = (pos + 10 + frameSize) - textPos
                        if (lyricsLength > 0) {
                            val charset = when (encoding) {
                                0 -> "ISO-8859-1"
                                1 -> "UTF-16"
                                2 -> "UTF-16BE"
                                else -> "UTF-8"
                            }
                            return String(tagData, textPos, lyricsLength, charset(charset)).trim()
                        }
                    }
                    pos += 10 + frameSize
                }
            } else {
                raf.close()
            }
        } catch (ignored: Exception) {}
        return null
    }

    fun updateProgress(currentPos: Int, duration: Int, playing: Boolean) {
        progressPos = currentPos
        progressDuration = duration
        progressTime = SystemClock.uptimeMillis()
        progressPlaying = playing
        if (isShowing()) scheduleFrame()
    }

    /** All synced lines in one text, so the view can glide between them. */
    private fun buildSyncedText() {
        lyricTimestamps = ArrayList(currentLyrics.keys)
        val sb = SpannableStringBuilder()
        lineStarts = IntArray(lyricTimestamps.size)
        lineEnds = IntArray(lyricTimestamps.size)
        for (i in lyricTimestamps.indices) {
            if (i > 0) sb.append("\n\n")
            lineStarts[i] = sb.length
            sb.append(currentLyrics[lyricTimestamps[i]])
            lineEnds[i] = sb.length
        }
        tvLyrics.setTextColor(otherLineColor)
        tvLyrics.setPadding(20, 10, 20, 10)
        tvLyrics.setText(sb, TextView.BufferType.SPANNABLE)
        activeSpan = null
        lastLyricIndex = -1
    }

    private fun scheduleFrame() {
        if (frameScheduled) return
        frameScheduled = true
        lyricScrollView.postOnAnimation(frameTask)
    }

    /** Moves the scroll position a step towards the target; runs every frame only while playing or moving. */
    private fun onFrame() {
        frameScheduled = false
        // isShown is false when a parent is hidden too; the next progress tick starts the frames again
        if (!lyricScrollView.isShown || MainActivity.instance?.isScreenSleeping == true) return
        if (isSynced && lineStarts.isEmpty()) buildSyncedText()
        if (tvLyrics.layout == null || tvLyrics.isLayoutRequested || lyricScrollView.isLayoutRequested) {
            scheduleFrame()
            return
        }

        // Stop after 1.5 s without a progress tick, so a stopped progress loop also stops the frames
        val now = SystemClock.uptimeMillis()
        val live = progressPlaying && now - progressTime < 1500
        val pos = if (live) progressPos + ((now - progressTime) * AudioPlayerManager.getInstance().getCurrentSpeed()).toInt() else progressPos

        if (isSynced) setActiveLine(lineIndexAt(pos))
        val target = clampScroll(
            when {
                manualScrollY >= 0f -> manualScrollY
                isSynced -> syncedScrollY(pos)
                else -> plainScrollY(pos)
            }
        )
        val diff = target - scrollPos
        scrollPos = if (scrollPos < 0f || Math.abs(diff) < 1f) target else scrollPos + diff * 0.2f
        lyricScrollView.scrollTo(0, Math.round(scrollPos))
        if (live || scrollPos != target) scheduleFrame()
    }

    /** Puts the line being sung at a third of the height, and glides to the next line until its time. */
    private fun syncedScrollY(pos: Int): Float {
        val i = lineIndexAt(pos)
        if (i < 0) return 0f
        var y = lineY(i)
        if (i + 1 < lyricTimestamps.size) {
            val start = lyricTimestamps[i]
            val end = lyricTimestamps[i + 1]
            val fraction = Math.max(0f, Math.min(1f, (pos - start).toFloat() / Math.max(1, end - start)))
            y += (lineY(i + 1) - y) * fraction
        }
        return y - lyricScrollView.height / 3f
    }

    /** Plain lyrics scroll in proportion to the playback position. */
    private fun plainScrollY(pos: Int): Float {
        if (progressDuration <= 0) return 0f
        val ratio = (pos.toFloat() / progressDuration - 0.15f) / 0.70f
        return maxScroll() * Math.max(0f, Math.min(1f, ratio))
    }

    private fun setActiveLine(index: Int) {
        if (index == lastLyricIndex) return
        lastLyricIndex = index
        val text = tvLyrics.text as? Spannable ?: return
        activeSpan?.let { text.removeSpan(it) }
        activeSpan = null
        if (index >= 0) {
            val span = ForegroundColorSpan(activeLineColor)
            text.setSpan(span, lineStarts[index], lineEnds[index], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            activeSpan = span
        }
    }

    /** Index of the line being sung at [pos], or -1 before the first line. */
    private fun lineIndexAt(pos: Int): Int {
        val i = Collections.binarySearch(lyricTimestamps, pos)
        return if (i >= 0) i else -i - 2
    }

    private fun lineY(index: Int): Float {
        val layout = tvLyrics.layout ?: return 0f
        return (layout.getLineTop(layout.getLineForOffset(lineStarts[index])) + tvLyrics.totalPaddingTop).toFloat()
    }

    private fun maxScroll(): Int = Math.max(0, tvLyrics.height - lyricScrollView.height)

    private fun clampScroll(y: Float): Float = Math.max(0f, Math.min(maxScroll().toFloat(), y))
}
