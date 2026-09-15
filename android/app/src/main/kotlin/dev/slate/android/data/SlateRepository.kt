package dev.slate.android.data

import dev.slate.android.api.ApiConfig
import dev.slate.android.api.SlateApi
import dev.slate.android.api.SlateApiException
import dev.slate.android.api.SlateFetchResult
import dev.slate.android.api.SlateSummary
import dev.slate.android.api.isNetworkError
import dev.slate.android.spec.QuestionElement
import dev.slate.android.spec.SlateSpec
import dev.slate.android.spec.SpecParser
import dev.slate.android.spec.Tone
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Why a sync was kicked off — surfaced in logs/UI chips. */
enum class SyncReason { PERIODIC, MANUAL, APP_OPEN, FAST_POLL, RECONCILE, AFTER_FLUSH }

/** Legible sync state for UI + staleness surfacing. */
sealed class SyncState {
    data object Idle : SyncState()
    data object Syncing : SyncState()
    data class Success(val at: Long, val changed: Int) : SyncState()
    data class Error(val message: String, val offline: Boolean) : SyncState()
}

/** One card on the slates list screen. */
data class SlateCardUi(
    val slateId: String,
    val title: String,
    val tone: Tone,
    val updatedAt: String?,
    val contentHash: String,
    val fetchedAt: Long,
    val openQuestions: Int,
    val expired: Boolean,
)

data class SlatesUiState(
    val cards: List<SlateCardUi> = emptyList(),
    val sync: SyncState = SyncState.Idle,
    val configured: Boolean = false,
)

/** Detail screen state: parsed spec + answered markers, all from local cache. */
data class SlateDetailUiState(
    val slateId: String,
    val spec: SlateSpec?,
    val cached: CachedSlate?,
    val answered: Map<String, AnsweredChoice>,
    val pendingForSlate: Int,
)

/**
 * Offline-first repository. The cache (DataStore JSON) is the single source for
 * rendering — UI and widget never read the network directly. Server-down means
 * cached content + a legible [SyncState.Error], never a blank surface.
 */
class SlateRepository(
    private val api: SlateApi,
    private val settings: SettingsStore,
    private val cacheStore: CacheStore,
    private val queueStore: QueueStore,
    private val externalScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val syncMutex = Mutex()
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState

    /** Configured flag as flow, for gating nav + widgets. */
    val configuredFlow: Flow<Boolean> = settings.settings.map { it.isConfigured }

    /**
     * Full sync: hash-diff list → fetch changed slates (If-None-Match) → write cache.
     * Never throws; failures become [SyncState.Error] and the cache stays intact.
     */
    suspend fun syncNow(reason: SyncReason = SyncReason.MANUAL): SyncState = syncMutex.withLock {
        withContext(ioDispatcher) {
            val cfg = ApiConfig.fromSettings(settings.current().serverUrl, settings.current().apiKey)
            if (cfg == null || !cfg.isComplete) {
                val state = SyncState.Error("Slate isn’t set up yet — add your server and key.", offline = false)
                _syncState.value = state
                return@withContext state
            }
            _syncState.value = SyncState.Syncing
            _syncState.value = try {
                val list = api.listSlates(cfg)
                val changed = reconcileSlates(cfg, list)
                val state = SyncState.Success(clock(), changed)
                state
            } catch (e: SlateApiException) {
                SyncState.Error(e.message ?: "Server error", offline = false)
            } catch (e: Throwable) {
                if (isNetworkError(e)) {
                    SyncState.Error("Can’t reach the server — showing cached slates.", offline = true)
                } else {
                    SyncState.Error(e.message ?: "Unexpected error", offline = false)
                }
            }
            _syncState.value
        }
    }

    /** Diff the list against the cache; fetch only changed slates. Returns how many updated. */
    private suspend fun reconcileSlates(cfg: ApiConfig, list: List<SlateSummary>): Int {
        var changed = 0
        for (summary in list) {
            val existing = cacheStore.get(summary.slateId)
            val isUnchanged = existing != null && existing.contentHash == summary.contentHash
            if (isUnchanged) continue
            when (val result = api.fetchSlate(cfg, summary.slateId, existing?.etag)) {
                is SlateFetchResult.Fresh -> {
                    cacheStore.put(
                        CachedSlate(
                            slateId = summary.slateId,
                            rawJson = result.rawJson,
                            contentHash = result.contentHash,
                            etag = result.contentHash,
                            fetchedAt = clock(),
                        )
                    )
                    // Spec changed: keep answered markers only for questions still in the
                    // fresh spec (a one-row refresh must not re-open every question).
                    val freshSpec = SpecParserInstance.parseOrNull(result.rawJson)
                    val keepIds = freshSpec?.children
                        ?.filterIsInstance<dev.slate.android.spec.QuestionElement>()
                        ?.map { it.id }
                        ?: emptyList()
                    queueStore.retainAnswered(summary.slateId, keepIds)
                    changed++
                }
                SlateFetchResult.NotModified -> {
                    // Server says content matches our ETag — trust it, refresh freshness only.
                    existing?.let {
                        cacheStore.put(it.copy(contentHash = summary.contentHash, fetchedAt = clock()))
                    }
                }
            }
        }
        // Slates the server no longer knows about leave the device too.
        cacheStore.retainAll(list.map { it.slateId }.toSet())
        return changed
    }

    /** List-screen state: cards built from cache (never blank when offline). */
    val slatesUi: StateFlow<SlatesUiState> = combine(
        cacheStore.cache,
        _syncState,
        settings.settings,
        queueStore.snapshot,
    ) { snapshot, sync, appSettings, queueSnapshot ->
        val cards = snapshot.slates.values.map { cached ->
            val spec = SpecParserInstance.parseOrNull(cached.rawJson)
            val answered = queueSnapshot.answered.keys
                .filter { it.startsWith("${cached.slateId}/") }
                .map { it.removePrefix("${cached.slateId}/") }
                .toSet()
            SlateCardUi(
                slateId = cached.slateId,
                title = spec?.title?.ifBlank { cached.slateId } ?: cached.slateId,
                tone = spec?.let { SlateSpec.aggregateTone(it) } ?: Tone.NEUTRAL,
                updatedAt = spec?.updatedAt,
                contentHash = cached.contentHash,
                fetchedAt = cached.fetchedAt,
                openQuestions = spec?.let { openQuestions(it, answered) } ?: 0,
                expired = spec?.isExpired(clock()) ?: false,
            )
        }.sortedByDescending { it.updatedAt ?: "" }
        SlatesUiState(cards = cards, sync = sync, configured = appSettings.isConfigured)
    }.stateIn(
        scope = externalScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SlatesUiState(),
    )

    /** Detail-screen state for one slate, driven entirely by cache + queue. */
    fun detailUi(slateId: String): Flow<SlateDetailUiState> = combine(
        cacheStore.slateFlow(slateId),
        queueStore.snapshot,
    ) { cached, queueSnapshot ->
        SlateDetailUiState(
            slateId = slateId,
            spec = cached?.let { SpecParserInstance.parseOrNull(it.rawJson) },
            cached = cached,
            answered = queueSnapshot.answered
                .filterKeys { it.startsWith("$slateId/") }
                .mapKeys { it.key.removePrefix("$slateId/") },
            pendingForSlate = queueSnapshot.queue.count { it.slateId == slateId },
        )
    }

    /** Current cached spec (suspend); widget path uses CacheStore.getBlocking instead. */
    suspend fun cachedSpec(slateId: String): SlateSpec? =
        cacheStore.get(slateId)?.let { SpecParserInstance.parseOrNull(it.rawJson) }

    companion object {
        private val SpecParserInstance = SpecParser()

        /** Question elements of [spec] without an answered marker. */
        fun openQuestions(spec: SlateSpec, answeredIds: Collection<String>): Int =
            spec.children.count { el ->
                el is QuestionElement && el.id !in answeredIds.toSet()
            }
    }
}
