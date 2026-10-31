package app.droplink.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.droplink.R
import app.droplink.transfer.ConnectionMode
import app.droplink.net.LinkProblem
import app.droplink.transfer.TransferError

@Composable
fun LinkProblem.text(): String = stringResource(
    when (this) {
        LinkProblem.BLUETOOTH_OFF -> R.string.err_bluetooth_off
        LinkProblem.NEARBY_UNAVAILABLE -> R.string.err_nearby_unavailable
        LinkProblem.NEARBY_FAILED -> R.string.err_nearby_failed
        LinkProblem.NO_NETWORK -> R.string.err_no_network
        LinkProblem.WIFI_OFF -> R.string.err_wifi_off
        LinkProblem.LOCATION_OFF -> R.string.err_location_off
        LinkProblem.PERMISSION_DENIED -> R.string.err_permission
    },
)

@Composable
fun TransferError.text(): String = when (this) {
    is TransferError.Link -> problem.text()
    TransferError.PeerCancelled -> stringResource(R.string.err_peer_cancelled)
    TransferError.NoAnswer -> stringResource(R.string.err_no_answer)
    TransferError.ConnectionLost -> stringResource(R.string.err_connection_lost)
    TransferError.CouldNotConnect -> stringResource(R.string.err_could_not_connect)
    TransferError.Busy -> stringResource(R.string.err_busy)
    TransferError.PinRequired -> stringResource(R.string.err_pin_required)
    is TransferError.Other -> stringResource(R.string.err_other, detail)
}

@Composable
fun ConnectionMode?.label(): String = stringResource(
    when (this) {
        ConnectionMode.NEARBY -> R.string.mode_nearby
        ConnectionMode.LOCALSEND -> R.string.mode_localsend
        null -> R.string.mode_auto
    },
)

/** Pulsing rings used while waiting for peers / files. */
@Composable
fun Pulse(modifier: Modifier = Modifier, description: String) {
    val color = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "pulse")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "t")
    Canvas(modifier.semantics { contentDescription = description }) {
        val center = Offset(size.width / 2, size.height / 2)
        val maxR = size.minDimension / 2
        for (i in 0 until 3) {
            val p = (t + i / 3f) % 1f
            drawCircle(color.copy(alpha = (1f - p) * 0.6f), radius = maxR * p, center = center, style = Stroke(width = 3.dp.toPx()))
        }
        drawCircle(color, radius = maxR * 0.12f, center = center)
    }
}

@Composable
fun InfoCard(icon: ImageVector, title: String, body: String?, modifier: Modifier = Modifier, tone: Tone = Tone.NEUTRAL, content: @Composable () -> Unit = {}) {
    val container = when (tone) {
        Tone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
        Tone.GOOD -> MaterialTheme.colorScheme.primaryContainer
        Tone.WARN -> MaterialTheme.colorScheme.tertiaryContainer
        Tone.BAD -> MaterialTheme.colorScheme.errorContainer
    }
    Surface(color = container, shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(top = 2.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium)
                content()
            }
        }
    }
}

enum class Tone { NEUTRAL, GOOD, WARN, BAD }

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
fun BigCode(code: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(
            code.toCharArray().joinToString(" "),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { contentDescription = "$label ${code.toCharArray().joinToString(" ")}" },
        )
    }
}

/** Copies the diagnostic log to the clipboard and offers to share it. */
fun copyDiagLog(context: android.content.Context) {
    val text = app.droplink.util.DiagLog.read()
    runCatching {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("DropLink log", text))
    }
    android.widget.Toast.makeText(context, context.getString(R.string.diag_copied), android.widget.Toast.LENGTH_SHORT).show()
    runCatching {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, text)
        context.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
