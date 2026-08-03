package ee.ukesk.a5s.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Üks küpsetus. Algab siis, kui sihttemperatuur seatakse, ja lõpeb siis, kui
 * siht tühistatakse või mõõtmine peatatakse.
 */
@Entity(tableName = "cook_session")
data class CookSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    /** Millise sondi küpsetus. Null = kirje on vanem kui mitme sondi tugi. */
    val probeAddress: String? = null,
    val meat: String? = null,
    val doneness: String? = null,
    val targetCelsius: Int? = null,
    /** Kõrgeim mõõdetud temperatuur — et nimekirjas ei peaks kõiki punkte lugema. */
    val peakCelsius: Double? = null,
)

@Entity(
    tableName = "sample",
    indices = [Index("sessionId"), Index(value = ["sessionId", "at"])],
    foreignKeys = [
        ForeignKey(
            entity = CookSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val at: Long,
    @ColumnInfo(name = "celsius") val celsius: Double,
)
