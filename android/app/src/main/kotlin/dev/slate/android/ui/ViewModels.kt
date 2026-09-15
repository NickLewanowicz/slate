package dev.slate.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.slate.android.api.ApiConfig
import dev.slate.android.api.PingResponse
import dev.slate.android.api.SlateApi
import dev.slate.android.data.AppSettings
import dev.slate.android.data.SetupField
import dev.slate.android.data.SettingsStore
import dev.slate.android.data.SettingsSanitizer
import dev.slate.android.data.SlateDetailUiState
import dev.slate.android.data.SlateRepository
import dev.slate.android.data.SyncReason
import dev.slate.android.interact.DeliveryStatus
import dev.slate.android.interact.InteractionManager
import dev.slate.android.spec.QuestionElement
import dev.slate.android.spec.QuestionOption
import dev.slate.android.sync.SyncScheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Shared factory for container-wired ViewModels. */
class SlateVmFactory(
    private val container: dev.slate.android.di.AppContainer,
    private val slateId: String? = null,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return when (modelClass) {
            SetupViewModel::class.java -> SetupViewModel(
                container.slateApi,
                container.settingsStore,
                container.syncScheduler,
                container.repository,
            )
            SlatesListViewModel::class.java -> SlatesListViewModel(container.repository, container.syncScheduler)
            SlateDetailViewModel::class.java -> SlateDetailViewModel(
                container.repository,
                container.interactions,
                container.syncScheduler,
                slateId ?: "home",
            )
            SettingsViewModel::class.java -> SettingsViewModel(container.settingsStore, container.syncScheduler)
            else -> error("Unknown ViewModel $modelClass")
        } as T
    }
}

// ---------------------------------------------------------------- setup

sealed class TestResult {
    data object InProgress : TestResult()
    data class Success(val version: String?, val serverTime: String?) : TestResult()
    data class Failure(val message: String) : TestResult()
}

data class SetupUiState(
    val serverUrl: String = "",
    val apiKey: String = "",
    val testing: Boolean = false,
    val test: TestResult? = null,
    val fieldErrors: Map<SetupField, String> = emptyMap(),
    val saving: Boolean = false,
)

/** Setup screen: server + key, live "Test connection", validation via SettingsSanitizer. */
class SetupViewModel(
    private val api: SlateApi,
    private val settings: SettingsStore,
    private val scheduler: SyncScheduler,
    private val repository: SlateRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state
    private var testJob: Job? = null

    fun prefill(serverUrl: String?, apiKey: String?) {
        _state.value = _state.value.copy(
            serverUrl = serverUrl ?: _state.value.serverUrl,
            apiKey = apiKey ?: _state.value.apiKey,
        )
    }

    fun editServerUrl(value: String) {
        _state.value = _state.value.copy(serverUrl = value, test = null, fieldErrors = _state.value.fieldErrors - SetupField.SERVER_URL)
    }

    fun editApiKey(value: String) {
        _state.value = _state.value.copy(apiKey = value, test = null, fieldErrors = _state.value.fieldErrors - SetupField.API_KEY)
    }

    fun testConnection() {
        val cfg = ApiConfig.fromSettings(_state.value.serverUrl, _state.value.apiKey)
        if (cfg == null) {
            _state.value = _state.value.copy(fieldErrors = SettingsSanitizer.validate(_state.value.serverUrl, _state.value.apiKey))
            return
        }
        testJob?.cancel()
        _state.value = _state.value.copy(testing = true, test = TestResult.InProgress)
        testJob = viewModelScope.launch {
            _state.value = try {
                val pong: PingResponse = api.ping(cfg)
                _state.value.copy(
                    testing = false,
                    test = if (pong.ok) TestResult.Success(pong.version, pong.serverTime)
                    else TestResult.Failure("Server answered, but not with a Slate ping."),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value.copy(testing = false, test = TestResult.Failure(e.message ?: "Connection failed"))
            }
        }
    }

    /** Validate + persist; arm periodic sync; kick a first sync; then [onDone]. */
    fun save(onDone: () -> Unit) {
        val s = _state.value
        viewModelScope.launch {
            val errors = settings.saveConnection(s.serverUrl, s.apiKey)
            if (errors.isNotEmpty()) {
                _state.value = s.copy(fieldErrors = errors)
                return@launch
            }
            scheduler.ensurePeriodic(AppSettings.DEFAULT_SYNC_INTERVAL_MINUTES)
            repository.syncNow(SyncReason.APP_OPEN)
            onDone()
        }
    }
}

// ---------------------------------------------------------------- list

/** Slates list: exposes repository state + manual refresh. */
class SlatesListViewModel(
    private val repository: SlateRepository,
    private val scheduler: SyncScheduler,
) : ViewModel() {
    val ui: StateFlow<dev.slate.android.data.SlatesUiState> = repository.slatesUi
    val syncState: StateFlow<dev.slate.android.data.SyncState> = repository.syncState

    fun refresh() = scheduler.syncNow(SyncReason.MANUAL)
}

// ---------------------------------------------------------------- detail

data class SlateDetailVmState(
    val detail: SlateDetailUiState?,
    val delivery: Map<String, DeliveryStatus>,
)

/** Detail screen: cached spec + optimistic interactions + delivery chips. */
class SlateDetailViewModel(
    private val repository: SlateRepository,
    private val interactions: InteractionManager,
    private val scheduler: SyncScheduler,
    private val slateId: String = "home",
) : ViewModel() {

    val ui: StateFlow<SlateDetailVmState> =
        combine(repository.detailUi(slateId), interactions.delivery) { detail, delivery ->
            SlateDetailVmState(detail, delivery)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SlateDetailVmState(null, emptyMap()))

    val syncState: StateFlow<dev.slate.android.data.SyncState> = repository.syncState

    fun toggleTodo(listId: String, itemId: String) {
        viewModelScope.launch {
            interactions.toggleTodo(slateId, listId, itemId)
            scheduler.flushNow()
        }
    }

    fun answer(question: QuestionElement, option: QuestionOption) {
        viewModelScope.launch {
            interactions.answerQuestion(slateId, question, option)
            scheduler.flushNow()
        }
    }

    fun sendText(question: QuestionElement, value: String) {
        viewModelScope.launch {
            interactions.sendText(slateId, question, value)
            scheduler.flushNow()
        }
    }

    fun refresh() = scheduler.syncNow(SyncReason.MANUAL)

    fun questionKey(questionId: String) = interactions.questionKey(slateId, questionId)

    /** 20s foreground fast-poll; lifecycle-aware via repeatOnLifecycle in the screen. */
    fun startFastPoll(scope: kotlinx.coroutines.CoroutineScope): Job = scope.launch {
        while (isActive) {
            repository.syncNow(SyncReason.FAST_POLL)
            delay(20_000)
        }
    }
}

// ---------------------------------------------------------------- settings

data class SettingsUiState(
    val serverUrl: String = "",
    val apiKey: String = "",
    val syncIntervalMinutes: Int = 15,
    val fieldErrors: Map<SetupField, String> = emptyMap(),
    val saved: Boolean = false,
)

class SettingsViewModel(
    private val settings: SettingsStore,
    private val scheduler: SyncScheduler,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state

    init {
        viewModelScope.launch {
            settings.settings.collect { s ->
                _state.value = _state.value.copy(
                    serverUrl = s.serverUrl,
                    apiKey = s.apiKey,
                    syncIntervalMinutes = s.syncIntervalMinutes,
                )
            }
        }
    }

    fun setInterval(minutes: Int) {
        viewModelScope.launch {
            settings.setSyncIntervalMinutes(minutes)
            scheduler.reschedulePeriodic(minutes)
        }
    }

    /** Local edits before saving (does not touch DataStore). */
    fun editConnectionPreview(serverUrl: String, apiKey: String) {
        _state.value = _state.value.copy(
            serverUrl = serverUrl,
            apiKey = apiKey,
            fieldErrors = emptyMap(),
            saved = false,
        )
    }

    fun saveConnection(serverUrl: String, apiKey: String) {
        viewModelScope.launch {
            val errors = settings.saveConnection(serverUrl, apiKey)
            _state.value = _state.value.copy(fieldErrors = errors, saved = errors.isEmpty())
        }
    }
}
