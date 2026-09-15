package dev.slate.android.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.slate.android.data.SyncReason
import java.util.concurrent.TimeUnit

/**
 * WorkManager owns all scheduling (widget updatePeriodMillis is 0):
 *  - periodic sync every [intervalMinutes] (default 15), network-constrained,
 *  - one-time syncs (app open, pull-to-refresh),
 *  - expedited flush right after widget/UI taps.
 */
class SyncScheduler(private val context: Context) {

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun ensurePeriodic(intervalMinutes: Int) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(network)
            .setInputData(workDataOf(KEY_SYNC_REASON to SyncReason.PERIODIC.name))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_SYNC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** One-time sync (app open, manual refresh). */
    fun syncNow(reason: SyncReason = SyncReason.MANUAL) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network)
            .setInputData(workDataOf(KEY_SYNC_REASON to reason.name))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SYNC_NOW,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /** Expedited flush — widget taps must reach the agent ASAP. */
    fun flushNow() {
        val request = OneTimeWorkRequestBuilder<FlushWorker>()
            .setConstraints(network)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            FLUSH_NOW,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /** Re-arm the periodic sync after the interval setting changes. */
    fun reschedulePeriodic(intervalMinutes: Int) = ensurePeriodic(intervalMinutes)

    companion object {
        const val PERIODIC_SYNC = "slate-periodic-sync"
        const val SYNC_NOW = "slate-sync-now"
        const val FLUSH_NOW = "slate-flush-now"
    }
}
