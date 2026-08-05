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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
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

/**
 * Hoiab avatud vaate alles ka siis, kui Android protsessi taustal maha võtab.
 * Manifesti `configChanges` katab pööramise; see katab protsessi surma.
 */
private val ScreenSaver = listSaver<Screen, Any>(
    save = { screen ->
        when (screen) {
            Screen.Home -> listOf(KEY_HOME)
            Screen.AddDevice -> listOf(KEY_ADD_DEVICE)
            Screen.History -> listOf(KEY_HISTORY)
            Screen.Help -> listOf(KEY_HELP)
            is Screen.Probe -> listOf(KEY_PROBE, screen.address)
            is Screen.CookDetail -> listOf(KEY_COOK_DETAIL, screen.sessionId)
        }
    },
    restore = { saved ->
        when (saved.firstOrNull()) {
            KEY_ADD_DEVICE -> Screen.AddDevice
            KEY_HISTORY -> Screen.History
            KEY_HELP -> Screen.Help
            KEY_PROBE -> (saved.getOrNull(1) as? String)?.let { Screen.Probe(it) }
            KEY_COOK_DETAIL -> (saved.getOrNull(1) as? Long)?.let { Screen.CookDetail(it) }
            else -> null
        } ?: Screen.Home
    },
)

private const val KEY_HOME = "home"
private const val KEY_ADD_DEVICE = "add_device"
private const val KEY_HISTORY = "history"
private const val KEY_HELP = "help"
private const val KEY_PROBE = "probe"
private const val KEY_COOK_DETAIL = "cook_detail"

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

    var screen by rememberSaveable(stateSaver = ScreenSaver) {
        mutableStateOf<Screen>(Screen.Home)
    }
    // Baasita saab edasi minna — andurite nimekirjas ootab demo sond, millega
    // saab kogu äppi läbi katsuda. Peab olema saveable, muidu viskaks äpp
    // baasita kasutaja iga taasloomise järel uuesti tervitusekraanile.
    var skippedPairing by rememberSaveable { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Load ja ühendus kohe käivitumisel — eraldi "Ühenda" nuppu ei ole.
    // Teenuse käivitab onRequestPermissions alles siis, kui load on käes.
    LaunchedEffect(Unit) { onRequestPermissions() }

    val knownBases = bases
    if (knownBases == null) return // andmebaas veel laeb

    if (knownBases.isEmpty() && !skippedPairing) {
        PairingScreen(
            isFirstRun = true,
            onDone = {
                skippedPairing = true
                screen = Screen.Home
            },
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
