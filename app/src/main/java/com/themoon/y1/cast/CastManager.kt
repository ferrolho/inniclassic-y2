package com.themoon.y1.cast

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.updateStatusBarPlayOn
import com.themoon.y1.eventlog.EventLog
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.MediaLibraryManager
import com.themoon.y1.views.Notice
import org.json.JSONObject
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Play On: the music of the Y2 plays on a speaker of the Wi-Fi (Sonos, DLNA, Google Cast). The speaker gets the
 * file from [CastMediaServer] on the Y2. The Y2 player continues with no sound and stays in control; [CastSync]
 * keeps the two together. The wheel changes the volume of the speaker.
 */
object CastManager {
    private const val TAG = "CastManager"
    private const val VOLUME_STEP = 2

    /** The speaker that plays, or null when the Y2 plays itself. Set and read on the main thread. */
    @Volatile var device: CastDevice? = null
        private set

    val isActive: Boolean get() = device != null

    private val main = Handler(Looper.getMainLooper())
    private var worker: Handler? = null
    private var renderer: CastRenderer? = null
    private var server: CastMediaServer? = null
    private var sync = CastSync()
    private var session = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var volume: Int? = null
    @Volatile private var volumeTarget: Int? = null
    @Volatile private var lastWheelAt = 0L
    private var ticks = 0
    private var lastDevices: List<CastDevice> = emptyList()
    private var lastSearchAt = 0L

    // --- search ---

    /** The players found by the last search (empty before the first one). */
    val knownDevices: List<CastDevice> get() = lastDevices

    /** Searches in the background and gives the result on the main thread. */
    fun search(done: (List<CastDevice>) -> Unit) {
        workerHandler().post {
            val found = try { CastDiscovery.discover() } catch (e: Exception) { Log.w(TAG, "search: $e"); emptyList() }
            main.post {
                lastDevices = found
                lastSearchAt = SystemClock.uptimeMillis()
                log("search", JSONObject().put("found", found.joinToString { it.id }))
                done(found)
            }
        }
    }

    fun searchIsRecent(): Boolean = lastSearchAt > 0 && SystemClock.uptimeMillis() - lastSearchAt < 60_000L

    // --- session ---

    /** Starts to play on [target] (main thread). Another speaker stops first. */
    fun start(context: Context, target: CastDevice) {
        if (device?.id == target.id) return
        if (device != null) stop(stopRemote = true, reason = null)
        val app = context.applicationContext
        val s = ++session
        device = target
        sync = CastSync()
        volume = null
        volumeTarget = null
        acquireLocks(app)
        AudioPlayerManager.getInstance().applyMasterVolume()
        MainActivity.instance?.updateStatusBarPlayOn()
        log("start", JSONObject().put("device", target.id).put("name", target.name))
        notice("${localize("Playing on")} ${target.name}")
        val r = CastRenderer.forDevice(target)
        renderer = r
        workerHandler().post {
            if (s != session) return@post
            try {
                val srv = server ?: CastMediaServer().also { it.start(); server = it }
                if (srv.port <= 0) srv.start()
                volume = r.volume()
            } catch (e: Exception) {
                Log.w(TAG, "start: $e")
            }
        }
        main.removeCallbacks(tick)
        main.post(tick)
    }

    /** Back to the Y2 (main thread). The Y2 continues at the same place, with its sound on. */
    fun stop(stopRemote: Boolean = true, reason: String? = null) {
        val d = device ?: return
        val r = renderer
        session++
        device = null
        renderer = null
        main.removeCallbacks(tick)
        AudioPlayerManager.getInstance().applyMasterVolume()
        MainActivity.instance?.updateStatusBarPlayOn()
        releaseLocks()
        log("stop", JSONObject().put("device", d.id).put("reason", reason ?: "user"))
        if (reason != null) notice("${d.name}: ${localize(reason)}", error = true)
        workerHandler().post {
            if (stopRemote) try { r?.stop() } catch (e: Exception) { Log.w(TAG, "stop: $e") }
            try { r?.close() } catch (_: Exception) {}
            if (device == null) {
                server?.stop()
                server = null
            }
        }
    }

    // --- volume ---

    /** The wheel while a speaker plays: changes its volume. False when no speaker plays (main thread). */
    fun adjustVolume(isUp: Boolean): Boolean {
        if (!isActive) return false
        val current = volumeTarget ?: volume ?: return true
        val target = (current + if (isUp) VOLUME_STEP else -VOLUME_STEP).coerceIn(0, 100)
        volumeTarget = target
        lastWheelAt = SystemClock.uptimeMillis()
        MainActivity.instance?.showDynamicVolumeOverlay(target, 100, R.drawable.ic_play_on)
        val s = session
        val w = workerHandler()
        w.removeCallbacks(sendVolume)
        w.post(sendVolume)
        log("volume", JSONObject().put("level", target).put("session", s))
        return true
    }

    private val sendVolume = Runnable {
        val t = volumeTarget ?: return@Runnable
        try {
            renderer?.setVolume(t)
            volume = t
        } catch (e: Exception) {
            Log.w(TAG, "volume: $e")
        }
    }

    // --- tick ---

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!isActive) return
            val local = snapshot()
            val s = session
            val info = local.trackPath?.let { path -> lastInfo?.takeIf { it.file.absolutePath == path } ?: trackInfo(path) }
            lastInfo = info
            workerHandler().post { work(s, local, info) }
            main.postDelayed(this, CastSync.TICK_MS)
        }
    }

    private fun snapshot(): CastSync.Local {
        val m = MainActivity.instance
        val apm = AudioPlayerManager.getInstance()
        val file = m?.currentPlaylist?.getOrNull(m.currentIndex)
        val path = file?.absolutePath?.takeIf { file.isFile }
        return CastSync.Local(path, apm.isPlaying(), apm.getCurrentPosition().toLong(), apm.getDuration().toLong(), SystemClock.uptimeMillis())
    }

    /** Title, artist and album of a track (main thread: the library is there). */
    private class TrackInfo(val file: File, val title: String, val artist: String, val album: String)

    private var lastInfo: TrackInfo? = null

    private fun trackInfo(path: String): TrackInfo {
        val file = File(path)
        val song = MainActivity.customLibrary.firstOrNull { it.file.absolutePath == path }
        val title = song?.title?.takeIf { it.isNotEmpty() } ?: file.nameWithoutExtension
        val artist = song?.let { if (it.artist.isNotEmpty()) it.artist else it.albumArtist }.orEmpty()
        return TrackInfo(file, title, artist, song?.album.orEmpty())
    }

    /** The embedded picture of the file, else the image of its folder (cast thread). */
    private fun coverBytes(file: File): ByteArray? {
        try {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(file.absolutePath)
                mmr.embeddedPicture?.let { return it }
            } finally {
                mmr.release()
            }
        } catch (_: Exception) {}
        return try { MediaLibraryManager.getInstance().findFolderCover(file.parentFile)?.readBytes() } catch (_: Exception) { null }
    }

    /** Cast thread: status of the speaker, then the actions of [CastSync]. */
    private fun work(s: Int, local: CastSync.Local, info: TrackInfo?) {
        if (s != session) return
        val r = renderer ?: return
        val remote = if (local.trackPath == sync.loadedTrack) {
            try { r.status() } catch (e: Exception) { Log.w(TAG, "status: $e"); null }
        } else null
        val actions = sync.tick(local, remote)
        // The volume can change in the speaker's app: read it again every 5 s, not while the wheel turns
        if (++ticks % 5 == 0 && SystemClock.uptimeMillis() - lastWheelAt > 3000) {
            try {
                r.volume()?.let { v -> volume = v; volumeTarget = null }
            } catch (_: Exception) {}
        }
        for (a in actions) {
            if (s != session) return
            try {
                when (a) {
                    is CastSync.Action.Load -> load(r, info ?: continue, local, a)
                    CastSync.Action.RemotePlay -> r.play()
                    CastSync.Action.RemotePause -> r.pause()
                    is CastSync.Action.RemoteSeek -> r.seek(a.ms)
                    is CastSync.Action.LocalSeekBy -> onMain(s) { AudioPlayerManager.getInstance().seekRelative(a.deltaMs.toInt(), savePosition = false) }
                    CastSync.Action.LocalPause -> onMain(s) { if (AudioPlayerManager.getInstance().isPlaying()) AudioPlayerManager.getInstance().playOrPauseMusic() }
                    CastSync.Action.LocalPlay -> onMain(s) { if (!AudioPlayerManager.getInstance().isPlaying()) AudioPlayerManager.getInstance().playOrPauseMusic() }
                    is CastSync.Action.End -> onMain(s) { endFromSpeaker(a.reason) }
                }
                if (a !is CastSync.Action.LocalSeekBy) log("action", JSONObject().put("do", a.toString()))
            } catch (e: Exception) {
                Log.w(TAG, "$a: $e")
                log("error", JSONObject().put("do", a.toString()).put("error", e.toString()))
            }
        }
    }

    private fun load(r: CastRenderer, info: TrackInfo, local: CastSync.Local, a: CastSync.Action.Load) {
        val srv = server ?: CastMediaServer().also { it.start(); server = it }
        val host = localAddressFor(device?.host ?: return) ?: throw java.io.IOException("no Wi-Fi address")
        val base = "http://$host:${srv.port}"
        // The Sonos app shows only HTTPS covers: no HTTP cover for a Sonos
        val cover = if (device?.kind == CastDevice.Kind.SONOS) null else coverBytes(info.file)?.let { b ->
            val png = b.size > 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()
            base + srv.registerBytes(b, if (png) "image/png" else "image/jpeg", if (png) "png" else "jpg")
        }
        val https = if (device?.kind == CastDevice.Kind.SONOS) CastCover.httpsCoverUrl(info.file) else null
        val media = CastMedia(
            url = base + srv.register(info.file), mimeType = CastProtocol.mimeType(info.file.name),
            title = info.title, artist = info.artist, album = info.album,
            durationMs = local.durationMs, sizeBytes = info.file.length(), coverUrl = cover, httpsCoverUrl = https
        )
        sync.expectedUrl = media.url
        r.load(media, a.startMs, a.play)
        log("load", JSONObject().put("title", info.title).put("startMs", a.startMs).put("play", a.play)
            .put("cover", cover != null).put("httpsCover", https != null))
    }

    /** The speaker stopped, another app took it, or the connection is lost: the Y2 pauses and has its sound again. */
    private fun endFromSpeaker(reason: String) {
        if (!isActive) return
        val apm = AudioPlayerManager.getInstance()
        if (apm.isPlaying()) apm.playOrPauseMusic()
        stop(stopRemote = false, reason = reason)
    }

    // --- helpers ---

    private fun onMain(s: Int, block: () -> Unit) {
        main.post { if (s == session) block() }
    }

    /** The address of the Y2 on the network of [peer] (the Wi-Fi address changes at each Wi-Fi start). */
    private fun localAddressFor(peer: String): String? = try {
        val sock = DatagramSocket()
        try {
            sock.connect(InetAddress.getByName(peer), 9)
            sock.localAddress?.hostAddress?.takeIf { it != "0.0.0.0" }
        } finally {
            sock.close()
        }
    } catch (_: Exception) { null }

    private fun workerHandler(): Handler {
        worker?.let { return it }
        val t = HandlerThread("Cast")
        t.start()
        return Handler(t.looper).also { worker = it }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "y2:cast").apply { setReferenceCounted(false); acquire() }
            val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "y2:cast").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) {
            Log.w(TAG, "locks: $e")
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    private fun notice(text: String, error: Boolean = false) {
        val ctx = MainActivity.instance ?: return
        if (error) Notice.error(ctx, text) else Notice.show(ctx, text)
    }

    private fun localize(text: String): String = MainActivity.instance?.t(text) ?: text

    private fun log(what: String, fields: JSONObject) {
        Log.i(TAG, "$what $fields")
        EventLog.emit("cast", fields.put("a", what))
    }
}
