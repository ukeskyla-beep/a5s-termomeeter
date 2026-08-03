package ee.ukesk.a5s.data

import android.content.Context
import ee.ukesk.a5s.data.db.AppDatabase
import ee.ukesk.a5s.data.db.CookDao
import ee.ukesk.a5s.data.db.CookSessionEntity
import ee.ukesk.a5s.data.db.SampleEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Salvestab küpsetuse käigu andmebaasi, iga sondi kohta eraldi seanss.
 *
 * Seanss algab, kui sellele sondile seatakse sihttemperatuur, ja lõpeb, kui
 * siht tühistatakse või mõõtmine peatatakse. Ilma sihita ühendust ei
 * salvestata — muidu tekiks iga juhusliku ühenduse kohta tühi kirje.
 */
object CookRecorder {

    /** Punkt salvestatakse, kui väärtus muutub või kui nii palju aega on möödas. */
    private const val SAMPLE_INTERVAL_MS = 10_000L

    /** Alla selle punkti arvu ei ole seanss midagi väärt — visatakse ära. */
    private const val MIN_SAMPLES_TO_KEEP = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var dao: CookDao? = null

    private val sessionIds = ConcurrentHashMap<String, Long>()
    private val lastSampleAt = ConcurrentHashMap<String, Long>()
    private val lastSampleCelsius = ConcurrentHashMap<String, Double>()

    fun init(context: Context) {
        if (dao != null) return
        val d = AppDatabase.get(context).cookDao()
        dao = d
        // Kui protsess tapeti keset küpsetust, jäid seansid lahti.
        scope.launch { d.closeDanglingSessions() }
    }

    fun startOrUpdateSession(address: String, target: CookTarget) {
        val d = dao ?: return
        scope.launch {
            val existing = sessionIds[address]
            if (existing != null) {
                d.updateTarget(existing, target.meat, target.doneness, target.celsius)
            } else {
                lastSampleAt.remove(address)
                lastSampleCelsius.remove(address)
                sessionIds[address] = d.insertSession(
                    CookSessionEntity(
                        startedAt = System.currentTimeMillis(),
                        probeAddress = address,
                        meat = target.meat,
                        doneness = target.doneness,
                        targetCelsius = target.celsius,
                    ),
                )
            }
        }
    }

    fun endSession(address: String) {
        val d = dao ?: return
        val id = sessionIds.remove(address) ?: return
        lastSampleAt.remove(address)
        lastSampleCelsius.remove(address)
        scope.launch {
            if (d.sampleCount(id) < MIN_SAMPLES_TO_KEEP) {
                d.deleteSession(id)
            } else {
                d.endSession(id, System.currentTimeMillis())
            }
        }
    }

    fun endAllSessions() {
        sessionIds.keys.toList().forEach { endSession(it) }
    }

    fun record(address: String, celsius: Double) {
        val d = dao ?: return
        val id = sessionIds[address] ?: return

        val now = System.currentTimeMillis()
        val unchanged = lastSampleCelsius[address] == celsius
        if (unchanged && now - (lastSampleAt[address] ?: 0L) < SAMPLE_INTERVAL_MS) return

        lastSampleAt[address] = now
        lastSampleCelsius[address] = celsius

        scope.launch {
            d.insertSample(SampleEntity(sessionId = id, at = now, celsius = celsius))
            d.raisePeak(id, celsius)
        }
    }
}
