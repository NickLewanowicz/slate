package dev.slate.android

import android.app.Application
import androidx.work.Configuration
import dev.slate.android.di.AppContainer
import dev.slate.android.sync.SlateWorkerFactory
import kotlinx.coroutines.launch

class SlateApplication : Application(), Configuration.Provider {

    val container: AppContainer by lazy { AppContainer(this) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(SlateWorkerFactory(container))
            .build()

    override fun onCreate() {
        super.onCreate()
        // Arm the periodic sync on boot (WorkManager owns scheduling; updatePeriodMillis is 0).
        container.applicationScope.launch {
            val settings = container.settingsStore.current()
            if (settings.isConfigured) {
                container.syncScheduler.ensurePeriodic(settings.syncIntervalMinutes)
            }
        }
        // Wake WorkManager's factory initialization eagerly so background syncs can start.
        @Suppress("UNSAFE_EXPLICIT_WORKMANAGER_INIT")
        runCatching { androidx.work.WorkManager.getInstance(this) }
    }
}
