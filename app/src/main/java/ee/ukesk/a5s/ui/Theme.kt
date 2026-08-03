package ee.ukesk.a5s.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Söe ja sütt värvid. Vaikimisi lilla Material 3 skeem ei sobinud grilliäpile,
 * ja tume taust on grilli juures õhtul ka silmadele lahkem.
 */
private val Ember = Color(0xFFFF8F5A)
private val EmberDim = Color(0xFF9A3F16)
private val Charcoal = Color(0xFF151110)
private val CharcoalRaised = Color(0xFF241C19)
private val Ash = Color(0xFFD9C9C1)
private val AshDim = Color(0xFFB3A099)

private val A5sColors = darkColorScheme(
    primary = Ember,
    onPrimary = Color(0xFF3D1200),
    primaryContainer = EmberDim,
    onPrimaryContainer = Color(0xFFFFDBCB),

    secondary = Color(0xFFE0BFA8),
    onSecondary = Color(0xFF412D1E),
    secondaryContainer = Color(0xFF5A4433),
    onSecondaryContainer = Color(0xFFFFDCC4),

    background = Charcoal,
    onBackground = Ash,
    surface = Charcoal,
    onSurface = Ash,
    surfaceVariant = CharcoalRaised,
    onSurfaceVariant = AshDim,

    outline = Color(0xFF6E5B53),
    outlineVariant = Color(0xFF3F332E),

    error = Color(0xFFFF6E5E),
    onError = Color(0xFF4E0A05),
    errorContainer = Color(0xFF8B2318),
    onErrorContainer = Color(0xFFFFDAD4),
)

/** Roheline "siht käes" tähistamiseks — küps, mitte kilecirkus. */
val TargetReachedGreen = Color(0xFF86D18A)

@Composable
fun A5sTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = A5sColors,
        content = content,
    )
}
