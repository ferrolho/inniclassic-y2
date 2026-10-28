package com.themoon.y1.managers

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.themoon.y1.MainActivity
import java.lang.reflect.Method

/**
 * Manages Bluetooth A2DP profile connections, reflection calls to connect/disconnect/priority,
 * and the track data for AVRCP (RemoteControlClient) on the Innioasis Y2.
 */
class BluetoothAvrcpManager private constructor(private val context: Context) {

    private var a2dpProfile: BluetoothProfile? = null

    interface ConnectionCallback {
        fun onConnected(device: BluetoothDevice)
        fun onDisconnected(device: BluetoothDevice)
        fun onError(message: String)
    }

    fun init(onServiceConnected: (() -> Unit)? = null) {
        initRemoteControlClient()
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) {
                    a2dpProfile = proxy
                    onServiceConnected?.invoke()
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.A2DP) {
                    a2dpProfile = null
                }
            }
        }, BluetoothProfile.A2DP)
    }

    fun isA2dpReady(): Boolean = a2dpProfile != null

    fun connectDevice(targetDevice: BluetoothDevice, callback: ConnectionCallback? = null) {
        val a2dp = a2dpProfile
        if (a2dp == null) {
            init { connectDevice(targetDevice, callback) }
            return
        }

        try {
            // Restore priority so Android Bluetooth stack allows connection
            try {
                val setPriorityMethod = a2dp.javaClass.getMethod("setPriority", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                setPriorityMethod.invoke(a2dp, targetDevice, 100) // PRIORITY_ON
            } catch (ignored: Exception) {}

            // Disconnect other already connected devices first
            val connectedDevices = a2dp.connectedDevices
            if (connectedDevices != null) {
                for (connected in connectedDevices) {
                    if (connected.address != targetDevice.address) {
                        disconnectA2dpInternal(a2dp, connected)
                    }
                }
            }

            var connectMethod: Method? = null
            try {
                connectMethod = a2dp.javaClass.getDeclaredMethod("connect", BluetoothDevice::class.java)
            } catch (e: NoSuchMethodException) {
                for (m in a2dp.javaClass.methods) {
                    if (m.name == "connect" && m.parameterTypes.size == 1) {
                        connectMethod = m
                        break
                    }
                }
            }

            if (connectMethod == null) {
                callback?.onError("Connect method not found on A2DP profile")
                return
            }

            connectMethod.isAccessible = true
            val result = connectMethod.invoke(a2dp, targetDevice) as? Boolean ?: false
            if (result) {
                callback?.onConnected(targetDevice)
            } else {
                callback?.onError("Connection rejected by Bluetooth stack")
            }
        } catch (e: Exception) {
            callback?.onError(e.message ?: "Connection failed")
        }
    }

    fun disconnectDevice(targetDevice: BluetoothDevice, callback: ConnectionCallback? = null) {
        val a2dp = a2dpProfile ?: return
        try {
            // Set priority to PRIORITY_OFF (0) so incoming reconnect from earphones is rejected
            try {
                val setPriorityMethod = a2dp.javaClass.getMethod("setPriority", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                setPriorityMethod.invoke(a2dp, targetDevice, 0)
            } catch (ignored: Exception) {}

            disconnectA2dpInternal(a2dp, targetDevice)
            callback?.onDisconnected(targetDevice)
        } catch (e: Exception) {
            callback?.onError(e.message ?: "Disconnect failed")
        }
    }

    private fun disconnectA2dpInternal(a2dp: BluetoothProfile, targetDevice: BluetoothDevice) {
        var disconnectMethod: Method? = null
        try {
            disconnectMethod = a2dp.javaClass.getDeclaredMethod("disconnect", BluetoothDevice::class.java)
        } catch (e: NoSuchMethodException) {
            for (m in a2dp.javaClass.methods) {
                if (m.name == "disconnect" && m.parameterTypes.size == 1) {
                    disconnectMethod = m
                    break
                }
            }
        }
        if (disconnectMethod != null) {
            disconnectMethod.isAccessible = true
            disconnectMethod.invoke(a2dp, targetDevice)
        }
    }

    private var remoteControlClient: android.media.RemoteControlClient? = null
    private var mediaButtonReceiver: android.content.ComponentName? = null
    private var lastTrack: AvrcpMetadataLogic.Track? = null
    private var lastArtSource: android.graphics.Bitmap? = null
    // A new track whose data waits for its duration, and the time when the wait started.
    private var waitingTrack: AvrcpMetadataLogic.Track? = null
    private var waitStartMs = 0L
    private val waitTimeout = Runnable {
        val waited = waitingTrack
        MainActivity.instance?.sendBluetoothMetaToCar()
        // No send (no track now): stop the wait, so that the play state goes out again.
        if (waitingTrack === waited) stopWait()
    }
    private var a2dpWatchRegistered = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Registers the media button receiver and a RemoteControlClient, so the Bluetooth AVRCP
     * service of the ROM can send the track title, artist, album and play state to a car.
     */
    fun initRemoteControlClient() {
        if (remoteControlClient != null) return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        try {
            val receiver = android.content.ComponentName(
                context.packageName,
                "${context.packageName}.MainActivity\$MediaBtnReceiver"
            )
            mediaButtonReceiver = receiver
            audioManager.registerMediaButtonEventReceiver(receiver)

            val mediaButtonIntent = android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON)
            mediaButtonIntent.component = receiver
            var pendingIntentFlags = 0
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                pendingIntentFlags = android.app.PendingIntent.FLAG_IMMUTABLE
            }
            val mediaPendingIntent = android.app.PendingIntent.getBroadcast(
                context, 0, mediaButtonIntent, pendingIntentFlags
            )

            val rcc = android.media.RemoteControlClient(mediaPendingIntent)
            rcc.setTransportControlFlags(
                android.media.RemoteControlClient.FLAG_KEY_MEDIA_PLAY or
                    android.media.RemoteControlClient.FLAG_KEY_MEDIA_PAUSE or
                    android.media.RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE or
                    android.media.RemoteControlClient.FLAG_KEY_MEDIA_NEXT or
                    android.media.RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS
            )
            rcc.setOnGetPlaybackPositionListener {
                AudioPlayerManager.getInstance().getCurrentPosition().toLong()
            }
            audioManager.registerRemoteControlClient(rcc)
            remoteControlClient = rcc
        } catch (e: Exception) {
            android.util.Log.w(TAG, "RemoteControlClient registration failed", e)
            return
        }
        registerA2dpWatch()
    }

    /**
     * On Android 4.4 the last app that registers a media button receiver gets the AVRCP keys and
     * shows its data. Register again when a Bluetooth audio device connects, and send the data again
     * after AVRCP is up (it connects after A2DP).
     */
    private fun registerA2dpWatch() {
        if (a2dpWatchRegistered) return
        val watch = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, intent: android.content.Intent) {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                if (state != BluetoothProfile.STATE_CONNECTED) return
                handler.removeCallbacks(reassert)
                reassert.run()
                handler.postDelayed(reassert, AVRCP_SETTLE_MS)
            }
        }
        try {
            context.registerReceiver(watch, android.content.IntentFilter(A2DP_CONNECTION_STATE_CHANGED))
            a2dpWatchRegistered = true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "A2DP watch failed", e)
        }
    }

    private val reassert = Runnable {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        try {
            mediaButtonReceiver?.let { audioManager?.registerMediaButtonEventReceiver(it) }
            remoteControlClient?.let { audioManager?.registerRemoteControlClient(it) }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Media button re-registration failed", e)
        }
        // Send all data again: a new AVRCP controller asks for it now.
        hasAudioFocus = false
        lastTrack = null
        lastArtSource = null
        stopWait()
        MainActivity.instance?.let { main ->
            try { main.updatePlayerUI() } catch (_: Exception) {}
        }
    }

    /**
     * Sends the track data of the file at [path]. The duration comes from the player only when the
     * player holds this file. The data of a new track waits for the duration (at most
     * [AvrcpMetadataLogic.MAX_DURATION_WAIT_MS]), because the car reads the data only at a track change.
     */
    fun updateMetadata(path: String, title: String?, artist: String?, album: String?, albumArtBmp: android.graphics.Bitmap?) {
        if (remoteControlClient == null) initRemoteControlClient()
        val rcc = remoteControlClient ?: return
        val track = AvrcpMetadataLogic.Track(
            title = title ?: "Unknown Title",
            artist = artist ?: "Unknown Artist",
            album = album ?: "Unknown Album",
            durationMs = AudioPlayerManager.getInstance().getDurationOf(path).toLong()
        )
        val art = albumArtBmp?.takeIf { !it.isRecycled }
        val now = android.os.SystemClock.uptimeMillis()
        val waited = if (AvrcpMetadataLogic.isNewTrack(waitingTrack, track)) 0L else now - waitStartMs
        when (AvrcpMetadataLogic.action(lastTrack, track, art !== lastArtSource, waited)) {
            AvrcpMetadataLogic.Action.SKIP -> {
                stopWait()
                return
            }
            AvrcpMetadataLogic.Action.WAIT -> {
                if (waited == 0L) {
                    waitingTrack = track
                    waitStartMs = now
                    handler.removeCallbacks(waitTimeout)
                    handler.postDelayed(waitTimeout, AvrcpMetadataLogic.MAX_DURATION_WAIT_MS)
                }
                return
            }
            AvrcpMetadataLogic.Action.SEND -> stopWait()
        }
        try {
            val editor = rcc.editMetadata(true)
            for ((key, value) in AvrcpMetadataLogic.strings(track)) editor.putString(key, value)
            AvrcpMetadataLogic.duration(track)?.let {
                editor.putLong(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION, it)
            }
            // A small copy: RemoteControlClient can recycle the bitmap that it gets, and the
            // Now Playing screen still shows the original.
            artworkCopy(art)?.let {
                editor.putBitmap(android.media.RemoteControlClient.MetadataEditor.BITMAP_KEY_ARTWORK, it)
            }
            editor.apply()
            android.util.Log.w(TAG, AvrcpMetadataLogic.logLine(track))
            lastTrack = track
            lastArtSource = art
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Metadata update failed", e)
            return
        }
        // The play state and position after the track data: the ROM sets the position to 0 at a track change.
        updatePlaybackState(AudioPlayerManager.getInstance().isPlaying())
    }

    private fun stopWait() {
        handler.removeCallbacks(waitTimeout)
        waitingTrack = null
    }

    private fun artworkCopy(source: android.graphics.Bitmap?): android.graphics.Bitmap? {
        if (source == null) return null
        val size = AvrcpMetadataLogic.artSize(source.width, source.height) ?: return null
        return try {
            android.graphics.Bitmap.createScaledBitmap(source, size.first, size.second, true)
                .let { if (it === source) source.copy(source.config ?: android.graphics.Bitmap.Config.RGB_565, false) else it }
        } catch (e: Throwable) {
            null
        }
    }

    @Volatile
    private var hasAudioFocus = false

    // Android 4.4 gives the RemoteControlClient data to AVRCP only while the same app holds the
    // music audio focus. The app keeps playing on a focus loss (as before); it asks again at the next play.
    private val focusListener = android.media.AudioManager.OnAudioFocusChangeListener { change ->
        if (change == android.media.AudioManager.AUDIOFOCUS_LOSS) hasAudioFocus = false
    }

    private fun requestAudioFocus() {
        if (hasAudioFocus) return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        try {
            hasAudioFocus = audioManager.requestAudioFocus(
                focusListener, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN
            ) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Audio focus request failed", e)
        }
    }

    fun updatePlaybackState(isPlaying: Boolean) {
        if (remoteControlClient == null) initRemoteControlClient()
        val rcc = remoteControlClient ?: return
        if (isPlaying) requestAudioFocus()
        // The track data of a new track waits for its duration: send the play state after it.
        if (waitingTrack != null) return
        val player = AudioPlayerManager.getInstance()
        val hasTrack = player.getDuration() > 0 || lastTrack != null
        val state = AvrcpMetadataLogic.playState(hasTrack, isPlaying)
        try {
            rcc.setPlaybackState(state, player.getCurrentPosition().toLong(), if (isPlaying) 1.0f else 0f)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Play state update failed", e)
        }
    }

    companion object {
        private const val TAG = "BtAvrcp"
        private const val A2DP_CONNECTION_STATE_CHANGED = "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"
        private const val AVRCP_SETTLE_MS = 2000L

        @Volatile
        private var instance: BluetoothAvrcpManager? = null
        fun getInstance(context: Context): BluetoothAvrcpManager {
            return instance ?: synchronized(this) {
                instance ?: BluetoothAvrcpManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
