package ee.ukesk.a5s.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
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
import ee.ukesk.a5s.ble.ThermometerService
import ee.ukesk.a5s.data.Settings
import ee.ukesk.a5s.data.TempUnit
import ee.ukesk.a5s.data.db.AppDatabase
import kotlinx.coroutines.launch

private sealed interface Screen {
    data object Home : Screen
    data object AddDevice : Screen
    data class Probe(val address: String) : Screen
    data object History : Screen
    data object Help : Screen
    data class CookDetail(val sessionId: Long) : Screen
}

@Composable
fun AppRoot(
    onRequestPermissions: () -> Unit,
    onPickAlarmSound: () -> Unit,
    alarmSoundLabel: String,
) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).deviceDao() }
    val scope = rememberCoroutineScope()

    val bases by dao.observeBases().collectAsStateWithLifecycle(initialValue = null)
    val unit by Settings.unit.collectAsStateWithLifecycle()

    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Load ja ühendus kohe käivitumisel — eraldi "Ühenda" nuppu ei ole.
    // Teenuse käivitab onRequestPermissions alles siis, kui load on käes.
    LaunchedEffect(Unit) { onRequestPermissions() }

    val knownBases = bases
    if (knownBases == null) return // andmebaas veel laeb

    if (knownBases.isEmpty()) {
        PairingScreen(
            isFirstRun = true,
            onDone = { screen = Screen.Home },
            onBack = null,
        )
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "A5S Termomeeter",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )

                    Text(
                        text = "Temperatuuri ühik",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        TempUnit.entries.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = unit == option,
                                onClick = { Settings.setUnit(option) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = TempUnit.entries.size,
                                ),
                            ) { Text(option.suffix) }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()

                    NavigationDrawerItem(
                        label = { Text("Ajalugu") },
                        selected = false,
                        onClick = {
                            screen = Screen.History
                            scope.launch { drawerState.close() }
                        },
                    )
                    NavigationDrawerItem(
                        label = { Text("Testi alarmi") },
                        selected = false,
                        onClick = {
                            ThermometerService.testAlarm(context)
                            scope.launch { drawerState.close() }
                        },
                    )
                    NavigationDrawerItem(
                        label = {
                            Column {
                                Text("Alarmi helin")
                                Text(
                                    text = alarmSoundLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        selected = false,
                        onClick = {
                            onPickAlarmSound()
                            scope.launch { drawerState.close() }
                        },
                    )
                    NavigationDrawerItem(
                        label = { Text("Baasid") },
                        selected = false,
                        onClick = {
                            screen = Screen.AddDevice
                            scope.launch { drawerState.close() }
                        },
                    )
                    NavigationDrawerItem(
                        label = { Text("Abi") },
                        selected = false,
                        onClick = {
                            screen = Screen.Help
                            scope.launch { drawerState.close() }
                        },
                    )
                }
            }
        },
    ) {
        when (val current = screen) {
            Screen.Home -> ProbeListScreen(
                onOpenMenu = { scope.launch { drawerState.open() } },
                onAddDevice = { screen = Screen.AddDevice },
                onOpenProbe = { screen = Screen.Probe(it) },
            )

            Screen.AddDevice -> {
                BackHandler { screen = Screen.Home }
                PairingScreen(
                    isFirstRun = false,
                    onDone = { screen = Screen.Home },
                    onBack = { screen = Screen.Home },
                )
            }

            is Screen.Probe -> {
                BackHandler { screen = Screen.Home }
                ProbeDetailScreen(
                    address = current.address,
                    onBack = { screen = Screen.Home },
                )
            }

            Screen.History -> {
                BackHandler { screen = Screen.Home }
                HistoryScreen(
                    onBack = { screen = Screen.Home },
                    onOpenSession = { screen = Screen.CookDetail(it) },
                )
            }

            Screen.Help -> {
                BackHandler { screen = Screen.Home }
                HelpScreen(onBack = { screen = Screen.Home })
            }

            is Screen.CookDetail -> {
                BackHandler { screen = Screen.History }
                SessionDetailScreen(
                    sessionId = current.sessionId,
                    onBack = { screen = Screen.History },
                )
            }
        }
    }
}
