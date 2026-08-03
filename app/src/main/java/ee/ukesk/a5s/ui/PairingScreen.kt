package ee.ukesk.a5s.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ee.ukesk.a5s.ble.DiscoveredBase
import ee.ukesk.a5s.ble.ThermometerRepository
import ee.ukesk.a5s.ble.ThermometerService
import ee.ukesk.a5s.data.db.AppDatabase
import ee.ukesk.a5s.data.db.KnownBaseEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Paaritamine. Telefon ühendub baasiga (puidust pesa) — sondid ilmuvad ise
 * nähtavale kohe, kui baas neist rääkima hakkab.
 */
@Composable
fun PairingScreen(
    isFirstRun: Boolean,
    onDone: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).deviceDao() }
    val scope = rememberCoroutineScope()

    val state by ThermometerRepository.state.collectAsStateWithLifecycle()
    val knownBases by dao.observeBases().collectAsStateWithLifecycle(initialValue = emptyList())
    val knownAddresses = knownBases.map { it.address }.toSet()

    var naming by remember { mutableStateOf<DiscoveredBase?>(null) }

    // Otsime pidevalt, kuni sellel lehel ollakse. See katab ühtlasi juhtumi, kus
    // Bluetoothi luba anti alles pärast lehe avanemist või kus 30-sekundiline
    // skaneerimisaken sai täis.
    LaunchedEffect(Unit) {
        while (true) {
            if (!ThermometerRepository.state.value.scanningForBases) {
                ThermometerService.scanForBases(context)
            }
            kotlinx.coroutines.delay(4000)
        }
    }
    DisposableEffect(Unit) {
        onDispose { ThermometerService.stopScan(context) }
    }

    // Kui esimesel korral baas lisandub, liigume ise edasi.
    LaunchedEffect(knownBases.size) {
        if (isFirstRun && knownBases.isNotEmpty()) onDone()
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
        ) {
            onBack?.let {
                TextButton(onClick = it) { Text("←  Tagasi") }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = if (isFirstRun) "Tere tulemast" else "Lisa baas",
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Lülita A5S baas sisse ja hoia see telefoni lähedal. " +
                    "Telefon ühendub baasiga; sondid ilmuvad pärast seda ise nähtavale.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.scanningForBases) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.padding(horizontal = 6.dp))
                    Text(
                        text = "Otsin…",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    OutlinedButton(onClick = { ThermometerService.scanForBases(context) }) {
                        Text("Otsi uuesti")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            val candidates = state.discoveredBases.filterNot { it.address in knownAddresses }
            if (candidates.isEmpty()) {
                Text(
                    text = if (state.scanningForBases) {
                        "Ühtegi uut baasi pole veel leitud."
                    } else {
                        "Baasi ei leitud. Kontrolli, kas see on sisse lülitatud."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(candidates, key = { it.address }) { base ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { naming = base },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    text = base.advertisedName,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = "${base.address}  ·  signaal ${base.rssi} dBm",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (knownBases.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "Juba lisatud",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                knownBases.forEach { base ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(base.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = base.address,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            scope.launch(Dispatchers.IO) { dao.deleteBase(base.address) }
                            ThermometerService.refreshDevices(context)
                        }) { Text("Eemalda") }
                    }
                }
            }
        }
    }

    naming?.let { base ->
        NameBaseDialog(
            defaultName = "Grill",
            onDismiss = { naming = null },
            onSave = { name ->
                naming = null
                scope.launch(Dispatchers.IO) {
                    dao.upsertBase(
                        KnownBaseEntity(
                            address = base.address,
                            name = name,
                            addedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                ThermometerService.refreshDevices(context)
                if (!isFirstRun) onDone()
            },
        )
    }
}

@Composable
private fun NameBaseDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Anna baasile nimi") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Nimi") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Sondidele saad nime anda eraldi, kui nad nähtavale ilmuvad.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Lisa") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Loobu") } },
    )
}
