package dev.slate.android.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * A cached spec as pulled from the server. The widget ALWAYS renders from this
 * cache — never from the network — so the home screen survives server outages.
 */
@Serializable
data class CachedSlate(
    val slateId: String,
    val rawJson: String,
    val contentHash: String,
    val etag: String,
    val fetchedAt: Long,
)

@Serializable
data class CacheSnapshot(
    val slates: Map<String, CachedSlate> = emptyMap(),
)

/** Generic JSON-file DataStore serializer. */
class JsonDataStoreSerializer<T>(private val serializer: KSerializer<T>, private val default: T) : Serializer<T> {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override val defaultValue: T get() = default

    override suspend fun readFrom(input: InputStream): T = try {
        json.decodeFromString(serializer, input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Unable to read slate store file", e)
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        output.write(json.encodeToString(serializer, t).encodeToByteArray())
    }
}

/**
 * DataStore-backed cache of spec envelopes keyed by slateId (JSON on disk).
 * Synchronous accessors exist for the widget provider (broadcast receivers have
 * a short, synchronous lifecycle).
 */
class CacheStore(context: Context, name: String = DEFAULT_NAME) {

    // The "-cache" suffix namespaces the file per store type: DataStore allows
    // exactly ONE live instance per file (process-wide), and tests construct
    // CacheStore + QueueStore with the SAME name for isolation.
    private val dataStore: DataStore<CacheSnapshot> =
        Stores.json(
            file = Stores.file(context, "$name.cache", ".json"),
            cache = stores,
            serializer = JsonDataStoreSerializer(CacheSnapshot.serializer(), CacheSnapshot()),
            default = CacheSnapshot(),
        )

    val cache: Flow<CacheSnapshot> = dataStore.data

    suspend fun get(slateId: String): CachedSlate? = dataStore.data.first().slates[slateId]

    /** Blocking read for the widget provider path (small file; receiver-safe). */
    fun getBlocking(slateId: String): CachedSlate? = runBlockingFirst { get(slateId) }

    val slatesBlocking: Map<String, CachedSlate>
        get() = runBlockingFirst { dataStore.data.first().slates }

    suspend fun put(slate: CachedSlate) {
        dataStore.updateData { snapshot ->
            snapshot.copy(slates = snapshot.slates + (slate.slateId to slate))
        }
    }

    suspend fun remove(slateId: String) {
        dataStore.updateData { snapshot ->
            snapshot.copy(slates = snapshot.slates - slateId)
        }
    }

    suspend fun retainAll(keepIds: Set<String>) {
        dataStore.updateData { snapshot ->
            snapshot.copy(slates = snapshot.slates.filterKeys { it in keepIds })
        }
    }

    fun slateFlow(slateId: String): Flow<CachedSlate?> = cache.map { it.slates[slateId] }

    private fun <T> runBlockingFirst(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }

    companion object {
        const val DEFAULT_NAME = "slate_cache"
        private val stores = ConcurrentHashMap<String, DataStore<CacheSnapshot>>()
    }
}
