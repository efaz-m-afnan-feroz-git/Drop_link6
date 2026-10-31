package app.droplink.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.droplink.R
import app.droplink.di.AppContainer
import app.droplink.net.LinkProblem
import app.droplink.transfer.ConnectionMode
import app.droplink.transfer.LinkState
import app.droplink.ui.components.Asks
import app.droplink.ui.components.InfoCard
import app.droplink.ui.components.PermissionAsk
import app.droplink.ui.components.PermissionFlow
import app.droplink.ui.components.Pulse
import app.droplink.ui.components.label
import app.droplink.ui.components.text

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(container: AppContainer, permissions: PermissionFlow, onBack: () -> Unit, onSetup: () -> Unit) {
    val context = LocalContext.current
    val receive = container.receive
    val status by receive.status.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()

    // Nearby's Bluetooth/Wi-Fi permissions only matter on phones with Google Play services.
    val asks: List<PermissionAsk> = buildList {
        add(Asks.notifications)
        if (container.nearbyAvailable && settings.forcedMode != ConnectionMode.LOCALSEND) {
            add(Asks.nearby)
            add(Asks.locationSwitch)
        }
        add(Asks.legacyStorage)
    }

    // Ask for what receiving needs, then start (LocalSend works even if the Nearby permissions are refused).
    LaunchedEffect(Unit) {
        if (!receive.status.value.active) permissions.run(context, asks) { receive.start() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.receive_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.receive_visible_as), style = MaterialTheme.typography.labelLarge)
            Text(settings.deviceName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)

            if (!status.active) {
                Text(stringResource(R.string.receive_off), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { permissions.run(context, asks) { receive.start() } }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.receive_start))
                }
                return@Column
            }

            Pulse(Modifier.size(200.dp), stringResource(R.string.receive_waiting))
            Text(stringResource(R.string.receive_waiting), style = MaterialTheme.typography.titleMedium)

            // How senders can reach this phone
            if (settings.forcedMode != ConnectionMode.LOCALSEND) {
                StatusRow(
                    ConnectionMode.NEARBY.label(),
                    status.nearby,
                    status.nearbyProblem?.text() ?: stringResource(R.string.receive_nearby_ready),
                ) {
                    val p = status.nearbyProblem
                    if (status.nearby == LinkState.FAILED && p != null && p != LinkProblem.NEARBY_UNAVAILABLE) {
                        TextButton(onClick = { permissions.run(context, listOf(Asks.nearby, Asks.locationSwitch)) { receive.retryNearby() } }) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
            }
            if (settings.forcedMode != ConnectionMode.NEARBY) {
                StatusRow(
                    ConnectionMode.LOCALSEND.label(),
                    status.localSend,
                    when {
                        status.localSendProblem != null -> status.localSendProblem!!.text()
                        status.ip != null -> stringResource(R.string.receive_localsend_ready, status.ip!!)
                        else -> null
                    },
                )
            }

            InfoCard(Icons.Outlined.WifiTethering, stringResource(R.string.receive_tip_title), stringResource(R.string.receive_tip_body))
            TextButton(onClick = onSetup) { Text(stringResource(R.string.receive_setup_link)) }
            OutlinedButton(onClick = { receive.stop(); onBack() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.receive_stop))
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, state: LinkState, detail: String?, action: @Composable () -> Unit = {}) {
    val (icon, text) = when (state) {
        LinkState.ON -> Icons.Outlined.CheckCircle to (detail ?: stringResource(R.string.state_ready))
        LinkState.STARTING -> Icons.Outlined.HourglassTop to stringResource(R.string.state_starting)
        LinkState.FAILED -> Icons.Outlined.ErrorOutline to (detail ?: stringResource(R.string.state_unavailable))
        LinkState.OFF -> Icons.Outlined.HourglassTop to (detail ?: stringResource(R.string.state_off))
    }
    ListItem(
        leadingContent = { Icon(icon, null, tint = if (state == LinkState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(label) },
        supportingContent = {
            Column {
                Text(text)
                action()
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
