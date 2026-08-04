package ee.ukesk.a5s.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ee.ukesk.a5s.MainActivity
import ee.ukesk.a5s.R
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Hoiab BLE-ühendust kõigi teadaolevate A5S baasidega ka siis, kui ekraan on
 * kustunud, ja annab märku, kui liha jõuab sihttemperatuurini.
 *
 * Foreground service on siin kohustuslik, mitte mugavus: ilma selleta tapab
 * Android ühenduse mõne minutiga ja alarm jääb tulemata just siis, kui seda
 * kõige rohkem vaja on.
 */
class ThermometerService : android.app.Service() {

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

        private const val CHANNEL_ONGOING = "a5s_ongoing"
        private const val CHANNEL_ALARM = "a5s_alarm_v2"
        private const val NOTIFICATION_ID_ONGOING = 1
        private const val NOTIFICATION_ID_ALARM = 2

        private const val BASE_SCAN_TIMEOUT_MS = 30_000L
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val NOTIFICATION_THROTTLE_MS = 1_000L

        /**
         * Küpsetuse ajal tuleb mõõtmisi ~165 ms tagant, aga jõude olles saadab
         * sond harva ja ebaühtlaselt — kuni poole minuti pikkused vahed on
         * normaalsed. Piir peab jääma neist selgelt kõrgemale, muidu hakkaks
         * valvekoer tervet ühendust põhjuseta remontima.
         */
        private const val STALE_DATA_TIMEOUT_MS = 45_000L
        private const val WATCHDOG_INTERVAL_MS = 5_000L

        /** Kui kaua ootame lingi elumärgi (RSSI) vastust, enne kui katkiseks loeme. */
        private const val LIVENESS_REPLY_TIMEOUT_MS = 5_000L

        /** Nii värske elumärk kehtib; vanem tuleb uuesti küsida. */
        private const val LIVENESS_INTERVAL_MS = 20_000L

        /**
         * Kui kaua lugeda ühendumiskatset pooleliolevaks. Androidi enda
         * ajapiir on ~30 s, seega natuke rohkem.
         */
        private const val CONNECT_ATTEMPT_TIMEOUT_MS = 40_000L

        /**
         * Küpsetuse ajal peab mõõtmisi tulema. Kui neid nii kaua ei ole, on
         * midagi valesti ka siis, kui link ise elumärki annab — siis on parem
         * ühendus katki teha ja uuesti üles ehitada.
         */
        private const val COOKING_SILENCE_TIMEOUT_MS = 90_000L

        /**
         * Kui kaua tulutult proovida, enne kui alla anda ja nuppu pakkuda.
         * Kehtib ainult jõudeoleku kohta: käimasoleva küpsetuse ajal proovime
         * lõputult, sest siis on alarm ainus asi, mis liha põlemast päästab.
         */
        private const val CONNECT_GIVE_UP_MS = 120_000L

        /** Demo simulatsiooni parameetrid: ~57 °C kahe minutiga. */
        private const val OVEN_C = 95.0
        private const val DEMO_TAU_S = 180.0

        fun start(context: Context) = send(context, ACTION_START)
        fun stop(context: Context) = send(context, ACTION_STOP)
        fun silenceAlarm(context: Context) = send(context, ACTION_SILENCE_ALARM)
        fun testAlarm(context: Context) = send(context, ACTION_TEST_ALARM)
        fun scanForBases(context: Context) = send(context, ACTION_SCAN_BASES)
        fun stopScan(context: Context) = send(context, ACTION_STOP_SCAN)
        fun refreshDevices(context: Context) = send(context, ACTION_REFRESH_DEVICES)
        fun retryConnect(context: Context) = send(context, ACTION_RETRY_CONNECT)
        fun demoBoost(context: Context) = send(context, ACTION_DEMO_BOOST)

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

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    private val alarmPlayer by lazy { AlarmPlayer(this) }
    private val deviceDao by lazy { AppDatabase.get(this).deviceDao() }

    private val gatts = ConcurrentHashMap<String, BluetoothGatt>()

    /**
     * Pooleliolevad ühendumiskatsed, aadress → algusaeg.
     *
     * Iga connectGatt annab Androidilt uue GATT-kliendi. Meie mäletame ainult
     * viimast — kui kaks katset satuvad korraga käima, jääb esimene klient
     * rippuma ja hoiab baasi ühenduses. Ühenduses seade ei reklaami end, seega
     * otsing ei leia teda üles ja tema andmed käivad vale kliendi kaudu.
     */
    private val connecting = ConcurrentHashMap<String, Long>()
    private val knownProbeAddresses = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var knownBaseAddresses: List<String> = emptyList()

    private var scanningForBases = false
    private var scanStartedAt = 0L
    private var running = false

    /** Praegune foreground service'i tüüp; 0 = Bluetoothi luba puudub. */
    private var foregroundType = -1
    private var lastNotificationUpdate = 0L

    @Volatile
    private var lastFrameAt = 0L

    /**
     * Millal ühendus viimati katkes. Nullitakse iga saabunud paketiga, seega
     * mõõdab see katkematut ebaõnnestumise seeriat, mitte üksikuid tõrkeid.
     */
    @Volatile
    private var connectionLostSince = 0L

    /** Taasühendamine on alla antud — ootame kasutaja nuppu. */
    @Volatile
    private var connectionGivenUp = false

    /** Millal küsisime lingilt elumärki; 0 = ei oota vastust. */
    @Volatile
    private var livenessAskedAt = 0L

    /** Millal link viimati elumärgile vastas. */
    @Volatile
    private var lastLivenessOkAt = 0L

    /**
     * Ühendus võib jääda püsti ka siis, kui mõõtmisi enam ei tule — nii juhtus
     * lekkinud GATT-klientidega, kus alarm oleks jäänud tulemata.
     *
     * Vaikus üksi ei tõesta aga midagi: kui ükski sond ei ole aktiivne (näiteks
     * laeb pesas), ei ole baasil lihtsalt midagi öelda. Seetõttu küsime enne
     * taasühendamist lingilt signaalitugevust — vastus tõestab, et ühendus
     * elab. Ilma selleta jäi äpp tsüklisse "Ühendatud → andmevoog katkes →
     * ühendatud", kuigi baasiga oli kõik korras.
     */
    private val staleDataWatchdog = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            val now = System.currentTimeMillis()
            val silentFor = now - lastFrameAt
            val watching = running && !connectionGivenUp && lastFrameAt > 0L &&
                gatts.isNotEmpty() && silentFor > STALE_DATA_TIMEOUT_MS

            when {
                !watching -> livenessAskedAt = 0L

                // Küpsetuse ajal peab andmeid tulema. Kui neid pikalt ei ole,
                // ei aita elumärk — teeme ühenduse uueks.
                cookInProgress() && silentFor > COOKING_SILENCE_TIMEOUT_MS ->
                    forceReconnect(silentFor)

                // Küsimus jäi vastuseta: link on päriselt katki.
                livenessAskedAt != 0L && now - livenessAskedAt > LIVENESS_REPLY_TIMEOUT_MS ->
                    forceReconnect(silentFor)

                livenessAskedAt == 0L && now - lastLivenessOkAt > LIVENESS_INTERVAL_MS -> {
                    livenessAskedAt = now
                    gatts.values.forEach { gatt ->
                        runCatching { gatt.readRemoteRssi() }
                        // Kui link elab, aga andmeid ei tule, võib tellimus olla
                        // vaikselt kadunud. Uuesti tellimine on kahjutu: magava
                        // sondi puhul ei muutu sellest midagi.
                        runCatching { enableNotifications(gatt) }
                    }
                }
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private fun forceReconnect(silentFor: Long) {
        Log.w(TAG, "Andmeid pole $silentFor ms ja link ei vasta — taasühendan")
        ThermometerRepository.setError("Andmevoog katkes, taasühendan…")
        livenessAskedAt = 0L
        lastFrameAt = System.currentTimeMillis()
        reconnectAll()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        Settings.init(this)
        CookRecorder.init(this)

        // Sondide nimed registrist UI-le ja teavitusele.
        serviceScope.launch {
            deviceDao.observeProbes().collect { probes ->
                knownProbeAddresses.addAll(probes.map { it.address })
                ThermometerRepository.applyNames(probes.associate { it.address to it.name })
            }
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
                        notificationManager.cancel(NOTIFICATION_ID_ALARM)
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
                .collect { cooking -> if (cooking) startDemoSim() else stopDemoSim() }
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
            ACTION_SILENCE_ALARM -> {
                silenceAlarmNow()
            }
            ACTION_TEST_ALARM -> {
                ThermometerRepository.setAlarmSounding(true)
                notifyAlarm(
                    title = "Alarmi test",
                    text = "Nii kõlab alarm, kui liha sihttemperatuurini jõuab.",
                    urgent = true,
                )
            }
            ACTION_SCAN_BASES -> startBaseScan()
            ACTION_STOP_SCAN -> stopBaseScan()
            ACTION_DEMO_BOOST -> demoBoost()
            // Kõik kolm on kasutaja enda tegevused: äpi avamine, käsitsi nupp
            // või baasi lisamine. Kui ta ise midagi ette võtab, alustame
            // proovimist otsast, ka siis kui olime alla andnud.
            ACTION_RETRY_CONNECT, ACTION_START, ACTION_REFRESH_DEVICES -> {
                connectionLostSince = 0L
                connectionGivenUp = false
                reloadBasesAndConnect()
            }
            else -> reloadBasesAndConnect()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alarmPlayer.stop()
        teardownBle()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * @return kas teenus tohib edasi töötada.
     *
     * `connectedDevice` tüüpi foreground service'i käivitamine ilma Bluetoothi
     * loata viskab SecurityException'i ja tapab protsessi. Ilma loata käivitame
     * seega tüübita teenuse: Bluetoothi pool jääb seisma, aga demo sond, alarm
     * ja ajalugu töötavad. Loa saabudes tõstame tüübi õigeks.
     */
    private fun ensureForeground(): Boolean {
        val wantedType = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> 0
            // Tüübita teenust Android 14+ enam ei luba, kui manifest tüübi
            // deklareerib — seega specialUse, mis ühtegi luba ei nõua.
            !hasBlePermissions() ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        if (running && foregroundType == wantedType) return true

        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID_ONGOING,
                buildOngoingNotification(),
                wantedType,
            )
        }.isSuccess

        if (started) {
            foregroundType = wantedType
            if (!hasBlePermissions()) {
                ThermometerRepository.setConnection(ConnectionState.STOPPED)
                ThermometerRepository.setError("Bluetoothi luba puudub")
            }
        }

        if (!started) {
            Log.e(TAG, "Foreground service't ei õnnestunud käivitada")
            ThermometerRepository.setError("Taustateenust ei õnnestunud käivitada")
            stopSelf()
            return false
        }

        running = true
        handler.removeCallbacks(staleDataWatchdog)
        handler.postDelayed(staleDataWatchdog, WATCHDOG_INTERVAL_MS)
        return true
    }

    private fun reloadBasesAndConnect() {
        if (!hasBlePermissions()) {
            ThermometerRepository.setError("Bluetoothi load puuduvad")
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            ThermometerRepository.setConnection(ConnectionState.BLUETOOTH_OFF)
            ThermometerRepository.setError("Bluetooth on välja lülitatud")
            return
        }

        serviceScope.launch {
            val bases = deviceDao.bases().map { it.address }
            knownBaseAddresses = bases

            // Lahtiütlemine käib enne ühendamist ja alati. Varem oli siin tühja
            // nimekirja korral kiire väljapääs — viimase baasi eemaldamisel jäi
            // ühendus seetõttu vaikselt alles. Ühenduses seade aga ei reklaami
            // end, nii et otsing ei leidnud teda enam kunagi üles ja aitas
            // ainult protsessi tapmine.
            gatts.keys.filterNot { it in bases }.forEach { disconnectFrom(it) }

            if (bases.isEmpty()) {
                ThermometerRepository.setConnection(ConnectionState.STOPPED)
                return@launch
            }
            bases.forEach { address ->
                if (!gatts.containsKey(address)) connectTo(address)
            }
        }
    }

    private fun shutdown() {
        running = false
        foregroundType = -1
        alarmPlayer.stop()
        CookRecorder.endAllSessions()
        teardownBle()
        ThermometerRepository.setConnection(ConnectionState.STOPPED)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("MissingPermission")
    private fun teardownBle() {
        handler.removeCallbacksAndMessages(null)
        stopBaseScan()
        gatts.values.forEach { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        gatts.clear()
    }

    // --------------------------------------------------------------- baasiotsing

    @SuppressLint("MissingPermission")
    private fun startBaseScan() {
        // "Otsing käib" on lipp, mille maha võtab käsuga postitatud tagasikutse.
        // Kui see tagasikutse kaob — näiteks koos teiste käskudega ühenduse
        // koristamisel — jääb lipp igaveseks püsti ja iga järgmine otsing
        // katkeb kohe alguses. Väljastpoolt paistab see nii, nagu baasi enam ei
        // olekski, ja aitab ainult protsessi tapmine. Seepärast ei usu me lippu
        // pimesi, vaid kontrollime kella.
        if (scanningForBases) {
            val runningFor = System.currentTimeMillis() - scanStartedAt
            if (runningFor < BASE_SCAN_TIMEOUT_MS) return
            Log.w(TAG, "Otsingu lipp jäi $runningFor ms püsti — võtan maha")
            stopBaseScan()
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return

        scanningForBases = true
        scanStartedAt = System.currentTimeMillis()
        ThermometerRepository.setScanningForBases(true)
        Log.i(TAG, "Alustan baasiotsingut")

        // Teadlikult ilma ScanFilter'ita: A5 ei advertise oma teenuse UUID-d ja
        // nimefilter jätab osal seadmetel scan response'i vahele.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, baseScanCallback)
        handler.postDelayed({ stopBaseScan() }, BASE_SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopBaseScan() {
        if (!scanningForBases) return
        scanningForBases = false
        runCatching { bluetoothAdapter?.bluetoothLeScanner?.stopScan(baseScanCallback) }
        ThermometerRepository.setScanningForBases(false)
    }

    private val baseScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device?.name ?: result.scanRecord?.deviceName ?: return
            if (name != A5sProtocol.ADVERTISED_NAME) return
            ThermometerRepository.addDiscoveredBase(
                DiscoveredBase(
                    address = result.device.address,
                    advertisedName = name,
                    rssi = result.rssi,
                ),
            )
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Otsing ebaõnnestus, kood $errorCode")
            scanningForBases = false
            ThermometerRepository.setScanningForBases(false)
            ThermometerRepository.setError(
                if (errorCode == ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY) {
                    "Android piiras otsingut — oota pool minutit ja proovi uuesti"
                } else {
                    "Otsing ebaõnnestus (kood $errorCode)"
                },
            )
        }
    }

    // -------------------------------------------------------------------- gatt

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun connectTo(address: String) {
        // Ühendumiskäske tuleb mitmest suunast korraga: registri jälgija,
        // kasutaja käsk, taasühendamine ja valvekoer. Ilma selle väravata
        // jõuavad kaks neist samal ajal kohale ja Android teeb kaks klienti.
        val attemptStartedAt = connecting[address]
        if (attemptStartedAt != null &&
            System.currentTimeMillis() - attemptStartedAt < CONNECT_ATTEMPT_TIMEOUT_MS
        ) {
            Log.i(TAG, "Ühendumine baasiga $address juba käib — teist ei alusta")
            return
        }

        val adapter = bluetoothAdapter ?: return
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return

        // Vana klient tuleb alati sulgeda, muidu jäävad GATT-ühendused lekkima.
        // Androidil on neid piiratud arv ja täis registriga lakkab asi töötamast.
        gatts.remove(address)?.let { runCatching { it.close() } }
        connecting[address] = System.currentTimeMillis()

        ThermometerRepository.setConnection(ConnectionState.CONNECTING)
        ThermometerRepository.setError(null)

        // API 37 tõi asemele connectGatt(BluetoothGattConnectionSettings, Executor,
        // BluetoothGattCallback), aga see on API 37+ ja veel funktsioonilipu taga.
        // Meie minSdk on 26, seega jääb vana meetod — see töötab kõigil versioonidel.
        //
        // autoConnect = false on siin oluline. autoConnect = true paneb stacki
        // aeglasse taustarežiimi, kus mõõtmised tulevad harva või lakkavad hoopis —
        // ja kuna alarmi kontrollitakse iga mõõtmise peale, jääks alarm tulemata.
        // Taasühendumise eest hoolitseb scheduleReconnect, mitte stack.
        val gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (gatt != null) gatts[address] = gatt else connecting.remove(address)
    }

    // ------------------------------------------------------------------- demo

    private var demoJob: kotlinx.coroutines.Job? = null

    @Volatile
    private var demoBoostCelsius = 0.0

    /**
     * Demo asendab ainult andmeallika. Kõik ülejäänu — alarm, salvestamine,
     * teavitus — käib täpselt sama koodi kaudu mis päris anduri puhul, muidu
     * ei testiks demo midagi.
     */
    private fun startDemoSim() {
        if (demoJob != null) return

        val startedAt = System.currentTimeMillis()
        demoJob = serviceScope.launch {
            while (isActive) {
                val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
                // Newtoni jahtumisseadus tagurpidi: kiire tõus alguses, siis
                // aeglustub — nagu päris lihal ahjus.
                val natural = OVEN_C - (OVEN_C - DEMO_AMBIENT_C) * exp(-seconds / DEMO_TAU_S)
                emitDemoReading(natural + demoBoostCelsius)
                delay(1000)
            }
        }
    }

    /** Siht maha võetud — sond läheb tagasi toatemperatuurile ja jääb sinna. */
    private fun stopDemoSim() {
        demoJob?.cancel()
        demoJob = null
        demoBoostCelsius = 0.0
        emitDemoReading(DEMO_AMBIENT_C)
    }

    /**
     * "+10 °C" kiirendab ootamist. Töötab ka enne küpsetuse algust, sest just
     * nii saab katsetada hoiatust "siht on juba käes".
     */
    private fun demoBoost() {
        demoBoostCelsius += 10.0
        if (demoJob == null) emitDemoReading(DEMO_AMBIENT_C + demoBoostCelsius)
    }

    /** Päris andur annab täiskraade, demo teeb sama. */
    private fun emitDemoReading(celsius: Double) {
        processReading(
            A5sProtocol.Reading(
                address = DEMO_PROBE_ADDRESS,
                celsius = min(150.0, celsius).roundToInt().toDouble(),
                batteryRaw = 100,
            ),
        )
    }

    /** Kõik ühendused kinni ja uuesti lahti — kasutab valvekoer. */
    @SuppressLint("MissingPermission")
    private fun reconnectAll() {
        gatts.values.forEach { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        gatts.clear()
        connecting.clear()
        ThermometerRepository.state.value.connectedBases.forEach {
            ThermometerRepository.setBaseConnected(it, false)
        }
        handler.postDelayed({ if (running) reloadBasesAndConnect() }, RECONNECT_DELAY_MS)
    }

    /**
     * Tellib mõõtmiste teavitused. Eraldi funktsioon, sest seda on vaja nii
     * ühendumisel kui hiljem: kui link elab, aga andmeid ei tule, võib tellimus
     * olla vaikselt kadunud ja uuesti tellimine on odavam kui ühenduse
     * lammutamine.
     *
     * @return kas tellimine üldse õnnestus.
     */
    @SuppressLint("MissingPermission")
    private fun enableNotifications(g: BluetoothGatt): Boolean {
        val characteristic = g.getService(A5sProtocol.SERVICE_UUID)
            ?.getCharacteristic(A5sProtocol.DATA_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            ThermometerRepository.setError("Seadmel ${g.device.address} puudub oodatud teenus")
            return false
        }

        g.setCharacteristicNotification(characteristic, true)

        val cccd = characteristic.getDescriptor(A5sProtocol.CCCD_UUID) ?: run {
            ThermometerRepository.setError("CCCD deskriptorit ei leitud")
            return false
        }

        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, enable)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = enable
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
        return true
    }

    /**
     * Baas eemaldati registrist: ühendus kinni ja tema sondid nimekirjast ära.
     *
     * Registrikirjeid me ei kustuta — nii ei kao sondi nimi, kui kasutaja baasi
     * hiljem tagasi lisab. Nimekirjast peidab nad ProbeListScreen, sest sondi
     * ilma baasita ei ole kuskilt kuulata.
     */
    private suspend fun forgetBase(address: String) {
        disconnectFrom(address)
        val probes = deviceDao.probesOfBase(address).map { it.address }
        ThermometerRepository.forgetProbes(probes)
        Log.i(TAG, "Baas $address eemaldatud, ${probes.size} sondi peidetud")
    }

    @SuppressLint("MissingPermission")
    private fun disconnectFrom(address: String) {
        connecting.remove(address)
        gatts.remove(address)?.let {
            Log.i(TAG, "Katkestan ühenduse baasiga $address")
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        ThermometerRepository.setBaseConnected(address, false)
    }

    @SuppressLint("MissingPermission")
    private fun scheduleReconnect(address: String) {
        connecting.remove(address)
        if (!running || connectionGivenUp) return
        gatts.remove(address)?.let { runCatching { it.close() } }
        ThermometerRepository.setBaseConnected(address, false)
        if (ThermometerRepository.state.value.connectedBases.isEmpty()) {
            ThermometerRepository.setConnection(ConnectionState.RECONNECTING)
        }
        if (address !in knownBaseAddresses) return

        val now = System.currentTimeMillis()
        if (connectionLostSince == 0L) connectionLostSince = now
        val failingFor = now - connectionLostSince

        // Käimasoleva küpsetuse ajal ei anna kunagi alla: alarm on siis ainus,
        // mis liha söödavana hoiab. Jõude olles ei ole mõtet akut tühjaks
        // proovida — kasutaja saab nupust uuesti alustada.
        if (failingFor > CONNECT_GIVE_UP_MS && !cookInProgress()) {
            giveUpConnecting()
            return
        }

        // Mida kauem ei õnnestu, seda harvem proovime.
        val delayMs = when {
            failingFor < 30_000L -> RECONNECT_DELAY_MS
            failingFor < 60_000L -> 5_000L
            else -> 15_000L
        }
        handler.postDelayed(
            { if (running && !connectionGivenUp) connectTo(address) },
            delayMs,
        )
    }

    /** Päris küpsetus — demo ei loe, see ei sõltu Bluetoothist. */
    private fun cookInProgress(): Boolean =
        ThermometerRepository.state.value.probes.values.any { it.isCooking && !it.isDemo }

    @SuppressLint("MissingPermission")
    private fun giveUpConnecting() {
        Log.w(TAG, "Ühendust ei saanud ${CONNECT_GIVE_UP_MS} ms jooksul — lõpetan proovimise")
        connectionGivenUp = true
        gatts.values.forEach {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatts.clear()
        connecting.clear()
        ThermometerRepository.state.value.connectedBases.forEach {
            ThermometerRepository.setBaseConnected(it, false)
        }
        ThermometerRepository.setConnection(ConnectionState.GAVE_UP)
        ThermometerRepository.setError(null)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val address = g.device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connecting.remove(address)
                    Log.i(TAG, "Ühendatud baasiga $address, otsin teenuseid")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "Ühendus baasiga $address katkes (status $status)")
                    scheduleReconnect(address)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val address = g.device.address
            if (status != BluetoothGatt.GATT_SUCCESS) {
                scheduleReconnect(address)
                return
            }

            if (!enableNotifications(g)) {
                scheduleReconnect(address)
                return
            }

            ThermometerRepository.setBaseConnected(address, true)
            ThermometerRepository.setConnection(ConnectionState.CONNECTED)
            ThermometerRepository.setError(null)
        }

        /**
         * Vastus tähendab, et link elab — ka siis, kui mõõtmisi ei tule. Nihutame
         * vaikuse arvestuse ette, et valvekoer ei hakkaks põhjuseta ühendust
         * lammutama.
         */
        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            // NB: lastFrameAt jääb puutumata. Elumärk tõestab linki, mitte
            // andmevoogu — kui me seda siin nihutaksime, ei saaks küpsetuse
            // ajal kunagi teada, et mõõtmised on päriselt lakanud.
            livenessAskedAt = 0L
            lastLivenessOkAt = System.currentTimeMillis()
            connectionLostSince = 0L
            ThermometerRepository.setError(null)
        }

        // Android 13+
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleFrame(g.device.address, characteristic, value)
        }

        // Kuni Android 12
        @Deprecated("Asendatud kolme argumendiga variandiga API 33-s")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            handleFrame(g.device.address, characteristic, characteristic.value ?: return)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid != A5sProtocol.CCCD_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Teavitused lubatud (${g.device.address})")
            } else {
                Log.e(TAG, "CCCD kirjutamine ebaõnnestus, status=$status")
                ThermometerRepository.setError("Andmekanali avamine ebaõnnestus ($status)")
            }
        }
    }

    // -------------------------------------------------------------------- data

    private fun handleFrame(
        baseAddress: String,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (characteristic.uuid != A5sProtocol.DATA_CHARACTERISTIC_UUID) return

        // Ükskõik milline pakett tõestab, et ühendus elab — ka baasi enda kirje,
        // mida me edasi ei kasuta. Ilma selleta loeks valvekoer magava sondi
        // katkiseks ühenduseks ja taasühendaks lõputult.
        lastFrameAt = System.currentTimeMillis()
        connectionLostSince = 0L

        val reading = A5sProtocol.parse(value) ?: return
        registerProbeIfNew(reading.address, baseAddress)
        processReading(reading)
    }

    /** Ühine töötlus päris ja demo mõõtmistele. */
    private fun processReading(reading: A5sProtocol.Reading) {
        // NB: lastFrameAt't siin ei puutu. Demo mõõtmised ei tohi valvekoerale
        // valetada, et BLE-ühendus elab — muidu ei märkaks ta surnud andmevoogu
        // ajal, mil demo sond parasjagu jookseb.
        ThermometerRepository.publishReading(reading)
        CookRecorder.record(reading.address, reading.celsius)
        checkAlarm(reading.address, reading.celsius)
        maybeUpdateOngoingNotification()
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
            notifyAlarm(
                title = "Varsti valmis — ${probe.displayName}",
                text = "${format(celsius)} — siht ${target.celsius} °C " +
                    "(${target.meat}, ${target.doneness})",
                urgent = false,
            )
        }

        if (!probe.alarmFired && celsius >= target.celsius) {
            ThermometerRepository.markAlarmFired(address)
            notifyAlarm(
                title = "Valmis! ${format(celsius)} — ${probe.displayName}",
                text = "${target.meat} — ${target.doneness} (${target.celsius} °C). Võta ahjust välja.",
                urgent = true,
            )
        }
    }

    private fun format(celsius: Double): String = "${(celsius * 10).roundToInt() / 10.0} °C"

    // ----------------------------------------------------------- notifications

    private fun createChannels() {
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

        notificationManager.createNotificationChannels(listOf(ongoing, alarm))
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun silenceIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, ThermometerService::class.java).setAction(ACTION_SILENCE_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildOngoingNotification(): Notification {
        val state = ThermometerRepository.state.value
        val probes = state.probeList

        val title = when {
            probes.isNotEmpty() -> probes.joinToString("  ·  ") {
                "${it.displayName} ${format(it.celsius)}"
            }
            state.connection == ConnectionState.SCANNING -> "Otsin termomeetrit…"
            state.connection == ConnectionState.CONNECTING -> "Ühendan…"
            state.connection == ConnectionState.RECONNECTING -> "Ühendus katkes, proovin uuesti…"
            else -> "A5S Termomeeter"
        }

        val cooking = probes.mapNotNull { probe ->
            probe.target?.let { "${it.meat}, ${it.doneness} → ${it.celsius} °C" }
        }
        val text = if (cooking.isEmpty()) "Küpsetust ei käi" else cooking.joinToString(" · ")

        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_thermometer)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun maybeUpdateOngoingNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdate < NOTIFICATION_THROTTLE_MS) return
        lastNotificationUpdate = now
        if (!canPostNotifications()) return
        notificationManager.notify(NOTIFICATION_ID_ONGOING, buildOngoingNotification())
    }

    /** Heli peatamise ja teavituse kustutamise teeb ülalpool olev jälgija. */
    private fun silenceAlarmNow() {
        ThermometerRepository.markAlarmSilenced()
    }

    private fun notifyAlarm(title: String, text: String, urgent: Boolean) {
        // Heli käivitub sõltumata sellest, kas teavituste luba on olemas —
        // just see osa peab grilli juures kohale jõudma.
        if (urgent) alarmPlayer.start()

        if (!canPostNotifications()) return

        val notification = NotificationCompat.Builder(this, CHANNEL_ALARM)
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

        notificationManager.notify(
            if (urgent) NOTIFICATION_ID_ALARM else NOTIFICATION_ID_ALARM + 1,
            notification,
        )
    }

    // ------------------------------------------------------------- permissions

    private fun hasBlePermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_SCAN) &&
                granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            granted(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(Manifest.permission.POST_NOTIFICATIONS)

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
