package dev.slate.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Immutable snapshot of user settings. */
data class AppSettings(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val apiKey: String = DEFAULT_API_KEY,
    val syncIntervalMinutes: Int = DEFAULT_SYNC_INTERVAL_MINUTES,
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && apiKey.isNotBlank()

    companion object {
        const val DEFAULT_SERVER_URL = ""
        const val DEFAULT_API_KEY = ""
        const val DEFAULT_SYNC_INTERVAL_MINUTES = 15
        const val MIN_SYNC_INTERVAL_MINUTES = 5
        const val MAX_SYNC_INTERVAL_MINUTES = 240
    }
}

object SettingsSanitizer {
    private val urlRegex = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

    /** Normalize a server URL; null when it cannot be valid (missing scheme / blank). */
    fun normalizeServerUrl(input: String): String? {
        val trimmed = input.trim().trimEnd('/')
        if (!urlRegex.matches(trimmed)) return null
        return trimmed
    }

    /** API key: trimmed, non-empty else null. */
    fun normalizeApiKey(input: String): String? {
        val trimmed = input.trim()
        return if (trimmed.isEmpty()) null else trimmed
    }

    /** Interval clamp: non-positive values fall back to the default 15, others clamp to bounds. */
    fun sanitizeInterval(minutes: Int): Int = when {
        minutes <= 0 -> AppSettings.DEFAULT_SYNC_INTERVAL_MINUTES
        minutes < AppSettings.MIN_SYNC_INTERVAL_MINUTES -> AppSettings.MIN_SYNC_INTERVAL_MINUTES
        minutes > AppSettings.MAX_SYNC_INTERVAL_MINUTES -> AppSettings.MAX_SYNC_INTERVAL_MINUTES
        else -> minutes
    }

    /** Combined validation used by the setup screen. Returns error messages per field. */
    fun validate(serverUrl: String, apiKey: String): Map<SetupField, String> {
        val errors = mutableMapOf<SetupField, String>()
        if (normalizeServerUrl(serverUrl) == null) {
            errors[SetupField.SERVER_URL] = "Needs a full URL, e.g. http://10.0.2.2:3000"
        }
        if (normalizeApiKey(apiKey) == null) {
            errors[SetupField.API_KEY] = "The API key can’t be empty"
        }
        return errors
    }
}

enum class SetupField { SERVER_URL, API_KEY }

/** DataStore-backed settings (serverUrl, apiKey, syncIntervalMinutes). */
class SettingsStore(context: Context, name: String = DEFAULT_NAME) {

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val API_KEY = stringPreferencesKey("api_key")
        val SYNC_INTERVAL_MINUTES = intPreferencesKey("sync_interval_minutes")
    }

    private val dataStore: DataStore<Preferences> =
        Stores.preferences(Stores.file(context, name, ".preferences_pb"), stores)

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            serverUrl = prefs[Keys.SERVER_URL] ?: AppSettings.DEFAULT_SERVER_URL,
            apiKey = prefs[Keys.API_KEY] ?: AppSettings.DEFAULT_API_KEY,
            syncIntervalMinutes = prefs[Keys.SYNC_INTERVAL_MINUTES]
                ?: AppSettings.DEFAULT_SYNC_INTERVAL_MINUTES,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    /**
     * Persist server + key after sanitization; returns per-field errors when
     * invalid. A FULLY blank pair is a deliberate disconnect and is persisted
     * (isConfigured becomes false → repository reports the friendly unconfigured
     * error); non-blank invalid input is rejected and saves nothing.
     */
    suspend fun saveConnection(serverUrl: String, apiKey: String): Map<SetupField, String> {
        val errors = SettingsSanitizer.validate(serverUrl, apiKey)
        val clearing = serverUrl.isBlank() && apiKey.isBlank()
        if (errors.isNotEmpty() && !clearing) return errors
        dataStore.edit { prefs ->
            if (clearing) {
                prefs[Keys.SERVER_URL] = ""
                prefs[Keys.API_KEY] = ""
            } else {
                prefs[Keys.SERVER_URL] = SettingsSanitizer.normalizeServerUrl(serverUrl)!!
                prefs[Keys.API_KEY] = SettingsSanitizer.normalizeApiKey(apiKey)!!
            }
        }
        return errors
    }

    suspend fun setSyncIntervalMinutes(minutes: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.SYNC_INTERVAL_MINUTES] = SettingsSanitizer.sanitizeInterval(minutes)
        }
    }

    companion object {
        const val DEFAULT_NAME = "slate_settings"
        private val stores = java.util.concurrent.ConcurrentHashMap<String, DataStore<Preferences>>()
    }
}
