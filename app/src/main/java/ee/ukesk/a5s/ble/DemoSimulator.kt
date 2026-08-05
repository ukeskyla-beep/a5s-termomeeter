package ee.ukesk.a5s.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Virtuaalne sond, millega saab kogu äppi läbi katsuda ilma riistvarata.
 *
 * Simulaator asendab ainult andmeallika. Kõik ülejäänu — alarm, salvestamine,
 * teavitus — käib täpselt sama koodi kaudu mis päris anduri puhul, muidu ei
 * testiks demo midagi.
 *
 * @param onReading kuhu valmis mõõtmine saata; sama tee mis päris kaadril.
 */
class DemoSimulator(
    private val scope: CoroutineScope,
    private val onReading: (A5sProtocol.Reading) -> Unit,
) {
    companion object {
        /** Simulatsiooni parameetrid: ~57 °C kahe minutiga. */
        private const val OVEN_C = 95.0
        private const val TAU_S = 180.0
        private const val MAX_C = 150.0
        private const val BOOST_STEP_C = 10.0
    }

    private var job: Job? = null

    @Volatile
    private var boostCelsius = 0.0

    val isRunning: Boolean get() = job != null

    fun start() {
        if (job != null) return

        val startedAt = System.currentTimeMillis()
        job = scope.launch {
            while (isActive) {
                val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
                // Newtoni jahtumisseadus tagurpidi: kiire tõus alguses, siis
                // aeglustub — nagu päris lihal ahjus.
                val natural = OVEN_C - (OVEN_C - DEMO_AMBIENT_C) *
                    exp(-seconds / TAU_S)
                emit(natural + boostCelsius)
                delay(1000)
            }
        }
    }

    /** Siht maha võetud — sond läheb tagasi toatemperatuurile ja jääb sinna. */
    fun stop() {
        job?.cancel()
        job = null
        boostCelsius = 0.0
        emit(DEMO_AMBIENT_C)
    }

    /**
     * "+10 °C" kiirendab ootamist. Töötab ka enne küpsetuse algust, sest just
     * nii saab katsetada hoiatust "siht on juba käes".
     */
    fun boost() {
        boostCelsius += BOOST_STEP_C
        if (job == null) emit(DEMO_AMBIENT_C + boostCelsius)
    }

    /**
     * Lisatud kraadid maha. Eraldi nuppu ei ole: seda kutsub demo sondi lehelt
     * lahkumine, nii et iga kord tuleb lehele tagasi puhas algseis.
     */
    fun resetBoost() {
        if (boostCelsius == 0.0) return
        boostCelsius = 0.0
        if (job == null) emit(DEMO_AMBIENT_C)
    }

    /** Päris andur annab täiskraade, demo teeb sama. */
    private fun emit(celsius: Double) {
        onReading(
            A5sProtocol.Reading(
                address = DEMO_PROBE_ADDRESS,
                celsius = min(MAX_C, celsius).roundToInt().toDouble(),
                batteryRaw = 100,
            ),
        )
    }
}
