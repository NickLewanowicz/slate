package dev.slate.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared plumbing for the three DataStore-backed stores.
 *
 * DataStore forbids two live instances for the same file inside one process,
 * so stores register per absolute file path. [name] is injectable: tests use
 * unique names for isolation (production keeps the defaults).
 */
object Stores {

    fun file(context: Context, name: String, ext: String): File =
        File(context.filesDir, "$name$ext")

    fun preferences(
        file: File,
        cache: ConcurrentHashMap<String, DataStore<Preferences>>,
    ): DataStore<Preferences> = cache.getOrPut(file.absolutePath) {
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { file },
        )
    }

    fun <T> json(
        file: File,
        cache: ConcurrentHashMap<String, DataStore<T>>,
        serializer: androidx.datastore.core.Serializer<T>,
        default: T,
    ): DataStore<T> = cache.getOrPut(file.absolutePath) {
        DataStoreFactory.create(
            serializer = serializer,
            corruptionHandler = ReplaceFileCorruptionHandler(produceNewData = { default }),
            produceFile = { file },
        )
    }
}
