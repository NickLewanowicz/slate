package dev.slate.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/** Settings defaults, sanitization branches, persistence across "restart". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // ---- pure sanitizers (no Robolectric needed) ----

    @Test
    fun `server url normalization branches`() {
        assertEquals("http://10.0.2.2:3000", SettingsSanitizer.normalizeServerUrl(" http://10.0.2.2:3000/ "))
        assertEquals("https://slate.example", SettingsSanitizer.normalizeServerUrl("https://slate.example///"))
        assertNull("missing scheme rejected", SettingsSanitizer.normalizeServerUrl("10.0.2.2:3000"))
        assertNull("blank rejected", SettingsSanitizer.normalizeServerUrl("   "))
        assertNull("garbage rejected", SettingsSanitizer.normalizeServerUrl("not a url"))
    }

    @Test
    fun `api key normalization branches`() {
        assertEquals("secret", SettingsSanitizer.normalizeApiKey("  secret "))
        assertNull(SettingsSanitizer.normalizeApiKey(""))
        assertNull(SettingsSanitizer.normalizeApiKey("   "))
    }

    @Test
    fun `sync interval sanitization clamps to bounds`() {
        assertEquals(15, SettingsSanitizer.sanitizeInterval(15))
        assertEquals(5, SettingsSanitizer.sanitizeInterval(1))
        assertEquals(5, SettingsSanitizer.sanitizeInterval(4))
        assertEquals(240, SettingsSanitizer.sanitizeInterval(1000))
        assertEquals(15, SettingsSanitizer.sanitizeInterval(0))
        assertEquals(15, SettingsSanitizer.sanitizeInterval(-30))
    }

    @Test
    fun `validate reports per-field errors`() {
        val clean = SettingsSanitizer.validate("http://x", "k")
        assertTrue(clean.isEmpty())
        val errors = SettingsSanitizer.validate("nope", "")
        assertEquals(setOf(SetupField.SERVER_URL, SetupField.API_KEY), errors.keys)
    }

    // ---- DataStore behavior ----

    /** Unique store name per test → full DataStore isolation (no cross-test statics). */
    private fun freshStore(): Pair<SettingsStore, String> {
        val name = "settings-${UUID.randomUUID()}"
        return SettingsStore(context, name) to name
    }

    @Test
    fun `defaults are empty url, empty key, 15 minutes`() = runTest {
        val (store, _) = freshStore()
        val settings = store.current()
        assertEquals(AppSettings.DEFAULT_SERVER_URL, settings.serverUrl)
        assertEquals(AppSettings.DEFAULT_API_KEY, settings.apiKey)
        assertEquals(15, settings.syncIntervalMinutes)
        assertTrue(!settings.isConfigured)
    }

    @Test
    fun `saveConnection persists and invalid input saves nothing`() = runTest {
        val (store, _) = freshStore()
        val errors = store.saveConnection("bad", "")
        assertEquals(2, errors.size)
        assertTrue(store.current().serverUrl.isEmpty())

        assertTrue(store.saveConnection("http://server.test", "key").isEmpty())
        assertEquals("http://server.test", store.current().serverUrl)
        assertEquals("key", store.current().apiKey)
        assertTrue(store.current().isConfigured)
    }

    @Test
    fun `settings persist to the preferences file`() = runTest {
        val (store, name) = freshStore()
        store.saveConnection("http://server.test", "key")
        store.setSyncIntervalMinutes(30)
        assertTrue("preferences file exists", File(context.filesDir, "$name.preferences_pb").exists())

        // What a restarted process reads: values live in the same per-name store.
        val after = SettingsStore(context, name).current()
        assertEquals("http://server.test", after.serverUrl)
        assertEquals(30, after.syncIntervalMinutes)
    }

    @Test
    fun `setInterval sanitizes before persisting`() = runTest {
        val (store, _) = freshStore()
        store.setSyncIntervalMinutes(-5)
        assertEquals(15, store.current().syncIntervalMinutes)
        store.setSyncIntervalMinutes(2)
        assertEquals(5, store.current().syncIntervalMinutes)
    }
}
