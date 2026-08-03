package ee.ukesk.a5s.util

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Kaks süsteemiseadet, mis vaikselt lõhuvad pika küpsetuse:
 * agressiivne akusäästmine ja liiga vaikne äratuse helitugevus.
 */
object SystemChecks {

    /**
     * Kas äpp on aku optimeerimisest vabastatud. Ilma selleta võib Android
     * pika küpsetuse ajal teenuse maha võtta ja alarm jääb tulemata.
     */
    fun isBatteryOptimizationExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Avab süsteemidialoogi, mis küsib erandit. Kui seade seda otsedialoogi ei
     * toeta, avab üldise akusäästu nimekirja.
     */
    fun requestBatteryOptimizationExemption(context: Context) {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching { context.startActivity(direct) }
            .recoverCatching { context.startActivity(fallback) }
    }

    /** Äratuse helitugevus 0..1. Alarm mängib just sellel kanalil. */
    fun alarmVolumeFraction(context: Context): Float {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 1f
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        if (max <= 0) return 1f
        return am.getStreamVolume(AudioManager.STREAM_ALARM).toFloat() / max
    }

    fun openSoundSettings(context: Context) {
        val intent = Intent(Settings.ACTION_SOUND_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
