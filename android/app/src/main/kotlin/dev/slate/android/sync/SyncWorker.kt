package dev.slate.android.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.slate.android.api.ApiConfig
import dev.slate.android.api.SlateApi
import dev.slate.android.api.SlateApiException
import dev.slate.android.data.SettingsStore
import dev.slate.android.data.SlateRepository
import dev.slate.android.data.SyncReason
import dev.slate.android.interact.FlushOutcome
import dev.slate.android.interact.InteractionManager
import dev.slate.android.widget.WidgetUpdater
import java.io.IOException

/** Fired after every sync so receivers (widget, app shortcuts) can re-render from cache. */
const val ACTION_SLATE_SYNC_DONE = "dev.slate.android.SLATE_SYNC_DONE"

/** Worker input key for the sync reason. */
const val KEY_SYNC_REASON = "sync_reason"

/**
 * Periodic + one-time sync. Diffs contentHash from GET /api/device/slates,
 * fetches changed slates, writes cache, updates every widget from cache.
 * Never retries: the cache stays intact and the next period catches up.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
    private val repository: SlateRepository,
    private val widgetUpdater: WidgetUpdater,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val reason = inputData.getString(KEY_SYNC_REASON)?.let { runCatching { SyncReason.valueOf(it) }.getOrNull() }
            ?: SyncReason.PERIODIC
        repository.syncNow(reason)
        // Re-render from cache regardless of outcome: offline → cache + legible staleness.
        widgetUpdater.updateAll()
        applicationContext.sendBroadcast(
            Intent(ACTION_SLATE_SYNC_DONE).setPackage(applicationContext.packageName)
        )
        return Result.success()
    }
}

/**
 * Flushes the pending-interaction queue. Retry on network errors with backoff;
 * 4xx batches are dropped inside [InteractionManager] and trigger a reconcile.
 */
class FlushWorker(
    appContext: Context,
    params: WorkerParameters,
    private val api: SlateApi,
    private val settings: SettingsStore,
    private val interactions: InteractionManager,
    private val repository: SlateRepository,
    private val widgetUpdater: WidgetUpdater,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val cfg = ApiConfig.fromSettings(settings.current().serverUrl, settings.current().apiKey)
            ?: return Result.success() // not configured; queue keeps entries for later
        val outcome = try {
            interactions.flushOnce(api, cfg)
        } catch (e: SlateApiException) {
            // 5xx: server-side trouble — retry with backoff, queue untouched.
            return if (e.status in 500..599) Result.retry() else Result.success()
        } catch (e: IOException) {
            return Result.retry()
        } catch (e: Exception) {
            if (e.cause is IOException) {
                return Result.retry()
            }
            throw e
        }
        if (outcome is FlushOutcome.Completed && outcome.needsReconcile) {
            repository.syncNow(SyncReason.RECONCILE)
        }
        widgetUpdater.updateAll()
        return Result.success()
    }
}
