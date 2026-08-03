package ee.ukesk.a5s.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import ee.ukesk.a5s.data.Settings

/**
 * Mängib alarmi tsüklis, kuni see käsitsi peatatakse.
 *
 * Teavituse enda heli ei sobi selleks otstarbeks: see mängib korra ja vaikib,
 * mistõttu jääb grillimüra sees kuulmata. Siin mängime alarmihelina
 * ALARM-kanalil (mitte teavituskanalil), mis tähendab, et see järgib telefoni
 * äratuse helitugevust ja tuleb läbi ka vaikse režiimi.
 */
class AlarmPlayer(private val context: Context) {

    companion object {
        private const val TAG = "A5S"

        /** Turvavõrk: ei helise igavesti, kui keegi telefoni juurde ei jõua. */
        private const val AUTO_STOP_AFTER_MS = 5 * 60 * 1000L

        private val VIBRATION_PATTERN = longArrayOf(0, 600, 400)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    var isPlaying: Boolean = false
        private set

    fun start() {
        if (isPlaying) return
        isPlaying = true

        requestAudioFocus()
        startSound()
        startVibration()

        handler.postDelayed({ stop() }, AUTO_STOP_AFTER_MS)
    }

    fun stop() {
        if (!isPlaying) return
        isPlaying = false

        handler.removeCallbacksAndMessages(null)

        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        player = null

        runCatching { vibrator.cancel() }
        abandonAudioFocus()
    }

    private fun startSound() {
        val chosen = Settings.alarmSoundUri.value?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val uri = chosen
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return

        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(audioAttributes)
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure {
            Log.e(TAG, "Alarmiheli ei õnnestunud mängida", it)
        }
    }

    private fun startVibration() {
        runCatching {
            // repeat = 0 → korda mustrit algusest, kuni cancel()
            val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(
                    effect,
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, audioAttributes)
            }
        }.onFailure {
            Log.w(TAG, "Vibratsioon ebaõnnestus", it)
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(audioAttributes)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        }
    }

    /** Kas telefoni äratuse helitugevus on nii madal, et alarmi ei pruugi kuulda. */
    fun alarmVolumeIsLow(): Boolean {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        return max > 0 && current.toFloat() / max < 0.4f
    }
}
