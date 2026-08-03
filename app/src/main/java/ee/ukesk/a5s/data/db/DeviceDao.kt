package ee.ukesk.a5s.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {

    // ------------------------------------------------------------------ baasid

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBase(base: KnownBaseEntity)

    @Query("SELECT * FROM known_base ORDER BY addedAt ASC")
    fun observeBases(): Flow<List<KnownBaseEntity>>

    @Query("SELECT * FROM known_base ORDER BY addedAt ASC")
    suspend fun bases(): List<KnownBaseEntity>

    @Query("DELETE FROM known_base WHERE address = :address")
    suspend fun deleteBase(address: String)

    // ------------------------------------------------------------------ sondid

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProbe(probe: KnownProbeEntity)

    @Query("SELECT * FROM known_probe ORDER BY addedAt ASC")
    fun observeProbes(): Flow<List<KnownProbeEntity>>

    @Query("SELECT * FROM known_probe ORDER BY addedAt ASC")
    suspend fun probes(): List<KnownProbeEntity>

    @Query("UPDATE known_probe SET name = :name WHERE address = :address")
    suspend fun renameProbe(address: String, name: String)

    @Query("DELETE FROM known_probe WHERE address = :address")
    suspend fun deleteProbe(address: String)

    // ------------------------------------------------------- omad temperatuurid

    @Insert
    suspend fun insertCustomTarget(target: CustomTargetEntity): Long

    @Query("SELECT * FROM custom_target ORDER BY createdAt DESC")
    fun observeCustomTargets(): Flow<List<CustomTargetEntity>>

    @Query("DELETE FROM custom_target WHERE id = :id")
    suspend fun deleteCustomTarget(id: Long)
}
