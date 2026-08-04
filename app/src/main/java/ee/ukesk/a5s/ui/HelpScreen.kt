package ee.ukesk.a5s.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HelpScreen(onBack: () -> Unit) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onBack) { Text("←  Tagasi") }

            Text(
                text = "Abi",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            Section("Alustamine")
            Body(
                "Lülita baas (puidust pesa) sisse. Äpp ühendub ise, eraldi nuppu ei ole. " +
                    "Sondid ilmuvad nimekirja kohe, kui baas neist rääkima hakkab — " +
                    "võta sond laadijast välja ja oota hetk.",
            )

            Section("Sondi nimi")
            Body(
                "Ava sond nimekirjast ja vajuta selle nimele. Nimi jääb püsima ka pärast " +
                    "äpi sulgemist.",
            )

            Section("Küpsetamine")
            Body(
                "Vajuta sondil, siis kastil «Valitud temperatuur». Vali liha ja seejärel " +
                    "valmimisaste. Kohe pärast valikut käivituvad stopper, alarm ja " +
                    "salvestamine — eraldi käivitusnuppu ei ole.\n\n" +
                    "«Lõpeta» peatab stopperi, vaigistab alarmi ja sulgeb ajaloo kirje.",
            )

            Section("Sihi muutmine keset küpsetust")
            Body(
                "Vali lihtsalt uus valmimisaste. Stopper alustab nullist, aga ajaloos jääb " +
                    "see üheks küpsetuseks — kõver jookseb edasi, ainult sihtjoon liigub.",
            )

            Section("Omad temperatuurid")
            Body(
                "Liha valiku lõpus on «Lisa oma temperatuur». Anna nimi ja kraadid " +
                    "(alati Celsiuses — kuvamisühiku vahetamine ei muuda salvestatut). " +
                    "Kirje jääb nimekirja alles.\n\n" +
                    "Kustutamiseks vajuta kirje juures prügikastile. Töötab ka pikk " +
                    "vajutus kirjel endal.",
            )

            Section("Alarm")
            Body(
                "Alarm mängib tsüklis äratuse helikanalil, kuni selle peatad — ühekordset " +
                    "piiksu ei ole, sest grillimüra sees jääks see kuulmata. Peatada saab " +
                    "«Lõpeta» nupuga, teavituse nupuga või uue sihi valimisega. Ise vaikib " +
                    "ta viie minuti pärast.\n\n" +
                    "«Peata alarm» ja «Lõpeta» ei ole sama asi. «Peata alarm» vaigistab " +
                    "ainult heli — stopper jookseb ja graafik salvestub edasi, mis sobib " +
                    "siis, kui tahad liha veel veidi ahjus hoida. «Lõpeta» paneb küpsetuse " +
                    "kinni.\n\n" +
                    "5 °C enne sihti tuleb vaikne eelhoiatus.\n\n" +
                    "Helina saad valida menüüst. Kuuldavust tasub enne küpsetust kontrollida " +
                    "menüü «Testi alarmi» nupuga — alarm järgib telefoni ÄRATUSE " +
                    "helitugevust, mitte meediaoma.",
            )

            Section("Pikk küpsetus")
            Body(
                "Kui äpp hoiatab aku optimeerimise pärast, luba tal taustal töötada. " +
                    "Ilma selleta võib Android mitmetunnise küpsetuse ajal ühenduse maha " +
                    "võtta ja alarm jääb tulemata.",
            )

            Section("Ohutus")
            Body(
                "Valmimisastme juures olev märge «Toiduohutuse alammäär» tähendab, et " +
                    "madalamale ei tohiks minna. Märge «⚠ alla ohutu miinimumi» tähendab, " +
                    "et tegu on maitse-eelistusega, mis jääb ametlikust soovitusest " +
                    "allapoole — terviklihal tavapärane, hakklihal ja linnulihal mitte.",
            )

            Section("Ajalugu")
            Body(
                "Iga küpsetus salvestub koos kõveraga. Ajaloo leiad menüüst. " +
                    "Alla kahe mõõtepunktiga küpsetusi ei salvestata.",
            )

            Section("Demo režiim")
            Body(
                "Kui baasi ei leita, ilmuvad umbes 12 sekundi pärast nupud «Proovi " +
                    "uuesti» ja «Demo». Demo annab virtuaalse sondi, millega saab kogu " +
                    "äppi läbi katsuda ilma riistvarata.\n\n" +
                    "Demo sond seisab toatemperatuuril, kuni valid sihi — siis hakkab " +
                    "soojenema ja «Lõpeta» viib ta tagasi algusesse. Nupp «+10 °C» " +
                    "kiirendab, et alarmi ootamine ei võtaks minuteid. Demo küpsetused " +
                    "on ajaloos märkega «DEMO».",
            )

            Section("Mida see andur ei oska")
            Body(
                "Mõõtevahemik algab nullist — sügavkülmas jääb näit 0 °C peale kinni ja " +
                    "miinuskraade seade ei raporteeri. Tegu on lihatermomeetriga, mitte " +
                    "külmiku omaga.",
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Body(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
