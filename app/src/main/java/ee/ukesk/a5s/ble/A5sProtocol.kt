package ee.ukesk.a5s.ble

import java.util.UUID

/**
 * AIBBQ A5S lihatermomeetri BLE protokoll.
 *
 * Pöördprojekteeritud 2026-08-01 nRF Connecti logist (381 paketti). Baas advertib
 * nimega "A5" ja saadab teavitusi ~165 ms tagant, ilma et oleks vaja midagi
 * käsukanalile kirjutada — piisab CCCD lubamisest.
 *
 * Paketi formaat, alati 15 baiti:
 *
 *     bait  0    1     2..7          8      9       10-11      12-13   14
 *          FF   21   BLE-aadress   roll   aku?   temp (BE)     0000    FD
 *
 * Kinnitatud: temperatuur = big-endian uint16 baitidest 10-11, jagatud 100-ga.
 * Kõik 82 logis esinenud väärtust olid täpselt 100 kordsed (andur annab 1 °C sammu),
 * ja need klappisid seadme enda Fahrenheiti-näiduga sajandiku täpsusega.
 */
object A5sProtocol {

    /** Nimi, millega baas advertib. */
    const val ADVERTISED_NAME = "A5"

    val SERVICE_UUID: UUID = UUID.fromString("43f4b114-ca67-48e8-a46f-9a8ffeb7146a")

    /** NOTIFY — siit tulevad mõõtmised. */
    val DATA_CHARACTERISTIC_UUID: UUID = UUID.fromString("bf83f3f2-399a-414d-9035-ce64ceb3ff67")

    /** READ / WRITE-NO-RESPONSE käsukanal. Lugemiseks pole vaja, hoiame teadmiseks. */
    val COMMAND_CHARACTERISTIC_UUID: UUID = UUID.fromString("bf83f3f1-399a-414d-9035-ce64ceb3ff67")

    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * OTA püsivara teenus. EI TOHI puutuda — juhuslike baitide kirjutamine
     * sellesse on ainus teadaolev viis seade päriselt ära rikkuda.
     */
    val OTA_SERVICE_UUID_DO_NOT_TOUCH: UUID =
        UUID.fromString("3345c2f0-6f36-45c5-8541-92f56728d5f3")

    private const val FRAME_SIZE = 15
    private const val FRAME_START = 0xFF.toByte()
    private const val FRAME_END = 0xFD.toByte()
    private const val MSG_STATUS = 0x21.toByte()

    private const val ROLE_BASE = 0x00.toByte()
    private const val ROLE_PROBE = 0x01.toByte()

    /**
     * Üks sondi mõõtmine.
     *
     * @param batteryRaw bait 9 — aku protsent. Algses logis esines ainult 0x3C (60)
     *   ja 0x46 (70), mis tegi tõlgenduse kahtlaseks. Kinnitus tuli hiljem samal
     *   päeval: pärast sondi laadimises hoidmist näitas sama bait 0x5A (90).
     */
    data class Reading(
        val address: String,
        val celsius: Double,
        val batteryRaw: Int,
    ) {
        val fahrenheit: Double get() = celsius * 9.0 / 5.0 + 32.0
    }

    /**
     * Parsib ühe teavituse. Tagastab null, kui tegu ei ole sondi mõõtmisega —
     * see hõlmab ka baasi enda kirjet (roll 0x00), mille sisu oli 40 minuti
     * jooksul muutumatu ega ole temperatuur.
     */
    fun parse(frame: ByteArray): Reading? {
        if (frame.size != FRAME_SIZE) return null
        if (frame[0] != FRAME_START || frame[FRAME_SIZE - 1] != FRAME_END) return null
        if (frame[1] != MSG_STATUS) return null
        if (frame[8] != ROLE_PROBE) return null

        val address = (2..7).joinToString(":") { "%02X".format(frame[it]) }

        // Big-endian. Märgiga lugemine on siin ettevaatusabinõu, mitte vajadus:
        // sügavkülmas testides jäi näit nulli kinni, seega seade ise miinuskraade
        // ei raporteeri. Loogiline ka — see on lihatermomeeter, mitte külmiku oma.
        val raw = (((frame[10].toInt() and 0xFF) shl 8) or (frame[11].toInt() and 0xFF)).toShort()

        return Reading(
            address = address,
            celsius = raw / 100.0,
            batteryRaw = frame[9].toInt() and 0xFF,
        )
    }

    /** Kas see pakett on baasi enda kirje? Kasutame ainult logimiseks. */
    fun isBaseRecord(frame: ByteArray): Boolean =
        frame.size == FRAME_SIZE && frame[0] == FRAME_START && frame[8] == ROLE_BASE

    fun ByteArray.toHex(): String = joinToString("-") { "%02X".format(it) }
}
