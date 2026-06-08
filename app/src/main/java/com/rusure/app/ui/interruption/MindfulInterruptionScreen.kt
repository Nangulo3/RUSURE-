package com.rusure.app.ui.interruption

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
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

@Composable
fun MindfulInterruptionScreen(
    viewModel: InterruptionViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    MindfulInterruptionContent(
        uiState = uiState,
        onContinue = viewModel::onContinue,
        onLeave = viewModel::onLeave,
        modifier = modifier
    )
}

@Composable
fun MindfulInterruptionContent(
    uiState: InterruptionUiState,
    onContinue: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        // Scrim semitransparente: oscurece y, combinado con el blur de ventana, deja el
        // contenido de la app de fondo difuminado e ilegible sin tapar del todo el efecto.
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    text = when (uiState.mode) {
                        GateMode.INITIAL -> "Una pausa antes de entrar"
                        GateMode.RE_ENTRY -> "Llevas un buen rato aquí"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = uiState.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.85f),
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
                    color = Color.White.copy(alpha = 0.8f),
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
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No quiero continuar a la app",
                        color = Color.White
                    )
                }
            }
        }
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
