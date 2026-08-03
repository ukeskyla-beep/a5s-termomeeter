package ee.ukesk.a5s.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Baas ehk puidust laadimispesa — ainus seade, millega telefon päriselt
 * Bluetoothi kaudu ühendub. Neid võib olla mitu, kui kasutajal on rohkem kui
 * üks komplekt.
 */
@Entity(tableName = "known_base")
data class KnownBaseEntity(
    @PrimaryKey val address: String,
    val name: String,
    val addedAt: Long,
)

/**
 * Sond. Ei ole eraldi Bluetooth-seade — baas edastab tema mõõtmisi koos sondi
 * aadressiga. Meie jaoks on ta siiski omaette asi, millel on nimi.
 */
@Entity(tableName = "known_probe")
data class KnownProbeEntity(
    @PrimaryKey val address: String,
    val name: String,
    val baseAddress: String?,
    val addedAt: Long,
)

/** Kasutaja enda salvestatud sihttemperatuur. */
@Entity(tableName = "custom_target")
data class CustomTargetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val celsius: Int,
    val createdAt: Long,
)
