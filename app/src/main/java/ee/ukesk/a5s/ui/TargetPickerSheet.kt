package ee.ukesk.a5s.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ee.ukesk.a5s.data.CookTarget
import ee.ukesk.a5s.data.Meat
import ee.ukesk.a5s.data.MeatTargets
import ee.ukesk.a5s.data.Settings
import ee.ukesk.a5s.data.TargetKind
import ee.ukesk.a5s.data.db.AppDatabase
import ee.ukesk.a5s.data.db.CustomTargetEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Kaheastmeline valik: kõigepealt liha, siis valmimisaste. Aste valides sulgub
 * leht ja siht läheb kohe käiku.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TargetPickerSheet(
    onDismiss: () -> Unit,
    onPicked: (CookTarget) -> Unit,
) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).deviceDao() }
    val scope = rememberCoroutineScope()
    val unit by Settings.unit.collectAsStateWithLifecycle()
    val customTargets by dao.observeCustomTargets()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedMeat by remember { mutableStateOf<Meat?>(null) }
    var showCustomDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .padding(horizontal = 20.dp),
        ) {
            val meat = selectedMeat
            if (meat == null) {
                Text(
                    text = "Mida küpsetad?",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                LazyColumn {
                    items(MeatTargets.all, key = { it.name }) { item ->
                        PickerRow(
                            emoji = item.emoji,
                            title = item.name,
                            onClick = { selectedMeat = item },
                        )
                    }

                    item {
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        Text(
                            text = "Omad temperatuurid",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }

                    items(customTargets, key = { "custom-${it.id}" }) { custom ->
                        PickerRow(
                            emoji = "⭐",
                            title = custom.name,
                            subtitle = formatTemp(custom.celsius.toDouble(), unit),
                            onClick = {
                                onPicked(
                                    CookTarget(
                                        meat = custom.name,
                                        doneness = "Oma seadistus",
                                        celsius = custom.celsius,
                                        kind = TargetKind.PREFERENCE,
                                    ),
                                )
                            },
                            onDelete = {
                                scope.launch(Dispatchers.IO) { dao.deleteCustomTarget(custom.id) }
                            },
                        )
                    }

                    item {
                        PickerRow(
                            emoji = "➕",
                            title = "Lisa oma temperatuur",
                            onClick = { showCustomDialog = true },
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selectedMeat = null }) { Text("←") }
                    Text(
                        text = "${meat.emoji}  ${meat.name}",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }

                meat.note?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                LazyColumn {
                    items(meat.donenessOptions, key = { it.label }) { option ->
                        DonenessRow(
                            label = option.label,
                            temperature = formatTemp(option.celsius.toDouble(), unit),
                            kind = option.kind,
                            onClick = {
                                onPicked(
                                    CookTarget(
                                        meat = meat.name,
                                        doneness = option.label,
                                        celsius = option.celsius,
                                        kind = option.kind,
                                    ),
                                )
                            },
                        )
                    }

                    // Selgitus üks kord kõigi kohta, mitte iga rea juures uuesti.
                    if (meat.donenessOptions.any { it.kind == TargetKind.BELOW_SAFE_MINIMUM }) {
                        item {
                            Text(
                                text = "⚠  " + MeatTargets.warningFor(
                                    TargetKind.BELOW_SAFE_MINIMUM,
                                ).orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    if (showCustomDialog) {
        CustomTargetDialog(
            onDismiss = { showCustomDialog = false },
            onSave = { name, celsius ->
                showCustomDialog = false
                scope.launch(Dispatchers.IO) {
                    dao.insertCustomTarget(
                        CustomTargetEntity(
                            name = name,
                            celsius = celsius,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerRow(
    emoji: String,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                // Pikk vajutus jääb alles neile, kes on selle juba selgeks saanud,
                // aga nähtav nupp on see, mis funktsiooni üldse leitavaks teeb.
                if (onDelete != null) {
                    Modifier.combinedClickableCompat(onClick = onClick, onLongClick = onDelete)
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            )
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = emoji, fontSize = 24.sp, modifier = Modifier.padding(end = 16.dp))
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        onDelete?.let {
            TextButton(onClick = it) {
                Text(text = "🗑", fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun DonenessRow(
    label: String,
    temperature: String,
    kind: TargetKind,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = temperature,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            when (kind) {
                TargetKind.SAFETY -> Text(
                    text = "Toiduohutuse alammäär",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TargetKind.BELOW_SAFE_MINIMUM -> Text(
                    text = "⚠  alla ohutu miinimumi",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TargetKind.PREFERENCE -> Unit
            }
        }
    }
}

@Composable
private fun CustomTargetDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, celsius: Int) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var degrees by remember { mutableStateOf("") }

    val parsed = degrees.toIntOrNull()
    val valid = name.isNotBlank() && parsed != null && parsed in 1..250

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Oma temperatuur") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nimi") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = degrees,
                    onValueChange = { input -> degrees = input.filter { it.isDigit() }.take(3) },
                    label = { Text("Temperatuur °C") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Sisesta alati Celsiuses — kuvamisühik ei muuda salvestatud väärtust.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let { onSave(name.trim(), it) } },
                enabled = valid,
            ) { Text("Salvesta") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Loobu") }
        },
    )
}

/** Pikk vajutus kustutab oma kirje. combinedClickable on eraldi välja toodud,
 *  et opt-in annotatsioon ei laiali valguks. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
): Modifier = this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

/** Ühine vorming, et kuvamisühik oleks kõikjal sama. */
fun formatTemp(celsius: Double, unit: ee.ukesk.a5s.data.TempUnit): String {
    val value = unit.from(celsius)
    return "${(value * 10).roundToInt() / 10.0} ${unit.suffix}"
}
