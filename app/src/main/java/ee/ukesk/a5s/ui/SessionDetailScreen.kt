package ee.ukesk.a5s.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ee.ukesk.a5s.data.MeatTargets
import ee.ukesk.a5s.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SessionDetailScreen(
    sessionId: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).cookDao() }
    val scope = rememberCoroutineScope()

    val unit by ee.ukesk.a5s.data.Settings.unit.collectAsStateWithLifecycle()
    val session by dao.observeSession(sessionId).collectAsStateWithLifecycle(initialValue = null)
    val samples by dao.observeSamples(sessionId).collectAsStateWithLifecycle(initialValue = emptyList())

    var confirmDelete by remember { mutableStateOf(false) }

    // Room emiteerib esimese väärtuse alles hetk pärast kompositsiooni, seega
    // on session alguses alati null. Ilma selle liputa hüppaks ekraan kohe
    // avanemisel tagasi. Läheme tagasi ainult siis, kui kirje päriselt kadus.
    var everLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(session) {
        if (session != null) everLoaded = true
        else if (everLoaded) onBack()
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack) { Text("← Tagasi") }

            session?.let { s ->
                Text(
                    text = s.meat
                        ?.let { "${MeatTargets.emojiFor(it)}  $it" }
                        ?: "Nimetu küpsetus",
                    style = MaterialTheme.typography.headlineSmall,
                )
                s.doneness?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = formatTimestamp(s.startedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        TemperatureChart(
                            samples = samples,
                            targetCelsius = s.targetCelsius,
                            unit = unit,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        StatRow("Punkte salvestatud", "${samples.size}")
                        s.endedAt?.let {
                            StatRow("Kestus", formatDuration(it - s.startedAt))
                        } ?: StatRow("Kestus", "käib praegu")
                        s.peakCelsius?.let {
                            StatRow("Kõrgeim temperatuur", formatTemp(it, unit))
                        }
                        s.targetCelsius?.let {
                            StatRow("Siht", formatTemp(it.toDouble(), unit))
                        }
                    }
                }

                OutlinedButton(onClick = { confirmDelete = true }) {
                    Text("Kustuta see küpsetus")
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Kustutada?") },
            text = { Text("Küpsetus ja kõik selle mõõtmispunktid kustutatakse jäädavalt.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch(Dispatchers.IO) { dao.deleteSession(sessionId) }
                    onBack()
                }) { Text("Kustuta") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Loobu") }
            },
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
