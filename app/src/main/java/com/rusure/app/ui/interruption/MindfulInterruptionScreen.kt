package com.rusure.app.ui.interruption

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rusure.app.domain.gate.GateMode
import com.rusure.app.domain.model.UsageStats
import com.rusure.app.ui.util.formatDuration
import com.rusure.app.ui.util.formatTimestamp

/**
 * Texto a alto contraste para garantizar legibilidad sobre el fondo difuminado y el scrim,
 * cumpliendo las reglas de accesibilidad visual independientemente del contenido de la app.
 */
private val TextPrimary = Color.White
private val TextSecondary = Color.White.copy(alpha = 0.88f)

/**
 * Opacidad del overlay oscuro. Cuando el blur nativo está activo basta un velo algo más ligero;
 * sin blur (fallback pre-Android 12) se refuerza para ocultar el contenido de la app de fondo.
 */
private const val SCRIM_ALPHA_WITH_BLUR = 0.62f
private const val SCRIM_ALPHA_FALLBACK = 0.78f

@Composable
fun MindfulInterruptionScreen(
    viewModel: InterruptionViewModel,
    glassBlurActive: Boolean = true,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    MindfulInterruptionContent(
        uiState = uiState,
        onContinue = viewModel::onContinue,
        onLeave = viewModel::onLeave,
        glassBlurActive = glassBlurActive,
        modifier = modifier
    )
}

@Composable
fun MindfulInterruptionContent(
    uiState: InterruptionUiState,
    onContinue: () -> Unit,
    onLeave: () -> Unit,
    glassBlurActive: Boolean = true,
    modifier: Modifier = Modifier
) {
    val scrimAlpha = if (glassBlurActive) SCRIM_ALPHA_WITH_BLUR else SCRIM_ALPHA_FALLBACK
    Surface(
        modifier = modifier.fillMaxSize(),
        // Overlay oscuro: combinado con el blur de ventana produce el efecto frosted glass; sin
        // blur, su opacidad reforzada deja igualmente ilegible la app de fondo.
        color = MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            GlassPanel {
                Text(
                    text = when (uiState.mode) {
                        GateMode.INITIAL -> "Una pausa antes de entrar"
                        GateMode.RE_ENTRY -> "Llevas un buen rato aquí"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = uiState.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )

                CountdownRing(
                    remainingSeconds = uiState.remainingSeconds,
                    progress = uiState.progress
                )

                Text(
                    text = if (uiState.canContinue) {
                        "Puedes continuar si de verdad lo necesitas."
                    } else {
                        "Respira. El botón se activará al terminar la cuenta."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )

                StatsCard(stats = uiState.stats)

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = onContinue,
                    enabled = uiState.canContinue,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Continuar")
                }

                OutlinedButton(
                    onClick = onLeave,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                ) {
                    Text(text = "No quiero continuar a la app")
                }
            }
        }
    }
}

/**
 * Panel "frosted glass": superficie translúcida y oscura con borde sutil que se asienta sobre el
 * fondo difuminado. Su base semiopaca garantiza el alto contraste del temporizador y los textos
 * principales sea cual sea el contenido de la app subyacente.
 */
@Composable
private fun GlassPanel(content: @Composable (androidx.compose.foundation.layout.ColumnScope.() -> Unit)) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = Color.Black.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            content = content
        )
    }
}

@Composable
private fun CountdownRing(
    remainingSeconds: Int,
    progress: Float
) {
    Box(
        modifier = Modifier.size(160.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxSize(),
            strokeWidth = 8.dp,
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.White.copy(alpha = 0.25f)
        )
        Text(
            text = remainingSeconds.toString(),
            style = MaterialTheme.typography.displayMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun StatsCard(stats: UsageStats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.12f),
            contentColor = Color.White
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Últimas 24 horas",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            StatRow(label = "Aperturas", value = stats.openings.toString())
            StatRow(label = "Tiempo de uso", value = formatDuration(stats.totalActiveMillis))
            StatRow(label = "Interrupciones", value = stats.totalInterruptions.toString())
            StatRow(label = "Último uso", value = formatTimestamp(stats.lastUsedEpochMillis))
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}
