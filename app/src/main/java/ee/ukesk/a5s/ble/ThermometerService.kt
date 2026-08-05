package ee.ukesk.a5s.ble

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ee.ukesk.a5s.alarm.AlarmPlayer
import ee.ukesk.a5s.data.CookRecorder
import ee.ukesk.a5s.data.CookTarget
import ee.ukesk.a5s.data.Settings
import ee.ukesk.a5s.data.db.AppDatabase
import ee.ukesk.a5s.data.db.KnownProbeEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Hoiab BLE-ühendust kõigi teadaolevate A5S baasidega ka siis, kui ekraan on
 * kustunud, ja annab märku, kui liha jõuab sihttemperatuurini.
 *
 * Foreground service on siin kohustuslik, mitte mugavus: ilma selleta tapab
 * Android ühenduse mõne minutiga ja alarm jääb tulemata just siis, kui seda
 * kõige rohkem vaja on.
 *
 * Teenus ise on liim. Päris töö teevad [BleConnectionManager] (ühendused ja
 * otsing), [A5sNotifications] (teavitused) ja [DemoSimulator] (virtuaalne sond);
 * siia jääb elutsükkel, käskude vastuvõtt ja mõõtmise tee alarmini.
 */
class ThermometerService : android.app.Service(), BleConnectionManager.Listener {

    companion object {
        private const val TAG = "A5S"

        const val ACTION_START = "ee.ukesk.a5s.START"
        const val ACTION_STOP = "ee.ukesk.a5s.STOP"
        const val ACTION_SILENCE_ALARM = "ee.ukesk.a5s.SILENCE_ALARM"
        const val ACTION_TEST_ALARM = "ee.ukesk.a5s.TEST_ALARM"
        const val ACTION_SCAN_BASES = "ee.ukesk.a5s.SCAN_BASES"
        const val ACTION_STOP_SCAN = "ee.ukesk.a5s.STOP_SCAN"
        const val ACTION_REFRESH_DEVICES = "ee.ukesk.a5s.REFRESH_DEVICES"
        const val ACTION_RETRY_CONNECT = "ee.ukesk.a5s.RETRY_CONNECT"
        const val ACTION_DEMO_BOOST = "ee.ukesk.a5s.DEMO_BOOST"
        const val ACTION_DEMO_RESET_BOOST = "ee.ukesk.a5s.DEMO_RESET_BOOST"

        fun start(context: Context) = send(context, ACTION_START)
        fun stop(context: Context) = send(context, ACTION_STOP)
        fun silenceAlarm(context: Context) = send(context, ACTION_SILENCE_ALARM)
        fun testAlarm(context: Context) = send(context, ACTION_TEST_ALARM)
        fun scanForBases(context: Context) = send(context, ACTION_SCAN_BASES)
        fun stopScan(context: Context) = send(context, ACTION_STOP_SCAN)
        fun refreshDevices(context: Context) = send(context, ACTION_REFRESH_DEVICES)
        fun retryConnect(context: Context) = send(context, ACTION_RETRY_CONNECT)
        fun demoBoost(context: Context) = send(context, ACTION_DEMO_BOOST)
        fun demoResetBoost(context: Context) = send(context, ACTION_DEMO_RESET_BOOST)

        /**
         * Teadlikult startService, mitte startForegroundService. Viimane annab
         * Androidile lubaduse, et teenus läheb viie sekundiga esiplaanile — ja
         * kui load puuduvad, ei saa me seda lubadust täita ning protsess
         * tapetakse. Kõik käivitused tulevad avatud äpist, seega startService
         * on lubatud.
         */
        private fun send(context: Context, action: String) {
            val intent = Intent(context, ThermometerService::class.java).setAction(action)
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "Teenust ei õnnestunud käivitada: $action", it) }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val alarmPlayer by lazy { AlarmPlayer(this) }
    private val deviceDao by lazy { AppDatabase.get(this).deviceDao() }

    private val notifications by lazy { A5sNotifications(this) }
    private val ble by lazy { BleConnectionManager(this, this) }
    private val demo by lazy { DemoSimulator(serviceScope, ::processReading) }

    private val knownProbeAddresses = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var knownBaseAddresses: List<String> = emptyList()

    private var running = false

    /** Praegune foreground service'i tüüp; 0 = Bluetoothi luba puudub. */
    private var foregroundType = -1

    /**
     * Bluetoothi sisselülitamine peab ühenduse ise taastama. Ilma selleta jäi
     * äpp pärast lennurežiimi või juhuslikku väljalülitamist seisma seniks,
     * kuni kasutaja ta uuesti avas — küpsetuse ajal tähendanuks see saamata
     * alarmi.
     */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "Bluetooth lülitati sisse — ühendan uuesti")
                    ble.onBluetoothOn()
                }
                BluetoothAdapter.STATE_OFF -> {
                    Log.i(TAG, "Bluetooth lülitati välja")
                    ble.onBluetoothOff()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.createChannels()
        ContextCompat.registerReceiver(
            this,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        Settings.init(this)
        CookRecorder.init(this)

        // Sondide nimed registrist UI-le ja teavitusele.
        serviceScope.launch {
            deviceDao.observeProbes().collect { probes ->
                knownProbeAddresses.addAll(probes.map { it.address })
                ThermometerRepository.applyNames(probes.associate { it.address to it.name })
            }
        }

        // Püsiteavitus järgib olekut, mitte ainult mõõtmisi. Küpsetuse lõpp,
        // sihi muutus ja ühenduse katkemine ei too ühtegi uut mõõtmist kaasa,
        // aga teavituses peavad nad kõik näha olema.
        serviceScope.launch {
            ThermometerRepository.state.collect { notifications.requestOngoingUpdate() }
        }

        // Alarm vaikib alati, kui olek ütleb, et ta ei peaks enam helisema.
        // Nii ei pea iga koht, mis alarmi maha võtab ("Lõpeta", uus siht,
        // teavituse nupp), eraldi mäletama ka heli peatamist.
        serviceScope.launch {
            ThermometerRepository.state
                .map { it.alarmSounding }
                .distinctUntilChanged()
                .collect { sounding ->
                    if (!sounding) {
                        alarmPlayer.stop()
                        notifications.cancelAlarm()
                    }
                }
        }

        // Baaside register juhib ühendusi otse. Varem käivitas UI eemaldamise
        // järel eraldi käsu, aga kustutamine käis taustal ja teenus jõudis
        // registrit lugeda enne, kui rida kadus — baas jäi ühendusse, sondid
        // saatsid edasi andmeid ja skaneerimine ei leidnud teda enam üles,
        // sest ühenduses seade ei reklaami end.
        serviceScope.launch {
            deviceDao.observeBases().collect { bases ->
                val addresses = bases.map { it.address }
                val removed = knownBaseAddresses.filterNot { it in addresses }
                knownBaseAddresses = addresses
                removed.forEach { forgetBase(it) }
                reloadBasesAndConnect()
            }
        }

        // Demo sondil ei ole eraldi käivitusnuppu ega režiimi: simulaator käib
        // täpselt siis, kui sellel sondil on siht. Nii käitub ta täpselt nagu
        // päris sond, kes seisab toatemperatuuril, kuni liha ahju paned.
        serviceScope.launch {
            ThermometerRepository.state
                .map { it.probes[DEMO_PROBE_ADDRESS]?.isCooking == true }
                .distinctUntilChanged()
                .collect { cooking -> if (cooking) demo.start() else demo.stop() }
        }

        // Seansi elutsükkel käib sihi järgi, iga sondi kohta eraldi.
        serviceScope.launch {
            var previous = emptyMap<String, CookTarget?>()
            ThermometerRepository.state
                .map { state -> state.probes.mapValues { it.value.target } }
                .distinctUntilChanged()
                .collect { current ->
                    current.forEach { (address, target) ->
                        val before = previous[address]
                        when {
                            target != null && target != before -> {
                                CookRecorder.startOrUpdateSession(address, target)
                                // Hinda alarmi kohe, mitte alles järgmise mõõtmise
                                // peale — tingimus võib olla juba täidetud.
                                ThermometerRepository.state.value.probes[address]
                                    ?.let { checkAlarm(address, it.celsius) }
                            }
                            target == null && before != null ->
                                CookRecorder.endSession(address)
                        }
                    }
                    (previous.keys - current.keys).forEach { CookRecorder.endSession(it) }
                    previous = current
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        // Kõik ülejäänud tegevused eeldavad töötavat foreground service'i.
        if (!ensureForeground()) return START_NOT_STICKY

        when (intent?.action) {
            ACTION_SILENCE_ALARM -> ThermometerRepository.markAlarmSilenced()
            ACTION_TEST_ALARM -> {
                ThermometerRepository.setAlarmSounding(true)
                raiseAlarm(
                    title = "Alarmi test",
                    text = "Nii kõlab alarm, kui liha sihttemperatuurini jõuab.",
                    urgent = true,
                )
            }
            ACTION_SCAN_BASES -> ble.startScan()
            ACTION_STOP_SCAN -> ble.stopScan()
            ACTION_DEMO_BOOST -> demo.boost()
            ACTION_DEMO_RESET_BOOST -> demo.resetBoost()
            // Kõik kolm on kasutaja enda tegevused: äpi avamine, käsitsi nupp
            // või baasi lisamine. Kui ta ise midagi ette võtab, alustame
            // proovimist otsast, ka siis kui olime alla andnud.
            ACTION_RETRY_CONNECT, ACTION_START, ACTION_REFRESH_DEVICES -> {
                ble.resetGiveUp()
                reloadBasesAndConnect()
            }
            else -> reloadBasesAndConnect()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        alarmPlayer.stop()
        ble.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * @return kas teenus tohib edasi töötada.
     *
     * `connectedDevice` tüüpi foreground service'i käivitamine ilma Bluetoothi
     * loata viskab SecurityException'i ja tapab protsessi. Ilma loata käivitame
     * seega muud tüüpi teenuse: Bluetoothi pool jääb seisma, aga demo sond,
     * alarm ja ajalugu töötavad. Loa saabudes tõstame tüübi õigeks.
     */
    private fun ensureForeground(): Boolean {
        val wantedType = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> 0
            // Tüübita teenust ei luba juba Android 10, kui manifest tüübi
            // deklareerib. specialUse on olemas alles Android 14-st, seega
            // vahepealne vahemik saab dataSync'i — mõlemad on Bluetoothi
            // loast sõltumatud.
            !ble.hasPermissions() ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        if (running && foregroundType == wantedType) return true

        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                A5sNotifications.ID_ONGOING,
                notifications.buildOngoing(),
                wantedType,
            )
        }.isSuccess

        if (!started) {
            Log.e(TAG, "Foreground service't ei õnnestunud käivitada")
            ThermometerRepository.setError("Taustateenust ei õnnestunud käivitada")
            stopSelf()
            return false
        }

        foregroundType = wantedType
        if (!ble.hasPermissions()) {
            ThermometerRepository.setConnection(ConnectionState.STOPPED)
            ThermometerRepository.setError("Bluetoothi luba puudub")
        }

        running = true
        ble.start()
        return true
    }

    private fun reloadBasesAndConnect() {
        serviceScope.launch {
            val bases = deviceDao.bases().map { it.address }
            knownBaseAddresses = bases
            ble.connectToBases(bases)
        }
    }

    private fun shutdown() {
        running = false
        foregroundType = -1
        alarmPlayer.stop()
        CookRecorder.endAllSessions()
        ble.shutdown()
        ThermometerRepository.setConnection(ConnectionState.STOPPED)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Baas eemaldati registrist: ühendus kinni ja tema sondid nimekirjast ära.
     *
     * Registrikirjeid me ei kustuta — nii ei kao sondi nimi, kui kasutaja baasi
     * hiljem tagasi lisab. Nimekirjast peidab nad ProbeListScreen, sest sondi
     * ilma baasita ei ole kuskilt kuulata.
     */
    private suspend fun forgetBase(address: String) {
        ble.disconnect(address)
        val probes = deviceDao.probesOfBase(address).map { it.address }
        ThermometerRepository.forgetProbes(probes)
        Log.i(TAG, "Baas $address eemaldatud, ${probes.size} sondi peidetud")
    }

    // ------------------------------------------------ BleConnectionManager.Listener

    override fun onFrame(baseAddress: String, value: ByteArray) {
        val reading = A5sProtocol.parse(value) ?: return
        registerProbeIfNew(reading.address, baseAddress)
        processReading(reading)
    }

    /** Päris küpsetus — demo ei loe, see ei sõltu Bluetoothist. */
    override fun cookInProgress(): Boolean =
        ThermometerRepository.state.value.probes.values.any { it.isCooking && !it.isDemo }

    // -------------------------------------------------------------------- data

    /** Ühine töötlus päris ja demo mõõtmistele. */
    private fun processReading(reading: A5sProtocol.Reading) {
        ThermometerRepository.publishReading(reading)
        CookRecorder.record(reading.address, reading.celsius)
        checkAlarm(reading.address, reading.celsius)
        notifications.requestOngoingUpdate()
    }

    /** Uus sond ilmub nähtavale kohe, kui baas temast räägib. Nime saab hiljem muuta. */
    private fun registerProbeIfNew(probeAddress: String, baseAddress: String) {
        if (!knownProbeAddresses.add(probeAddress)) return
        serviceScope.launch {
            val index = deviceDao.probes().size + 1
            deviceDao.upsertProbe(
                KnownProbeEntity(
                    address = probeAddress,
                    name = "Sond $index",
                    baseAddress = baseAddress,
                    addedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun checkAlarm(address: String, celsius: Double) {
        val probe = ThermometerRepository.state.value.probes[address] ?: return
        val target = probe.target ?: return

        if (!probe.preWarnFired && celsius >= target.celsius - target.preWarnDelta) {
            ThermometerRepository.markPreWarnFired(address)
            raiseAlarm(
                title = "Varsti valmis — ${probe.displayName}",
                text = "${formatCelsius(celsius)} — siht ${target.celsius} °C " +
                    "(${target.meat}, ${target.doneness})",
                urgent = false,
            )
        }

        if (!probe.alarmFired && celsius >= target.celsius) {
            ThermometerRepository.markAlarmFired(address)
            raiseAlarm(
                title = "Valmis! ${formatCelsius(celsius)} — ${probe.displayName}",
                text = "${target.meat} — ${target.doneness} (${target.celsius} °C). " +
                    "Võta ahjust välja.",
                urgent = true,
            )
        }
    }

    /**
     * Heli käivitub sõltumata sellest, kas teavituste luba on olemas — just see
     * osa peab grilli juures kohale jõudma.
     */
    private fun raiseAlarm(title: String, text: String, urgent: Boolean) {
        if (urgent) alarmPlayer.start()
        notifications.showAlarm(title, text, urgent)
    }
}
