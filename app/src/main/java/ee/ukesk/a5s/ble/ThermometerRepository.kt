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
}

/**
 * Demo sondi aadress. Prefiksi järgi tunneme demo ära kõikjal — nii ei ole vaja
 * ei eraldi andmebaasi veergu ega registrikirjet, mis pärast demot alles jääks.
 */
const val DEMO_ADDRESS_PREFIX = "DEMO:"
const val DEMO_PROBE_ADDRESS = "DEMO:00:00:00:01"

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
    /** Demo režiim: andmed tulevad simulaatorist, mitte päris baasist. */
    val demoMode: Boolean = false,
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

    private val _state = MutableStateFlow(ThermometerState())
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

    fun setDemoMode(enabled: Boolean) {
        _state.update { state ->
            state.copy(
                demoMode = enabled,
                // Demo ajal on Bluetooth maha võetud, seega päris sondide
                // näidud on vananenud — need tuleb ära koristada, et nad ei
                // jätaks muljet töötavast ühendusest. Demo lõppedes kaob
                // virtuaalne sond samal põhjusel.
                probes = state.probes.filterKeys { address ->
                    val isDemoProbe = address.startsWith(DEMO_ADDRESS_PREFIX)
                    if (enabled) isDemoProbe else !isDemoProbe
                },
                lastError = null,
            )
        }
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
        _state.value = ThermometerState()
    }
}
