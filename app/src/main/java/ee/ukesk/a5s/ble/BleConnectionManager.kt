package ee.ukesk.a5s.ble

import android.Manifest
import android.annotation.SuppressLint
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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Kas BLE-lubade komplekt on käes.
 *
 * Vajab ka kasutajaliides: esmakäivitusel jookseks otsing muidu tühja seni,
 * kuni kasutaja loadialoogidele vastab, ja lõpetaks eksitava teatega "baasi ei
 * leitud", kuigi otsingut ei toimunudki.
 */
internal fun blePermissionsGranted(context: Context): Boolean {
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        granted(Manifest.permission.BLUETOOTH_SCAN) &&
            granted(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        granted(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

/**
 * Kogu BLE-pool ühes kohas: ühendused, otsing, taasühendamine ja valvekoer.
 *
 * Teenus ei tea siit midagi peale selle, et kaadrid tulevad [Listener.onFrame]
 * kaudu. Vastu annab ta baaside nimekirja ja vastuse küsimusele, kas küpsetus
 * käib — sellest sõltub, kas ühendust tohib üldse käest lasta.
 */
class BleConnectionManager(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        /** Toores kaader baasilt. Parsimine ja jagamine käib teenuses. */
        fun onFrame(baseAddress: String, value: ByteArray)

        /**
         * Kas mõni päris sond parasjagu küpseb. Küpsetuse ajal ei anna me
         * ühendust kunagi käest — alarm on siis ainus, mis liha päästab.
         */
        fun cookInProgress(): Boolean
    }

    companion object {
        private const val TAG = "A5S"

        private const val BASE_SCAN_TIMEOUT_MS = 30_000L
        private const val RECONNECT_DELAY_MS = 3_000L

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
         * lõputult.
         */
        private const val CONNECT_GIVE_UP_MS = 120_000L

        /** Kui kaua proovime pimesi, enne kui võtame otsingu appi. */
        private const val BLIND_RETRY_WINDOW_MS = 10_000L

        /** STATE_ON tuleb enne, kui stack päriselt ühendusi vastu võtab. */
        private const val BLUETOOTH_SETTLE_MS = 1_500L
    }

    private val handler = Handler(Looper.getMainLooper())

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

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

    /**
     * Baasid, mille pime ühendus on läbi kukkunud ja mida tuleb enne järgmist
     * katset otsinguga üles leida. Vt [scanCallback].
     */
    private val scanConnectWanted = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var knownBases: List<String> = emptyList()

    private var scanning = false
    private var scanStartedAt = 0L

    @Volatile
    private var active = false

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
    private var givenUp = false

    /** Millal küsisime lingilt elumärki; 0 = ei oota vastust. */
    @Volatile
    private var livenessAskedAt = 0L

    /** Millal link viimati elumärgile vastas. */
    @Volatile
    private var lastLivenessOkAt = 0L

    // ------------------------------------------------------------------ elutsükkel

    /** Käivitab valvekoera. Kutsu siis, kui teenus on esiplaanile jõudnud. */
    fun start() {
        active = true
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    @SuppressLint("MissingPermission")
    fun teardown() {
        handler.removeCallbacksAndMessages(null)
        stopScan()
        gatts.values.forEach { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        gatts.clear()
    }

    fun shutdown() {
        active = false
        teardown()
    }

    /** Kasutaja tegi ise midagi — alustame proovimist otsast, ka pärast allaandmist. */
    fun resetGiveUp() {
        connectionLostSince = 0L
        givenUp = false
    }

    fun hasPermissions(): Boolean = blePermissionsGranted(context)

    // -------------------------------------------------------------------- ühendus

    /**
     * Ühendab kõigi registris olevate baasidega ja katkestab ülejäänud.
     *
     * Lahtiütlemine käib enne ühendamist ja alati. Varem oli siin tühja
     * nimekirja korral kiire väljapääs — viimase baasi eemaldamisel jäi ühendus
     * seetõttu vaikselt alles. Ühenduses seade aga ei reklaami end, nii et
     * otsing ei leidnud teda enam kunagi üles ja aitas ainult protsessi tapmine.
     */
    fun connectToBases(bases: List<String>) {
        if (!hasPermissions()) {
            ThermometerRepository.setError("Bluetoothi load puuduvad")
            return
        }
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            ThermometerRepository.setConnection(ConnectionState.BLUETOOTH_OFF)
            ThermometerRepository.setError("Bluetooth on välja lülitatud")
            return
        }

        knownBases = bases
        gatts.keys.filterNot { it in bases }.forEach { disconnect(it) }

        if (bases.isEmpty()) {
            ThermometerRepository.setConnection(ConnectionState.STOPPED)
            return
        }
        bases.forEach { address -> if (!gatts.containsKey(address)) connectTo(address) }
    }

    @SuppressLint("MissingPermission")
    fun disconnect(address: String) {
        connecting.remove(address)
        scanConnectWanted.remove(address)
        gatts.remove(address)?.let {
            Log.i(TAG, "Katkestan ühenduse baasiga $address")
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        ThermometerRepository.setBaseConnected(address, false)
    }

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

        val bt = adapter ?: return
        val device = runCatching { bt.getRemoteDevice(address) }.getOrNull() ?: return

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
        //
        // runCatching, sest siia jõuab ka otsingu tagasikutse ja viivitatud
        // taasühendamine, mis lubasid üle ei kontrolli. Loata connectGatt
        // viskaks SecurityException'i ja tapaks protsessi.
        val gatt = runCatching {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }.onFailure { Log.w(TAG, "Ühendumine baasiga $address ebaõnnestus", it) }.getOrNull()

        if (gatt != null) gatts[address] = gatt else connecting.remove(address)
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
        handler.postDelayed({ if (active) connectToBases(knownBases) }, RECONNECT_DELAY_MS)
    }

    private fun scheduleReconnect(address: String) {
        connecting.remove(address)
        if (!active || givenUp) return
        gatts.remove(address)?.let { runCatching { it.close() } }
        ThermometerRepository.setBaseConnected(address, false)
        if (ThermometerRepository.state.value.connectedBases.isEmpty()) {
            ThermometerRepository.setConnection(ConnectionState.RECONNECTING)
        }
        if (address !in knownBases) return

        val now = System.currentTimeMillis()
        if (connectionLostSince == 0L) connectionLostSince = now
        val failingFor = now - connectionLostSince

        // Käimasoleva küpsetuse ajal ei anna kunagi alla: alarm on siis ainus,
        // mis liha söödavana hoiab. Jõude olles ei ole mõtet akut tühjaks
        // proovida — kasutaja saab nupust uuesti alustada.
        if (failingFor > CONNECT_GIVE_UP_MS && !listener.cookInProgress()) {
            giveUp()
            return
        }

        // Mida kauem ei õnnestu, seda harvem proovime.
        val delayMs = when {
            failingFor < 30_000L -> RECONNECT_DELAY_MS
            failingFor < 60_000L -> 5_000L
            else -> 15_000L
        }
        // Esimesed sekundid proovime pimesi — see katab tavalise "baas läks
        // hetkeks levist välja" juhu ja on kiireim tee tagasi. Kui see ei aita,
        // on tõenäoline põhjus kadunud seadmekirje, mida ravib ainult otsing.
        val useScan = failingFor > BLIND_RETRY_WINDOW_MS
        handler.postDelayed(
            {
                if (!active || givenUp) return@postDelayed
                if (useScan) {
                    scanConnectWanted += address
                    startScan()
                }
                connectTo(address)
            },
            delayMs,
        )
    }

    @SuppressLint("MissingPermission")
    private fun giveUp() {
        Log.w(TAG, "Ühendust ei saanud $CONNECT_GIVE_UP_MS ms jooksul — lõpetan proovimise")
        givenUp = true
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

    // ------------------------------------------------------------------ bluetooth

    /**
     * Pärast taaskäivitust ei ole kontrolleril seadmekirjeid, seega otsing kohe
     * appi — pime katse siin ei õnnestu. Väike viivitus annab stackil valmis
     * saada, sest STATE_ON tuleb temast ette.
     */
    fun onBluetoothOn() {
        resetGiveUp()
        connecting.clear()
        start()
        handler.postDelayed({
            if (!active) return@postDelayed
            scanConnectWanted += knownBases
            startScan()
            connectToBases(knownBases)
        }, BLUETOOTH_SETTLE_MS)
    }

    fun onBluetoothOff() {
        teardown()
        ThermometerRepository.setConnection(ConnectionState.BLUETOOTH_OFF)
        ThermometerRepository.setError("Bluetooth on välja lülitatud")
    }

    // --------------------------------------------------------------- baasiotsing

    @SuppressLint("MissingPermission")
    fun startScan() {
        // "Otsing käib" on lipp, mille maha võtab käsuga postitatud tagasikutse.
        // Kui see tagasikutse kaob — näiteks koos teiste käskudega ühenduse
        // koristamisel — jääb lipp igaveseks püsti ja iga järgmine otsing
        // katkeb kohe alguses. Väljastpoolt paistab see nii, nagu baasi enam ei
        // olekski, ja aitab ainult protsessi tapmine. Seepärast ei usu me lippu
        // pimesi, vaid kontrollime kella.
        if (scanning) {
            val runningFor = System.currentTimeMillis() - scanStartedAt
            if (runningFor < BASE_SCAN_TIMEOUT_MS) return
            Log.w(TAG, "Otsingu lipp jäi $runningFor ms püsti — võtan maha")
            stopScan()
        }

        // Loata otsing viskab SecurityException'i. Kuna käsk tuleb kohale
        // onStartCommand'i kaudu, tapaks see kogu protsessi — ja START_STICKY
        // käivitaks teenuse kohe uuesti. Nii tekkis värskel paigaldusel
        // krahhiring: paaritumisekraan alustas otsingut samal hetkel, kui
        // loadialoog oli veel ees.
        if (!hasPermissions()) {
            Log.w(TAG, "Otsingut ei alusta — Bluetoothi luba puudub")
            ThermometerRepository.setError("Bluetoothi luba puudub")
            return
        }

        val scanner = adapter?.bluetoothLeScanner ?: return

        scanning = true
        scanStartedAt = System.currentTimeMillis()
        ThermometerRepository.setScanningForBases(true)
        Log.i(TAG, "Alustan baasiotsingut")

        // Teadlikult ilma ScanFilter'ita: A5 ei advertise oma teenuse UUID-d ja
        // nimefilter jätab osal seadmetel scan response'i vahele.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Luba võidakse ära võtta ka kontrolli ja selle rea vahel.
        val started = runCatching { scanner.startScan(null, settings, scanCallback) }
            .onFailure { Log.w(TAG, "Otsingu käivitamine ebaõnnestus", it) }
            .isSuccess
        if (!started) {
            scanning = false
            ThermometerRepository.setScanningForBases(false)
            ThermometerRepository.setError("Otsingut ei õnnestunud käivitada")
            return
        }
        handler.postDelayed({ stopScan() }, BASE_SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        ThermometerRepository.setScanningForBases(false)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device?.name ?: result.scanRecord?.deviceName ?: return
            if (name != A5sProtocol.ADVERTISED_NAME) return
            val address = result.device.address
            ThermometerRepository.addDiscoveredBase(
                DiscoveredBase(
                    address = address,
                    advertisedName = name,
                    rssi = result.rssi,
                ),
            )

            // Pime ühendus salvestatud aadressile kukub pärast Bluetoothi
            // taaskäivitust läbi, sest kontrolleril ei ole seadmest enam kirjet.
            // Värskelt nähtud seadmega õnnestub ühendus kohe, seega katkestame
            // pimeda katse ja alustame siit.
            if (scanConnectWanted.remove(address)) {
                Log.i(TAG, "Baas $address leiti otsinguga — ühendan")
                connecting.remove(address)
                gatts.remove(address)?.let { runCatching { it.close() } }
                connectTo(address)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Otsing ebaõnnestus, kood $errorCode")
            scanning = false
            ThermometerRepository.setScanningForBases(false)
            ThermometerRepository.setError(
                if (errorCode == SCAN_FAILED_SCANNING_TOO_FREQUENTLY) {
                    "Android piiras otsingut — oota pool minutit ja proovi uuesti"
                } else {
                    "Otsing ebaõnnestus (kood $errorCode)"
                },
            )
        }
    }

    // ---------------------------------------------------------------- valvekoer

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
    private val watchdog = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            val now = System.currentTimeMillis()
            val silentFor = now - lastFrameAt
            val watching = active && !givenUp && lastFrameAt > 0L &&
                gatts.isNotEmpty() && silentFor > STALE_DATA_TIMEOUT_MS

            when {
                !watching -> livenessAskedAt = 0L

                // Küpsetuse ajal peab andmeid tulema. Kui neid pikalt ei ole,
                // ei aita elumärk — teeme ühenduse uueks.
                listener.cookInProgress() && silentFor > COOKING_SILENCE_TIMEOUT_MS ->
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

    // --------------------------------------------------------------------- gatt

    /**
     * Tellib mõõtmiste teavitused. Eraldi funktsioon, sest seda on vaja nii
     * ühendumisel kui hiljem: kui link elab, aga andmeid ei tule, võib tellimus
     * olla vaikselt kadunud ja uuesti tellimine on odavam kui ühenduse
     * lammutamine.
     *
     * @return kas tellimine üldse õnnestus.
     */
    @SuppressLint("MissingPermission")
    private fun enableNotifications(g: BluetoothGatt): Boolean =
        runCatching { enableNotificationsOrThrow(g) }
            .onFailure { Log.w(TAG, "Teavituste tellimine ebaõnnestus", it) }
            .getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun enableNotificationsOrThrow(g: BluetoothGatt): Boolean {
        val characteristic = g.getService(A5sProtocol.SERVICE_UUID)
            ?.getCharacteristic(A5sProtocol.DATA_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            // Peaaegu alati aegunud teenusevahemälu, mitte vale seade. Otsing
            // enne järgmist katset annab stackile värske kirje.
            Log.w(TAG, "Teenust ei leitud (${g.device.address}) — otsin enne uut katset")
            scanConnectWanted += g.device.address
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

            scanConnectWanted.remove(address)
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

        listener.onFrame(baseAddress, value)
    }
}
