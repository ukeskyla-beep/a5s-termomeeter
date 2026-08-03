package ee.ukesk.a5s.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import ee.ukesk.a5s.data.TempUnit
import ee.ukesk.a5s.data.db.SampleEntity
import kotlin.math.max

/**
 * Küpsetuskõver. Teadlikult ilma graafikuteegita — üks joon ja üks sihtjoon
 * ei ole väärt sõltuvust, mille peab iga Compose'i versiooniuuendusega üle vaatama.
 */
@Composable
fun TemperatureChart(
    samples: List<SampleEntity>,
    targetCelsius: Int?,
    unit: TempUnit,
    modifier: Modifier = Modifier,
) {
    if (samples.size < 2) {
        Box(modifier.height(220.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "Liiga vähe punkte graafiku joonistamiseks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val lineColor = MaterialTheme.colorScheme.primary
    val targetColor = MaterialTheme.colorScheme.error

    val firstAt = samples.first().at
    val lastAt = samples.last().at
    val spanMs = max(lastAt - firstAt, 1L)

    val temps = samples.map { it.celsius }
    var lowC = temps.min()
    var highC = temps.max()
    targetCelsius?.let { highC = max(highC, it.toDouble()) }

    val headroom = ((highC - lowC) * 0.12).coerceAtLeast(2.0)
    lowC -= headroom
    highC += headroom
    val rangeC = max(highC - lowC, 0.1)

    Column(modifier) {
        // Kestust siin ei näita — statistikakaardil on see olemas ja need kaks
        // arvu ei kattu (mõõtepunktide vahemik vs seansi kestus).
        Text(
            text = "kõrgeim ${formatTemp(temps.max(), unit)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .padding(vertical = 8.dp),
        ) {
            val w = size.width
            val h = size.height

            fun px(at: Long): Float = (at - firstAt).toFloat() / spanMs.toFloat() * w
            fun py(c: Double): Float = (1.0 - (c - lowC) / rangeC).toFloat() * h

            targetCelsius?.let { target ->
                val y = py(target.toDouble())
                drawLine(
                    color = targetColor,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f)),
                )
            }

            val path = Path()
            samples.forEachIndexed { index, sample ->
                val x = px(sample.at)
                val y = py(sample.celsius)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(
                    width = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        ) {
            Text(
                text = "madalaim ${formatTemp(temps.min(), unit)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            targetCelsius?.let {
                Text(
                    text = "punktiir = siht ${formatTemp(it.toDouble(), unit)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

fun formatDuration(millis: Long): String {
    val totalMinutes = millis / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "${hours} h ${minutes} min"
        totalMinutes > 0 -> "${minutes} min"
        else -> "alla minuti"
    }
}
