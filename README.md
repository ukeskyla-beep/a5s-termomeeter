# A5S Termomeeter

Oma Android-äpp AIBBQ A5S juhtmevaba lihatermomeetri lugemiseks, tootja äppi kasutamata.

> **English:** an independent Android app for the AIBBQ A5S wireless meat
> thermometer. The device's BLE protocol was reverse-engineered from scratch and
> is fully documented in [Protokoll](#protokoll) below and in
> [`A5sProtocol.kt`](app/src/main/java/ee/ukesk/a5s/ble/A5sProtocol.kt) — that
> part is language-agnostic and should be useful even if you don't read Estonian.

Valid liha tüübi ja valmimisastme, äpp seab selle järgi alarmi ja annab märku ka
siis, kui ekraan on kustunud.

## Mida ta oskab

- Reaalajas temperatuur, ka taustal (foreground service, automaatne taasühendumine)
- Liha tüübi ja valmimisastme valik → sihttemperatuur → alarm, eelhoiatus 5 °C varem
- Alarm mängib tsüklis ALARM-helikanalil, kuni selle peatad — mitte ühekordne piiks
- "Testi alarmi" nupp kuuldavuse kontrollimiseks enne küpsetust
- Hoiatab, kui aku optimeerimine või liiga vaikne äratuse helitugevus võiks alarmi ära rikkuda
- Küpsetuse ajalugu koos küpsetuskõveraga (Room + Compose Canvas)
- Mitu sondi korraga, igaühel oma siht ja oma alarm
- Demo sond andurite nimekirjas: kogu äppi saab läbi katsuda ka ilma riistvarata,
  eraldi režiimi sisse lülitama ei pea

## Protokoll

Pöördprojekteeritud 2026-08-01 nRF Connecti logist (381 paketti). Täielik
kirjeldus koodis: [`A5sProtocol.kt`](app/src/main/java/ee/ukesk/a5s/ble/A5sProtocol.kt).

Lühidalt — baas advertib nimega `A5`, teenus
`43f4b114-ca67-48e8-a46f-9a8ffeb7146a`, andmed tulevad characteristic'ult
`bf83f3f2-…` NOTIFY kaudu ~165 ms tagant. **Käepigistust ei ole vaja**, piisab
CCCD lubamisest.

Iga pakett on 15 baiti:

```
bait  0    1     2..7          8      9       10-11      12-13   14
     FF   21   BLE-aadress   roll   aku?   temp (BE)     0000    FD
```

**`°C = big_endian_uint16(bait[10], bait[11]) / 100`**

Roll `0x01` on sond, `0x00` on baas ise (staatiline kirje, mitte temperatuur —
filtreeri välja).

### Hoiatus

Teenust `3345c2f0-6f36-45c5-8541-92f56728d5f3` **ei tohi puutuda** — see on
peaaegu kindlasti püsivara uuenduse (OTA) kanal ja sinna kirjutamine võib seadme
kasutuskõlbmatuks muuta.

## Ehitamine

```bash
./gradlew installDebug
```

Nõuab JDK 17+ ja Android SDK-d. Windowsis tuleb JDK vajadusel ette anda:

```bash
JAVA_HOME="C:/Program Files/Microsoft/jdk-21" ./gradlew installDebug
```

Allkirjastatud väljalaske ehitamiseks on vaja võtmehoidlat ja faili
`keystore.properties`. **Kumbki ei ela repo kaustas** — nende koht on
väljaspool projekti, et võti ei saaks sinna ka kogemata sattuda. Asukoha annab
`~/.gradle/gradle.properties`:

```properties
a5sKeystoreDir=C:/Users/<sina>/Keys/a5s
```

Seadistamise sammud on failis `keystore.properties.example`. Ilma võtmeta
ehitub release ilma allkirjata (`app-release-unsigned.apk`) — CI ja teiste
masinate jaoks normaalne, ainult paigaldada ei saa.

Toolchain: AGP 9.3.1, Gradle 9.6.1, Kotlin 2.4.10, compileSdk 37, Room 2.8.4 + KSP.

**AGP 9 eripära:** Kotlini tugi on Android-pluginasse sisse ehitatud, seega
`org.jetbrains.kotlin.android` plugin ei ole enam lubatud. Compose'i kompilaatori
plugin (`org.jetbrains.kotlin.plugin.compose`) on siiski endiselt vajalik.

## Testimata kohad

- ~~Negatiivsed temperatuurid~~ — testitud sügavkülmas: näit jääb **0 °C peale
  kinni**, seade ise miinuskraade ei raporteeri. Mõõtevahemik algab nullist.
- **Mitu sondi korraga.** Kood on aadressipõhine ja UI näitab iga sondi eraldi,
  aga päriselt on seda testitud ainult ühe sondiga — teist ei olnud võtta.

## Riistvara omapärad

- **Baas ei pruugi kiirlaadijaga laadida.** USB-C PD laadijaga võib ta jääda
  täiesti vaikseks — ei tulesid, ei laadimist — kuigi sama juhe laeb telefoni
  probleemideta. Tavalise laadijaga algab laadimine liikuvate tuledega.
- Baas on pime, kui sond on pesas ja juhet küljes ei ole. Sondi välja võttes
  näitab ta sondi temperatuuri Fahrenheitides; ühikut seadmest muuta ei saa.

## Toiduohutus

Sihttemperatuurid on failis
[`MeatTargets.kt`](app/src/main/java/ee/ukesk/a5s/data/MeatTargets.kt).
Ohutuse alammäärad järgivad USDA soovitusi: linnuliha 74 °C, hakkliha 71 °C,
tervikliha ja kala 63 °C. Astmed, mis jäävad nendest allapoole, on koodis
märgistatud ja äpp näitab nende juures hoiatust.
