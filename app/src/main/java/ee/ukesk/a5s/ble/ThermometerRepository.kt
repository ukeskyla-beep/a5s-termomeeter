package ee.ukesk.a5s.ble

import ee.ukesk.a5s.data.CookTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ConnectionState {
    STOPPED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    BLUETOOTH_OFF,

    /**
     * Taasühendamine anti alla. Lõputu proovimine tühjendaks aku ja jätaks äpi
     * igavesse "ühendan…" olekusse, seega proovime piiratud aja ja pakume siis
     * nuppu. Küpsetuse ajal siia olekusse ei jõuta — vt CONNECT_GIVE_UP_MS.
     */
    GAVE_UP,
}

/**
 * Ühe sondi olek. Baas edastab iga sondi kohta eraldi kirje koos tema
 * BLE-aadressiga, seega on aadress loomulik võti.
 */
data class ProbeState(
    val address: String,
    val name: String? = null,
    val celsius: Double,
    val batteryPercent: Int,
    val lastUpdateAt: Long,
    val target: CookTarget? = null,
    /**
     * Millal stopper käivitus. Nullitakse iga kord, kui siht muutub —
     * ajaloos jääb küpsetus siiski üheks kirjeks.
     */
    val timerStartedAt: Long? = null,
    val preWarnFired: Boolean = false,
    val alarmFired: Boolean = false,
) {
    val isDemo: Boolean get() = address.startsWith(DEMO_ADDRESS_PREFIX)

    val displayName: String get() = when {
        name != null -> name
        isDemo -> "Demo sond"
        else -> "Sond ${address.takeLast(5)}"
    }

    val isCooking: Boolean get() = target != null

    /**
     * Kas näitu ei tohi enam usaldada. Demo sond seisab meelega paigal, tema
     * kohta see ei kehti.
     */
    fun isReadingStale(now: Long): Boolean {
        if (isDemo) return false
        val limit = if (isCooking) COOKING_STALE_MS else IDLE_STALE_MS
        return now - lastUpdateAt > limit
    }
}

/**
 * Küpsetuse ajal saadab sond tihedalt ja vaikus on kohe kahtlane.
 *
 * Jõude olles tuleb mõõtmine ebaühtlaselt — mõnikord nelja, mõnikord
 * kolmekümne sekundi tagant. Lühem piir vilguks siin lakkamatult ette ja
 * tagasi, seega on jõudeoleku piir tunduvalt lahkem.
 */
const val COOKING_STALE_MS = 30_000L
const val IDLE_STALE_MS = 120_000L

/**
 * Demo sondi aadress. Prefiksi järgi tunneme demo ära kõikjal — nii ei ole vaja
 * ei eraldi andmebaasi veergu ega registrikirjet, mis pärast demot alles jääks.
 */
const val DEMO_ADDRESS_PREFIX = "DEMO:"
const val DEMO_PROBE_ADDRESS = "DEMO:00:00:00:01"

/** Toatemperatuur, mille peal demo sond seisab, kuni siht on valimata. */
const val DEMO_AMBIENT_C = 20.0

/**
 * Demo sond on nimekirjas alati olemas — eraldi "demo režiimi" ei ole. Nii on
 * see lihtsalt üks andur teiste seas ja kogu ülejäänud äpp ei pea teadma, et
 * demo üldse eksisteerib.
 */
private fun demoProbe() = ProbeState(
    address = DEMO_PROBE_ADDRESS,
    celsius = DEMO_AMBIENT_C,
    batteryPercent = 100,
    lastUpdateAt = System.currentTimeMillis(),
)

/** Skaneerimisel leitud baas, mida pole veel lisatud. */
data class DiscoveredBase(
    val address: String,
    val advertisedName: String,
    val rssi: Int,
)

data class ThermometerState(
    val connection: ConnectionState = ConnectionState.STOPPED,
    /** Aadress → sond. LinkedHashMap hoiab avastamise järjekorra, nii et kaardid ei hüppa. */
    val probes: Map<String, ProbeState> = emptyMap(),
    /** Alarm heliseb — üks heli kõigi sondide peale, mitte igaühele oma. */
    val alarmSounding: Boolean = false,
    val scanningForBases: Boolean = false,
    val discoveredBases: List<DiscoveredBase> = emptyList(),
    val connectedBases: Set<String> = emptySet(),
    val lastError: String? = null,
) {
    val probeList: List<ProbeState> get() = probes.values.toList()
}

/**
 * Ühine olek teenuse ja UI vahel. Teadlikult objekt, mitte DI —
 * äpp on väike ja teenus peab olekule ligi pääsema ka siis,
 * kui Activity't ei ole olemas.
 */
object ThermometerRepository {

    private val _state = MutableStateFlow(
        ThermometerState(probes = mapOf(DEMO_PROBE_ADDRESS to demoProbe())),
    )
    val state: StateFlow<ThermometerState> = _state.asStateFlow()

    /**
     * Nimed registrist. Hoiame neid eraldi, sest sond luuakse olekusse esimese
     * mõõtmise pealt — ilma selleta jääks ta nimetuks kuni järgmise
     * andmebaasimuudatuseni, mida tavaliselt ei tulegi.
     */
    @Volatile
    private var names: Map<String, String> = emptyMap()

    fun setConnection(connection: ConnectionState) {
        _state.update { it.copy(connection = connection) }
    }

    fun setError(message: String?) {
        _state.update { it.copy(lastError = message) }
    }

    fun publishReading(reading: A5sProtocol.Reading) {
        _state.update { state ->
            val existing = state.probes[reading.address]
            val updated = existing?.copy(
                celsius = reading.celsius,
                batteryPercent = reading.batteryRaw,
                lastUpdateAt = System.currentTimeMillis(),
            ) ?: ProbeState(
                address = reading.address,
                name = names[reading.address],
                celsius = reading.celsius,
                batteryPercent = reading.batteryRaw,
                lastUpdateAt = System.currentTimeMillis(),
            )
            state.copy(probes = state.probes + (reading.address to updated))
        }
    }

    /** Nimed tulevad seadmeregistrist, mida hoiab teenus. */
    fun applyNames(newNames: Map<String, String>) {
        names = newNames
        _state.update { state ->
            state.copy(
                probes = state.probes.mapValues { (address, probe) ->
                    probe.copy(name = newNames[address])
                },
            )
        }
    }

    /**
     * Sihi seadmine käivitab küpsetuse: stopper nullist, alarm valvesse.
     * Sihi muutmine keset küpsetust nullib stopperi, aga ajaloos jääb
     * küpsetus üheks kirjeks.
     */
    fun setTarget(address: String, target: CookTarget) {
        updateProbe(address) {
            it.copy(
                target = target,
                timerStartedAt = System.currentTimeMillis(),
                preWarnFired = false,
                alarmFired = false,
            )
        }
        _state.update { it.copy(alarmSounding = false) }
    }

    /** "Lõpeta" — stopper seisma, alarm valvest maha, seanss kinni. */
    fun finishCook(address: String) {
        updateProbe(address) {
            it.copy(
                target = null,
                timerStartedAt = null,
                preWarnFired = false,
                alarmFired = false,
            )
        }
        _state.update { it.copy(alarmSounding = false) }
    }

    fun markPreWarnFired(address: String) {
        updateProbe(address) { it.copy(preWarnFired = true) }
    }

    fun markAlarmFired(address: String) {
        updateProbe(address) { it.copy(alarmFired = true) }
        _state.update { it.copy(alarmSounding = true) }
    }

    fun setAlarmSounding(sounding: Boolean) {
        _state.update { it.copy(alarmSounding = sounding) }
    }

    fun markAlarmSilenced() {
        _state.update { it.copy(alarmSounding = false) }
    }

    // ----------------------------------------------------------- paaritamine

    fun setScanningForBases(scanning: Boolean) {
        _state.update {
            it.copy(
                scanningForBases = scanning,
                discoveredBases = if (scanning) emptyList() else it.discoveredBases,
            )
        }
    }

    fun addDiscoveredBase(base: DiscoveredBase) {
        _state.update { state ->
            if (state.discoveredBases.any { it.address == base.address }) {
                state.copy(
                    discoveredBases = state.discoveredBases.map {
                        if (it.address == base.address) base else it
                    },
                )
            } else {
                state.copy(discoveredBases = state.discoveredBases + base)
            }
        }
    }

    /** Baas eemaldati — tema sondid ei tohi nimekirja vana näiduga seisma jääda. */
    fun forgetProbes(addresses: Collection<String>) {
        if (addresses.isEmpty()) return
        _state.update { it.copy(probes = it.probes - addresses.toSet()) }
    }

    fun setBaseConnected(address: String, connected: Boolean) {
        _state.update { state ->
            state.copy(
                connectedBases = if (connected) {
                    state.connectedBases + address
                } else {
                    state.connectedBases - address
                },
            )
        }
    }

    private fun updateProbe(address: String, transform: (ProbeState) -> ProbeState) {
        _state.update { state ->
            val probe = state.probes[address] ?: return@update state
            state.copy(probes = state.probes + (address to transform(probe)))
        }
    }

    fun reset() {
        _state.value = ThermometerState(probes = mapOf(DEMO_PROBE_ADDRESS to demoProbe()))
    }
}
