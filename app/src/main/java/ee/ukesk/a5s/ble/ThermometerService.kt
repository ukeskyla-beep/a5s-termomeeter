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
        const val ACTION_START_DEMO = "ee.ukesk.a5s.START_DEMO"
        const val ACTION_STOP_DEMO = "ee.ukesk.a5s.STOP_DEMO"
        const val ACTION_DEMO_BOOST = "ee.ukesk.a5s.DEMO_BOOST"

        private const val CHANNEL_ONGOING = "a5s_ongoing"
        private const val CHANNEL_ALARM = "a5s_alarm_v2"
        private const val NOTIFICATION_ID_ONGOING = 1
        private const val NOTIFICATION_ID_ALARM = 2

        private const val BASE_SCAN_TIMEOUT_MS = 30_000L
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val NOTIFICATION_THROTTLE_MS = 1_000L

        /**
         * Mõõtmisi tuleb ~165 ms tagant. Kui neid pole nii kaua tulnud, on
         * andmevoog surnud, isegi kui Bluetooth väidab, et ühendus on olemas —
         * ja siis ei tuleks ka alarmi.
         */
        private const val STALE_DATA_TIMEOUT_MS = 20_000L
        private const val WATCHDOG_INTERVAL_MS = 5_000L

        /** Demo simulatsiooni parameetrid: ~57 °C kahe minutiga. */
        private const val AMBIENT_C = 20.0
        private const val OVEN_C = 95.0
        private const val DEMO_TAU_S = 180.0

        fun start(context: Context) = send(context, ACTION_START)
        fun stop(context: Context) = send(context, ACTION_STOP)
        fun silenceAlarm(context: Context) = send(context, ACTION_SILENCE_ALARM)
        fun testAlarm(context: Context) = send(context, ACTION_TEST_ALARM)
        fun scanForBases(context: Context) = send(context, ACTION_SCAN_BASES)
        fun stopScan(context: Context) = send(context, ACTION_STOP_SCAN)
        fun refreshDevices(context: Context) = send(context, ACTION_REFRESH_DEVICES)
        fun startDemo(context: Context) = send(context, ACTION_START_DEMO)
        fun stopDemo(context: Context) = send(context, ACTION_STOP_DEMO)
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
    private val knownProbeAddresses = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var knownBaseAddresses: List<String> = emptyList()

    private var scanningForBases = false
    private var running = false
    private var lastNotificationUpdate = 0L

    @Volatile
    private var lastFrameAt = 0L

    /**
     * Ühendus võib jääda püsti ka siis, kui mõõtmisi enam ei tule. Ilma selle
     * valveta jääks äpp näitama vana numbrit ja alarm ei käivituks kunagi.
     */
    private val staleDataWatchdog = object : Runnable {
        override fun run() {
            val silentFor = System.currentTimeMillis() - lastFrameAt
            if (running && lastFrameAt > 0L && silentFor > STALE_DATA_TIMEOUT_MS) {
                Log.w(TAG, "Mõõtmisi pole ${silentFor} ms — taasühendan")
                ThermometerRepository.setError("Andmevoog katkes, taasühendan…")
                lastFrameAt = System.currentTimeMillis()
                reconnectAll()
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
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
            ACTION_START_DEMO -> startDemo()
            ACTION_STOP_DEMO -> stopDemo()
            ACTION_DEMO_BOOST -> demoBoostCelsius += 10.0
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
     * loata viskab SecurityException'i ja tapab protsessi. Värskel paigaldusel
     * ei ole luba veel antud, seega tuleb siit vaikselt taganeda — mitte
     * kokku kukkuda.
     */
    private fun ensureForeground(): Boolean {
        if (running) return true

        if (!hasBlePermissions()) {
            ThermometerRepository.setConnection(ConnectionState.STOPPED)
            ThermometerRepository.setError("Bluetoothi luba puudub")
            stopSelf()
            return false
        }

        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID_ONGOING,
                buildOngoingNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                },
            )
        }.isSuccess

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
            if (bases.isEmpty()) {
                ThermometerRepository.setConnection(ConnectionState.STOPPED)
                return@launch
            }
            bases.forEach { address ->
                if (!gatts.containsKey(address)) connectTo(address)
            }
            // Baasid, mis kasutaja vahepeal eemaldas.
            gatts.keys.filterNot { it in bases }.forEach { disconnectFrom(it) }
        }
    }

    private fun shutdown() {
        running = false
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
        if (scanningForBases) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return

        scanningForBases = true
        ThermometerRepository.setScanningForBases(true)

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
            scanningForBases = false
            ThermometerRepository.setScanningForBases(false)
            ThermometerRepository.setError("Skaneerimine ebaõnnestus (kood $errorCode)")
        }
    }

    // -------------------------------------------------------------------- gatt

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun connectTo(address: String) {
        val adapter = bluetoothAdapter ?: return
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return

        // Vana klient tuleb alati sulgeda, muidu jäävad GATT-ühendused lekkima.
        // Androidil on neid piiratud arv ja täis registriga lakkab asi töötamast.
        gatts.remove(address)?.let { runCatching { it.close() } }

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
        if (gatt != null) gatts[address] = gatt
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
    private fun startDemo() {
        if (demoJob != null) return

        teardownBle()
        demoBoostCelsius = 0.0
        ThermometerRepository.setDemoMode(true)
        ThermometerRepository.setConnection(ConnectionState.CONNECTED)

        val startedAt = System.currentTimeMillis()
        demoJob = serviceScope.launch {
            while (isActive) {
                val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
                // Newtoni jahtumisseadus tagurpidi: kiire tõus alguses, siis
                // aeglustub — nagu päris lihal ahjus.
                val natural = OVEN_C - (OVEN_C - AMBIENT_C) * exp(-seconds / DEMO_TAU_S)
                val celsius = min(150.0, natural + demoBoostCelsius)
                // Päris andur annab täiskraade, demo teeb sama.
                processReading(
                    A5sProtocol.Reading(
                        address = DEMO_PROBE_ADDRESS,
                        celsius = celsius.roundToInt().toDouble(),
                        batteryRaw = 85,
                    ),
                )
                delay(1000)
            }
        }
        handler.removeCallbacks(staleDataWatchdog)
        handler.postDelayed(staleDataWatchdog, WATCHDOG_INTERVAL_MS)
    }

    private fun stopDemo() {
        demoJob?.cancel()
        demoJob = null
        CookRecorder.endSession(DEMO_PROBE_ADDRESS)
        ThermometerRepository.setDemoMode(false)
        reloadBasesAndConnect()
    }

    /** Kõik ühendused kinni ja uuesti lahti — kasutab valvekoer. */
    @SuppressLint("MissingPermission")
    private fun reconnectAll() {
        gatts.values.forEach { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        gatts.clear()
        ThermometerRepository.state.value.connectedBases.forEach {
            ThermometerRepository.setBaseConnected(it, false)
        }
        handler.postDelayed({ if (running) reloadBasesAndConnect() }, RECONNECT_DELAY_MS)
    }

    @SuppressLint("MissingPermission")
    private fun disconnectFrom(address: String) {
        gatts.remove(address)?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        ThermometerRepository.setBaseConnected(address, false)
    }

    @SuppressLint("MissingPermission")
    private fun scheduleReconnect(address: String) {
        if (!running) return
        gatts.remove(address)?.let { runCatching { it.close() } }
        ThermometerRepository.setBaseConnected(address, false)
        if (ThermometerRepository.state.value.connectedBases.isEmpty()) {
            ThermometerRepository.setConnection(ConnectionState.RECONNECTING)
        }
        if (address !in knownBaseAddresses) return
        handler.postDelayed({ if (running) connectTo(address) }, RECONNECT_DELAY_MS)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val address = g.device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
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

            val characteristic = g.getService(A5sProtocol.SERVICE_UUID)
                ?.getCharacteristic(A5sProtocol.DATA_CHARACTERISTIC_UUID)
            if (characteristic == null) {
                ThermometerRepository.setError("Seadmel $address puudub oodatud teenus")
                scheduleReconnect(address)
                return
            }

            g.setCharacteristicNotification(characteristic, true)

            val cccd = characteristic.getDescriptor(A5sProtocol.CCCD_UUID)
            if (cccd == null) {
                ThermometerRepository.setError("CCCD deskriptorit ei leitud")
                return
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

            ThermometerRepository.setBaseConnected(address, true)
            ThermometerRepository.setConnection(ConnectionState.CONNECTED)
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

        val reading = A5sProtocol.parse(value) ?: return
        registerProbeIfNew(reading.address, baseAddress)
        processReading(reading)
    }

    /** Ühine töötlus päris ja demo mõõtmistele. */
    private fun processReading(reading: A5sProtocol.Reading) {
        lastFrameAt = System.currentTimeMillis()
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
