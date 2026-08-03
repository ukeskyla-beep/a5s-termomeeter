package ee.ukesk.a5s.data

/**
 * Soovituslikud sisetemperatuurid.
 *
 * Ohutuse alammäärad pärinevad USDA soovitustest, mis on ka EL-i praktikas
 * levinuim viide: linnuliha 74 °C, hakkliha 71 °C, tervikliha ja kala 63 °C
 * (+ 3 min seismist). Need on märgitud [TargetKind.SAFETY].
 *
 * Madalamad astmed on maitse-eelistus, mitte ohutu miinimum. Terviklihatükil
 * (praad, karbonaad) on bakterid pinnal, mille kuum pann või grill hävitab —
 * seepärast on roosa praad tavapärane. Hakkliha, linnuliha ja torgitud liha
 * puhul see ei kehti. Astmed, mis jäävad ohutu miinimumi alla, on märgitud
 * [TargetKind.BELOW_SAFE_MINIMUM] ja äpp näitab nende juures hoiatust.
 */
enum class TargetKind {
    /** Maitse-eelistus, ohutu miinimumi peal või üle selle. */
    PREFERENCE,

    /** See ongi ohutu miinimum — madalamale minna ei tohiks. */
    SAFETY,

    /** Maitse-eelistus, mis jääb ametlikust ohutust miinimumist allapoole. */
    BELOW_SAFE_MINIMUM,
}

data class Doneness(
    val label: String,
    val celsius: Int,
    val kind: TargetKind = TargetKind.PREFERENCE,
)

data class Meat(
    val name: String,
    val emoji: String,
    val donenessOptions: List<Doneness>,
    val note: String? = null,
)

data class CookTarget(
    val meat: String,
    val doneness: String,
    val celsius: Int,
    val kind: TargetKind,
    /** Mitu kraadi enne sihti anda eelhoiatus. */
    val preWarnDelta: Int = 5,
)

object MeatTargets {

    val all: List<Meat> = listOf(
        Meat(
            name = "Veiseliha (praad, rostbiif)",
            emoji = "🥩",
            note = "Terviklihatükk. Mõõda kõige paksemast kohast, luust eemal.",
            donenessOptions = listOf(
                Doneness("Rare", 52, TargetKind.BELOW_SAFE_MINIMUM),
                Doneness("Medium rare", 55, TargetKind.BELOW_SAFE_MINIMUM),
                Doneness("Medium", 60, TargetKind.BELOW_SAFE_MINIMUM),
                Doneness("Medium well", 65),
                Doneness("Läbiküpsenud", 71),
            ),
        ),
        Meat(
            name = "Lambaliha (tervik)",
            emoji = "🐑",
            donenessOptions = listOf(
                Doneness("Medium rare", 55, TargetKind.BELOW_SAFE_MINIMUM),
                Doneness("Medium", 60, TargetKind.BELOW_SAFE_MINIMUM),
                Doneness("Medium well", 65),
                Doneness("Läbiküpsenud", 71),
            ),
        ),
        Meat(
            name = "Sealiha (karbonaad, sisefilee)",
            emoji = "🐖",
            note = "63 °C juures jääb liha kergelt roosa ja mahlane — see on ohutu.",
            donenessOptions = listOf(
                Doneness("Mahlane, kergelt roosa", 63, TargetKind.SAFETY),
                Doneness("Läbiküpsenud", 71),
            ),
        ),
        Meat(
            name = "Hakkliha (burger, kotlet, lihapall)",
            emoji = "🍔",
            note = "Hakklihas on bakterid läbisegi kogu tükis, mitte ainult pinnal. " +
                "Roosa hakkliha ei ole ohutu.",
            donenessOptions = listOf(
                Doneness("Ohutu miinimum", 71, TargetKind.SAFETY),
                Doneness("Kindla peale", 75),
            ),
        ),
        Meat(
            name = "Kana ja kalkun",
            emoji = "🍗",
            note = "Mõõda kõige paksemast kohast — kanal reie sisemusest, luud puutumata.",
            donenessOptions = listOf(
                Doneness("Ohutu miinimum", 74, TargetKind.SAFETY),
                Doneness("Reieliha, pehmem tekstuur", 80),
            ),
        ),
        Meat(
            name = "Pardirind",
            emoji = "🦆",
            note = "Terviklihatükk, mida serveeritakse tavaliselt roosana. " +
                "Linnuliha ohutu miinimum on siiski 74 °C.",
            donenessOptions = listOf(
                Doneness("Medium rare", 55, TargetKind.BELOW_SAFE_MINIMUM),
                Doneness("Medium", 60, TargetKind.BELOW_SAFE_MINIMUM),
                Doneness("Läbiküpsenud", 74, TargetKind.SAFETY),
            ),
        ),
        Meat(
            name = "Kala (lõhe, forell, tuunikala)",
            emoji = "🐟",
            donenessOptions = listOf(
                Doneness("Mahlane, poolläbipaistev", 50, TargetKind.BELOW_SAFE_MINIMUM),
                Doneness("Medium", 55, TargetKind.BELOW_SAFE_MINIMUM),
                Doneness("Ohutu miinimum", 63, TargetKind.SAFETY),
            ),
        ),
        Meat(
            name = "Toored vorstid",
            emoji = "🌭",
            donenessOptions = listOf(
                Doneness("Ohutu miinimum", 71, TargetKind.SAFETY),
            ),
        ),
        Meat(
            name = "Sink (eelküpsetatud, soojendamine)",
            emoji = "🍖",
            donenessOptions = listOf(
                Doneness("Soojendatud", 60),
            ),
        ),
        Meat(
            name = "Aeglane küpsetamine (BBQ)",
            emoji = "🔥",
            note = "Siin ei ole eesmärk ohutus, vaid sidekoe lagunemine. " +
                "Need temperatuurid on ohutust miinimumist tunduvalt kõrgemal.",
            donenessOptions = listOf(
                Doneness("Ribid", 90),
                Doneness("Pull pork (seakael)", 93),
                Doneness("Brisket (veise rinnatükk)", 96),
            ),
        ),
    )

    /** Ikoon liha nime järgi. Sihid salvestatakse nimena, emoji tuletame siit. */
    fun emojiFor(meatName: String): String =
        all.firstOrNull { it.name == meatName }?.emoji ?: "🍽"

    /** Hoiatustekst, mida näidata madala sihttemperatuuri juures. */
    fun warningFor(kind: TargetKind): String? = when (kind) {
        TargetKind.BELOW_SAFE_MINIMUM ->
            "See siht jääb ametlikust ohutust miinimumist allapoole. " +
                "Terviklihal on see tavapärane, aga riskirühmadele " +
                "(rasedad, väikelapsed, eakad, nõrga immuunsusega) ei ole soovitatav."
        else -> null
    }
}
