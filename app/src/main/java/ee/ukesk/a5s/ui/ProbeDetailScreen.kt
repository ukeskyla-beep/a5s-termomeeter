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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ee.ukesk.a5s.ble.ThermometerRepository
import ee.ukesk.a5s.ble.ThermometerService
import ee.ukesk.a5s.data.MeatTargets
import ee.ukesk.a5s.data.Settings
import ee.ukesk.a5s.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ProbeDetailScreen(
    address: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).deviceDao() }
    val scope = rememberCoroutineScope()

    val state by ThermometerRepository.state.collectAsStateWithLifecycle()
    val unit by Settings.unit.collectAsStateWithLifecycle()
    val probe = state.probes[address]

    var pickerOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var targetBelowCurrent by remember { mutableStateOf<ee.ukesk.a5s.data.CookTarget?>(null) }

    // Kell tiksub sekundis. Stopperi aeg arvutatakse alati timerStartedAt'ist,
    // seega ei lähe see paigast ka siis, kui äpp vahepeal taustal oli.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val readingIsStale = probe != null && now - probe.lastUpdateAt > 30_000L

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("←  Tagasi") }
            }

            if (probe == null) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Sondilt ei tule hetkel andmeid.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            Text(
                text = probe.displayName,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.clickable { renameOpen = true },
            )

            if (state.alarmSounding) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
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
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { ThermometerService.silenceAlarm(context) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Peata alarm") }
                    }
                }
            }

            // ----------------------------------------------------- praegune näit
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "${(unit.from(probe.celsius) * 10).roundToInt() / 10.0}",
                        fontSize = 88.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(text = unit.suffix, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "aku ~${probe.batteryPercent}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (readingIsStale) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "⚠  näit on vana — andmeid pole " +
                                "${(now - probe.lastUpdateAt) / 1000} s",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // -------------------------------------------------------- valitud siht
            Card(
                modifier = Modifier.fillMaxWidth().clickable { pickerOpen = true },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Valitud temperatuur",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))

                    val target = probe.target
                    if (target == null) {
                        Text(
                            text = "—",
                            fontSize = 56.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Vajuta ja vali liha",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(
                            text = "${(unit.from(target.celsius.toDouble()) * 10).roundToInt() / 10.0}",
                            fontSize = 56.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(text = unit.suffix, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${MeatTargets.emojiFor(target.meat)}  ${target.meat}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = target.doneness,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ------------------------------------------------------------- käik
            if (probe.target != null) {
                val target = probe.target
                val progress = (probe.celsius / target.celsius).coerceIn(0.0, 1.0).toFloat()

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = if (probe.alarmFired) {
                        TargetReachedGreen
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    gapSize = 0.dp,
                    drawStopIndicator = { },
                )

                val elapsed = probe.timerStartedAt?.let { now - it } ?: 0L
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatStopwatch(elapsed),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    val remaining = (target.celsius - probe.celsius).roundToInt()
                    Text(
                        text = if (probe.alarmFired || remaining <= 0) {
                            "Valmis"
                        } else {
                            "veel $remaining °C"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (probe.alarmFired) {
                            TargetReachedGreen
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                Button(
                    onClick = { ThermometerRepository.finishCook(address) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) { Text("Lõpeta") }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (pickerOpen) {
        TargetPickerSheet(
            onDismiss = { pickerOpen = false },
            onPicked = { target ->
                pickerOpen = false
                val current = probe?.celsius
                // Sihist kõrgem algtemperatuur tähendab tavaliselt eksitust:
                // küpsetus oleks kohe "valmis" ja alarm läheks silmapilk lahti.
                if (current != null && current >= target.celsius) {
                    targetBelowCurrent = target
                } else {
                    ThermometerRepository.setTarget(address, target)
                }
            },
        )
    }

    targetBelowCurrent?.let { target ->
        val current = probe?.celsius ?: 0.0
        AlertDialog(
            onDismissRequest = { targetBelowCurrent = null },
            title = { Text("Siht on juba käes") },
            text = {
                Text(
                    "Sond näitab ${(unit.from(current) * 10).roundToInt() / 10.0} ${unit.suffix}, " +
                        "mis on sihist (${(unit.from(target.celsius.toDouble()) * 10).roundToInt() / 10.0} " +
                        "${unit.suffix}) juba kõrgem. Küpsetus loetakse kohe valmis ja alarm " +
                        "käivitub silmapilk.\n\nKas valisid õige valmimisastme?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ThermometerRepository.setTarget(address, target)
                    targetBelowCurrent = null
                }) { Text("Sea ikkagi") }
            },
            dismissButton = {
                TextButton(onClick = { targetBelowCurrent = null }) { Text("Vali uuesti") }
            },
        )
    }

    if (renameOpen && probe != null) {
        RenameDialog(
            current = probe.displayName,
            onDismiss = { renameOpen = false },
            onSave = { newName ->
                renameOpen = false
                scope.launch(Dispatchers.IO) { dao.renameProbe(address, newName) }
            },
        )
    }
}

@Composable
private fun RenameDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sondi nimi") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Salvesta") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Loobu") } },
    )
}

fun formatStopwatch(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
