package ee.ukesk.a5s.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CookDao {

    @Insert
    suspend fun insertSession(session: CookSessionEntity): Long

    @Insert
    suspend fun insertSample(sample: SampleEntity)

    @Query("UPDATE cook_session SET meat = :meat, doneness = :doneness, targetCelsius = :target WHERE id = :sessionId")
    suspend fun updateTarget(sessionId: Long, meat: String?, doneness: String?, target: Int?)

    @Query("UPDATE cook_session SET peakCelsius = :peak WHERE id = :sessionId AND (peakCelsius IS NULL OR peakCelsius < :peak)")
    suspend fun raisePeak(sessionId: Long, peak: Double)

    @Query("UPDATE cook_session SET endedAt = :endedAt WHERE id = :sessionId AND endedAt IS NULL")
    suspend fun endSession(sessionId: Long, endedAt: Long)

    /** Sulgeb seansid, mis jäid lahti, kui protsess tapeti. */
    @Query("UPDATE cook_session SET endedAt = startedAt WHERE endedAt IS NULL")
    suspend fun closeDanglingSessions()

    @Query("SELECT * FROM cook_session ORDER BY startedAt DESC")
    fun observeSessions(): Flow<List<CookSessionEntity>>

    @Query("SELECT * FROM cook_session WHERE id = :sessionId")
    fun observeSession(sessionId: Long): Flow<CookSessionEntity?>

    @Query("SELECT * FROM sample WHERE sessionId = :sessionId ORDER BY at ASC")
    fun observeSamples(sessionId: Long): Flow<List<SampleEntity>>

    @Query("DELETE FROM cook_session WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    @Query("SELECT COUNT(*) FROM sample WHERE sessionId = :sessionId")
    suspend fun sampleCount(sessionId: Long): Int
}
