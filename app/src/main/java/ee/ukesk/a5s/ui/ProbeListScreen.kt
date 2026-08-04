package ee.ukesk.a5s.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ee.ukesk.a5s.ble.ConnectionState
import ee.ukesk.a5s.ble.ThermometerRepository
import ee.ukesk.a5s.ble.ThermometerService
import ee.ukesk.a5s.data.MeatTargets
import ee.ukesk.a5s.data.Settings
import ee.ukesk.a5s.data.db.AppDatabase
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProbeListScreen(
    onOpenMenu: () -> Unit,
    onAddDevice: () -> Unit,
    onOpenProbe: (String) -> Unit,
) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).deviceDao() }

    val state by ThermometerRepository.state.collectAsStateWithLifecycle()
    val unit by Settings.unit.collectAsStateWithLifecycle()
    val knownProbes by dao.observeProbes().collectAsStateWithLifecycle(initialValue = emptyList())

    // Registris olevad sondid pluss need, kes hetkel andmeid saadavad, aga
    // registrisse veel ei kuulu — demo sond ja äsja avastatud sond.
    val rows = remember(knownProbes, state.probes) {
        val known = knownProbes.map { it.address to it.name }
        val extra = state.probeList
            .filterNot { probe -> knownProbes.any { it.address == probe.address } }
            .map { it.address to it.displayName }
        known + extra
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Andurid") },
                navigationIcon = {
                    TextButton(onClick = onOpenMenu) {
                        Text("☰", fontSize = 22.sp)
                    }
                },
                actions = {
                    TextButton(onClick = onAddDevice) {
                        Text("＋", fontSize = 26.sp)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = connectionLabel(state.connection, state.connectedBases.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            state.lastError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            // Alarmi peab saama peatada ka äpist, mitte ainult teavitusest.
            // Vana ühe-ekraani versioonis oli see olemas; UI ümberkirjutusel
            // jäi taastamata ja "Testi alarmi" puhul ei olnud ühtegi väljapääsu.
            if (state.alarmSounding) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "Alarm heliseb",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = "Vaigistab ainult heli. Stopper ja salvestamine " +
                                "jäävad käima — küpsetuse lõpetab «Lõpeta».",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { ThermometerService.silenceAlarm(context) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Peata alarm") }
                    }
                }
            }

            if (state.demoMode) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "Demo režiim",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = "Andmed tulevad simulaatorist, mitte päris termomeetrilt. " +
                                "Sond püsib toatemperatuuril, kuni valid sihi — siis " +
                                "hakkab soojenema nagu liha ahjus.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { ThermometerService.demoBoost(context) },
                            ) { Text("+10 °C") }
                            OutlinedButton(
                                onClick = { ThermometerService.stopDemo(context) },
                            ) { Text("Lõpeta demo") }
                        }
                    }
                }
            }

            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Ühtegi sondi pole veel nähtud.\n" +
                            "Võta sond laadijast välja — ta ilmub siia ise.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(rows, key = { it.first }) { (address, name) ->
                        val live = state.probes[address]
                        ProbeRow(
                            name = name,
                            temperatureText = live?.let {
                                "${(unit.from(it.celsius) * 10).roundToInt() / 10.0}"
                            },
                            unitSuffix = unit.suffix,
                            batteryPercent = live?.batteryPercent,
                            targetLine = live?.target?.let { target ->
                                "${MeatTargets.emojiFor(target.meat)}  ${target.meat} — " +
                                    "${target.doneness}"
                            },
                            reachedTarget = live?.alarmFired == true,
                            onClick = { onOpenProbe(address) },
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ProbeRow(
    name: String,
    temperatureText: String?,
    unitSuffix: String,
    batteryPercent: Int?,
    targetLine: String?,
    reachedTarget: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = name, style = MaterialTheme.typography.titleMedium)
                targetLine?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                batteryPercent?.let {
                    Text(
                        text = "aku ~$it%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } ?: Text(
                    text = "ei ole ühenduses",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = temperatureText ?: "—",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (reachedTarget) {
                        TargetReachedGreen
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = unitSuffix,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
            }
        }
    }
}

private fun connectionLabel(connection: ConnectionState, connectedBases: Int): String =
    when (connection) {
        ConnectionState.STOPPED -> "Ühendus puudub"
        ConnectionState.SCANNING -> "Otsin…"
        ConnectionState.CONNECTING -> "Ühendan…"
        ConnectionState.CONNECTED ->
            if (connectedBases > 1) "Ühendatud · $connectedBases baasi" else "Ühendatud"
        ConnectionState.RECONNECTING -> "Ühendus katkes, proovin uuesti…"
        ConnectionState.BLUETOOTH_OFF -> "Bluetooth on välja lülitatud"
    }
