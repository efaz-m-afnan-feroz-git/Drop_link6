package app.droplink.di

import android.app.Application
import app.droplink.core.security.IdentityStore
import app.droplink.data.AppDatabase
import app.droplink.data.SettingsRepository
import app.droplink.engine.LocalSendEngine
import app.droplink.engine.NearbyEngine
import app.droplink.service.ReceiveTileService
import app.droplink.storage.Sinks
import app.droplink.storage.SourceResolver
import app.droplink.transfer.HistoryRecorder
import app.droplink.transfer.ReceiveManager
import app.droplink.transfer.SendManager
import app.droplink.transfer.TransferTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import java.io.File

/** Manual dependency injection: one instance of everything, created with the Application. */
class AppContainer(private val application: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(application)
    val identity = IdentityStore(File(application.filesDir, "identity.p12"))
    val database: AppDatabase by lazy { AppDatabase.create(application) }
    val tracker = TransferTracker()
    val sources = SourceResolver(application)
    val history = HistoryRecorder(application, appScope) { database }

    /** Where received files go: the folder chosen in Settings, or Download/DropLink. */
    private val sinks = { Sinks.create(application, settings.current.saveTreeUri) }

    /** Google Nearby Connections (phones with Google Play services). */
    val nearby = NearbyEngine(application, sinks)

    /** LocalSend open protocol (every ROM, and the official LocalSend apps). */
    val localSend = LocalSendEngine(application, appScope, identity, settings, sinks)

    val receive = ReceiveManager(
        context = application,
        scope = appScope,
        settings = settings,
        db = { database },
        tracker = tracker,
        history = history,
        nearby = nearby,
        localSend = localSend,
    )

    val send = SendManager(
        context = application,
        scope = appScope,
        settings = settings,
        db = { database },
        tracker = tracker,
        history = history,
        nearby = nearby,
        localSend = localSend,
    )

    /** True when Google Play services (and so Nearby Connections) is usable on this phone. */
    val nearbyAvailable: Boolean get() = nearby.isAvailable

    init {
        // Generate the TLS identity early (first launch) so the first transfer doesn't wait for it.
        appScope.launch(Dispatchers.IO) { runCatching { identity.get() } }
        // Keep the Quick Settings tile in sync.
        appScope.launch {
            receive.status.distinctUntilChangedBy { it.active }.collect { ReceiveTileService.refresh(application) }
        }
    }
}
