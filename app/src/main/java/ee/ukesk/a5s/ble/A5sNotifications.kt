package ee.ukesk.a5s.ble

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ee.ukesk.a5s.MainActivity
import ee.ukesk.a5s.R
import kotlin.math.roundToInt

/** Ühtlane kraadikuju kõikjal, kus temperatuur kasutajale silma jääb. */
internal fun formatCelsius(celsius: Double): String =
    "${(celsius * 10).roundToInt() / 10.0} °C"

/**
 * Kõik teavitused ühes kohas: püsiteavitus, mis hoiab taustateenust elus, ja
 * alarm, mis liha valmimisest teada annab.
 *
 * Oma põhilõime handler on siin meelega eraldi teenuse omast. Teenus tühjendab
 * BLE koristamisel kogu oma järjekorra ja viis varem sealt kaasa ka ootel
 * teavituse uuenduse.
 */
class A5sNotifications(private val context: Context) {

    companion object {
        const val CHANNEL_ONGOING = "a5s_ongoing"
        const val CHANNEL_ALARM = "a5s_alarm_v2"

        const val ID_ONGOING = 1
        private const val ID_ALARM = 2
        private const val ID_PRE_WARN = 3

        /** Mõõtmisi tuleb ~165 ms tagant; nii tihti teavitust üle ei kirjuta. */
        private const val THROTTLE_MS = 1_000L
    }

    private val handler = Handler(Looper.getMainLooper())

    private val manager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private var lastUpdateAt = 0L
    private var lastContent: String? = null

    @Volatile
    private var updateQueued = false

    private val updater = Runnable {
        updateQueued = false
        updateOngoing()
    }

    fun createChannels() {
        val ongoing = NotificationChannel(
            CHANNEL_ONGOING,
            "Mõõtmine käib",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Püsiteavitus, mis hoiab ühenduse elus"
            setShowBadge(false)
        }

        val alarm = NotificationChannel(
            CHANNEL_ALARM,
            "Alarmid",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Teavitus, kui liha jõuab sihttemperatuurini"
            // Heli ja vibratsiooni mängib AlarmPlayer tsüklis. Kui kanal teeks
            // seda ka ise, kostaks kaks heli korraga.
            setSound(null, null)
            enableVibration(false)
        }

        manager.createNotificationChannels(listOf(ongoing, alarm))
    }

    // ------------------------------------------------------------- püsiteavitus

    /** Teenus vajab seda foreground service'i käivitamiseks. */
    fun buildOngoing(): Notification {
        val (title, text) = ongoingContent()
        return buildOngoing(title, text)
    }

    /**
     * Tohib kutsuda tihti ja mitmest lõimest — päris töö käib põhilõimes ja
     * ainult siis, kui teavituse tekst päriselt muutub.
     */
    fun requestOngoingUpdate() {
        if (updateQueued) return
        updateQueued = true
        handler.post(updater)
    }

    /**
     * Püsiteavituse sisu. Teine väli on null, kui teisel real ei ole midagi
     * öelda — siis jääb see rida hoopis ära.
     */
    private fun ongoingContent(): Pair<String, String?> {
        val state = ThermometerRepository.state.value

        // Demo sond kuulub teavitusse ainult küpsetuse ajal. Muidu seisaks ta
        // seal alaliselt toatemperatuuriga ja lükkaks päris sondi näidu kõrvale.
        val probes = state.probeList.filter { !it.isDemo || it.isCooking }

        val title = when {
            probes.isNotEmpty() -> probes.joinToString("  ·  ") {
                "${it.displayName} ${formatCelsius(it.celsius)}"
            }
            state.connection == ConnectionState.SCANNING -> "Otsin termomeetrit…"
            state.connection == ConnectionState.CONNECTING -> "Ühendan…"
            state.connection == ConnectionState.RECONNECTING -> "Ühendus katkes, proovin uuesti…"
            else -> "A5S Termomeeter"
        }

        // Ilma küpsetuseta jääb teine rida tühjaks. Varem seisis seal
        // "Küpsetust ei käi" — rida teksti mitte millegi kohta.
        val cooking = probes.mapNotNull { probe ->
            probe.target?.let { "${it.meat}, ${it.doneness} → ${it.celsius} °C" }
        }
        return title to cooking.joinToString(" · ").ifEmpty { null }
    }

    private fun buildOngoing(title: String, text: String?): Notification =
        NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_thermometer)
            .setContentTitle(title)
            .apply { text?.let { setContentText(it) } }
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()

    private fun updateOngoing() {
        if (!canPost()) return

        val (title, text) = ongoingContent()
        val content = "$title\n$text"
        if (content == lastContent) return

        val now = System.currentTimeMillis()
        val sinceLast = now - lastUpdateAt
        if (sinceLast < THROTTLE_MS) {
            // Muudatust ei visata ära, vaid lükatakse edasi. Varem kadus nii
            // viimane muutus — näiteks küpsetuse lõpp, mille järel mõõtmisi
            // enam ei tule — ja teavitusse jäi vana näit rippuma.
            updateQueued = true
            handler.postDelayed(updater, THROTTLE_MS - sinceLast)
            return
        }

        lastUpdateAt = now
        lastContent = content
        manager.notify(ID_ONGOING, buildOngoing(title, text))
    }

    // -------------------------------------------------------------------- alarm

    /**
     * NB: heli käivitab kutsuja enne seda meetodit. Just see osa peab grilli
     * juures kohale jõudma ka siis, kui teavituste luba puudub.
     */
    fun showAlarm(title: String, text: String, urgent: Boolean) {
        if (!canPost()) return

        val notification = NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_stat_thermometer)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent())
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .apply {
                if (urgent) {
                    setOngoing(true)
                    setFullScreenIntent(contentIntent(), true)
                    addAction(R.drawable.ic_stat_thermometer, "Peata alarm", silenceIntent())
                } else {
                    setAutoCancel(true)
                }
            }
            .build()

        manager.notify(if (urgent) ID_ALARM else ID_PRE_WARN, notification)
    }

    fun cancelAlarm() = manager.cancel(ID_ALARM)

    // ------------------------------------------------------------------- muu

    fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun silenceIntent(): PendingIntent = PendingIntent.getService(
        context,
        1,
        Intent(context, ThermometerService::class.java)
            .setAction(ThermometerService.ACTION_SILENCE_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
