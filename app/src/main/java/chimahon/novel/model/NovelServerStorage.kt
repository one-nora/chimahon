package chimahon.novel.model

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.novelServerDataStore: DataStore<Preferences> by preferencesDataStore(name = "novel_servers")

class NovelServerStorage(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    fun getAllServers(): Flow<List<NovelServer>> {
        return context.novelServerDataStore.data.map { prefs ->
            prefs.asMap()
                .filterKeys { it.name.startsWith("novel_server_") }
                .values
                .mapNotNull { value ->
                    runCatching { json.decodeFromString<NovelServer>(value.toString()) }.getOrNull()
                }
                .sortedBy { it.displayOrder }
        }
    }

    suspend fun saveServer(server: NovelServer) {
        context.novelServerDataStore.edit { prefs ->
            val key = stringPreferencesKey("novel_server_${server.id}")
            prefs[key] = json.encodeToString(server)
        }
    }

    suspend fun deleteServer(serverId: String) {
        context.novelServerDataStore.edit { prefs ->
            val key = stringPreferencesKey("novel_server_$serverId")
            prefs.remove(key)
        }
    }

    suspend fun updateServerOrder(serverId: String, newOrder: Int) {
        context.novelServerDataStore.edit { prefs ->
            val key = stringPreferencesKey("novel_server_$serverId")
            val existing = prefs[key]?.let { runCatching { json.decodeFromString<NovelServer>(it.toString()) }.getOrNull() }
            if (existing != null) {
                prefs[key] = json.encodeToString(existing.copy(displayOrder = newOrder))
            }
        }
    }
}
