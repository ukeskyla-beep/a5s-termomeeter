package ee.ukesk.a5s.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ee.ukesk.a5s.data.MeatTargets
import ee.ukesk.a5s.data.db.AppDatabase
import ee.ukesk.a5s.data.db.CookSessionEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val estonian = Locale.forLanguageTag("et")
private val dateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d. MMMM, HH:mm", estonian)

fun formatTimestamp(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dateFormat)

@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenSession: (Long) -> Unit,
) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).cookDao() }
    val unit by ee.ukesk.a5s.data.Settings.unit.collectAsStateWithLifecycle()
    val sessions by dao.observeSessions().collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
        ) {
            TextButton(onClick = onBack) { Text("← Tagasi") }

            Text(
                text = "Ajalugu",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Ühtegi küpsetust pole veel salvestatud.\n" +
                            "Sea sihttemperatuur — siis hakkab äpp käiku salvestama.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(sessions, key = { it.id }) { session ->
                        SessionCard(
                            session = session,
                            unit = unit,
                            onClick = { onOpenSession(session.id) },
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: CookSessionEntity,
    unit: ee.ukesk.a5s.data.TempUnit,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = session.meat
                    ?.let { "${MeatTargets.emojiFor(it)}  $it" }
                    ?: "Nimetu küpsetus",
                style = MaterialTheme.typography.titleMedium,
            )
            session.doneness?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = formatTimestamp(session.startedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val details = buildList {
                session.endedAt?.let { add(formatDuration(it - session.startedAt)) }
                session.peakCelsius?.let { add("tipp ${formatTemp(it, unit)}") }
                session.targetCelsius?.let { add("siht ${formatTemp(it.toDouble(), unit)}") }
            }
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
