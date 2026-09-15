package dev.slate.android.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import dev.slate.android.data.CacheStore
import dev.slate.android.data.CachedSlate
import dev.slate.android.data.QueueStore
import dev.slate.android.spec.SpecParser
import dev.slate.android.sync.ACTION_SLATE_SYNC_DONE
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Provider renders synchronously from cache: update, resize, sync-done broadcast. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SlateWidgetProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var cache: CacheStore
    private lateinit var queue: QueueStore

    private val specJson = """{"id":"home","version":2,"title":"🌙 Nightly","tone":"warn","children":[
        {"type":"statusRow","label":"Backup","value":"failed","tone":"error","detail":"disk full"}]}"""

    @Before
    fun setUp() = runTest {
        cache = CacheStore(context)
        queue = QueueStore(context)
        cache.put(CachedSlate(DEFAULT_WIDGET_SLATE_ID, specJson, "h1", "h1", fetchedAt = 100L))
    }

    private fun bindWidget(appWidgetId: Int): AppWidgetManager {
        val manager = AppWidgetManager.getInstance(context)!!
        shadowOf(manager).bindAppWidgetId(
            appWidgetId,
            ComponentName(context, SlateWidgetProvider::class.java),
        )
        return manager
    }

    @Test
    fun `onUpdate renders from cache for every bound widget`() = runTest {
        val manager = bindWidget(42)
        SlateWidgetProvider().onUpdate(context, manager, intArrayOf(42))
        assertNotNull("widget should have been updated", shadowOf(manager).getViewFor(42))
    }

    @Test
    fun `widget renders waiting placeholder without cache`() = runTest {
        cache.remove(DEFAULT_WIDGET_SLATE_ID)
        val manager = bindWidget(7)
        SlateWidgetProvider().onUpdate(context, manager, intArrayOf(7))
        assertNotNull(shadowOf(manager).getViewFor(7))
    }

    @Test
    fun `onAppWidgetOptionsChanged re-renders for the new bucket`() = runTest {
        val manager = bindWidget(43)
        val options = Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200) }
        SlateWidgetProvider().onAppWidgetOptionsChanged(context, manager, 43, options)
        assertNotNull(shadowOf(manager).getViewFor(43))
        assertEquals(SizeBucket.COMPACT, SizeBucket.fromOptions(options))
    }

    @Test
    fun `SLATE_SYNC_DONE broadcast re-renders widgets`() = runTest {
        val manager = bindWidget(44)
        SlateWidgetProvider().onReceive(
            context,
            Intent(ACTION_SLATE_SYNC_DONE).setPackage(context.packageName),
        )
        assertNotNull(shadowOf(manager).getViewFor(44))
    }

    @Test
    fun `CacheWidgetUpdater updates all bound widgets and reports buckets`() = runTest {
        val manager = bindWidget(45)
        val updater = CacheWidgetUpdater(context, cache, queue, SpecParser())
        updater.updateAll()
        assertNotNull(shadowOf(manager).getViewFor(45))
        assertEquals(SizeBucket.FULL, updater.bucketFor(manager, 45))

        updater.updateOne(45)
        assertEquals(SizeBucket.FULL, updater.bucketFor(manager, 45))
    }

    @Test
    fun `WidgetRendererSync renders and reports spec presence`() = runTest {
        val manager = AppWidgetManager.getInstance(context)!!
        val result = WidgetRendererSync.renderForWidget(
            context, manager, 46, cache, queue, SpecParser(),
        )
        assertEquals(SizeBucket.FULL, result.bucket)
        assertTrue(result.hasSpec)
    }

    @Test
    fun `widget id default slate is home`() {
        assertEquals("home", DEFAULT_WIDGET_SLATE_ID)
    }
}
