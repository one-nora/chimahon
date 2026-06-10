package ireader.core.prefs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

interface Preference<T> {
    fun key(): String
    fun get(): T
    fun set(value: T)
    fun isSet(): Boolean
    fun delete()
    fun defaultValue(): T
    fun changes(): Flow<T>
    fun stateIn(scope: CoroutineScope): StateFlow<T>
}

interface PreferenceStore {
    fun getString(key: String, defaultValue: String = ""): Preference<String>
    fun getLong(key: String, defaultValue: Long = 0): Preference<Long>
    fun getInt(key: String, defaultValue: Int = 0): Preference<Int>
    fun getFloat(key: String, defaultValue: Float = 0f): Preference<Float>
    fun getBoolean(key: String, defaultValue: Boolean = false): Preference<Boolean>
    fun getStringSet(key: String, defaultValue: Set<String> = emptySet()): Preference<Set<String>>

    fun <T> getObject(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T>

    fun <T> getJsonObject(
        key: String,
        defaultValue: T,
        serializer: KSerializer<T>,
        serializersModule: SerializersModule = EmptySerializersModule(),
    ): Preference<T>
}

inline fun <reified T : Enum<T>> PreferenceStore.getEnum(
    key: String,
    defaultValue: T,
): Preference<T> {
    return getObject(
        key = key,
        defaultValue = defaultValue,
        serializer = { it.name },
        deserializer = { value -> enumValues<T>().firstOrNull { it.name == value } ?: defaultValue },
    )
}

class InMemoryPreferenceStore : PreferenceStore {
    private val values = mutableMapOf<String, Any?>()
    private val preferences = mutableMapOf<String, MemoryPreference<*>>()

    override fun getString(key: String, defaultValue: String): Preference<String> = preference(key, defaultValue)
    override fun getLong(key: String, defaultValue: Long): Preference<Long> = preference(key, defaultValue)
    override fun getInt(key: String, defaultValue: Int): Preference<Int> = preference(key, defaultValue)
    override fun getFloat(key: String, defaultValue: Float): Preference<Float> = preference(key, defaultValue)
    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> = preference(key, defaultValue)
    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> = preference(key, defaultValue)

    override fun <T> getObject(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = preference(key, defaultValue)

    override fun <T> getJsonObject(
        key: String,
        defaultValue: T,
        serializer: KSerializer<T>,
        serializersModule: SerializersModule,
    ): Preference<T> = preference(key, defaultValue)

    @Suppress("UNCHECKED_CAST")
    private fun <T> preference(key: String, defaultValue: T): Preference<T> {
        return preferences.getOrPut(key) {
            MemoryPreference(
                prefKey = key,
                defaultValue = defaultValue,
                read = { values[key] as? T },
                write = { values[key] = it },
                clear = { values.remove(key) },
            )
        } as Preference<T>
    }
}

private class MemoryPreference<T>(
    private val prefKey: String,
    private val defaultValue: T,
    private val read: () -> T?,
    private val write: (T) -> Unit,
    private val clear: () -> Unit,
) : Preference<T> {
    private val state = MutableStateFlow(read() ?: defaultValue)

    override fun key(): String = prefKey
    override fun get(): T = read() ?: defaultValue
    override fun set(value: T) {
        write(value)
        state.value = value
    }
    override fun isSet(): Boolean = read() != null
    override fun delete() {
        clear()
        state.value = defaultValue
    }
    override fun defaultValue(): T = defaultValue
    override fun changes(): Flow<T> = state.asStateFlow()
    override fun stateIn(scope: CoroutineScope): StateFlow<T> = state.asStateFlow()
}
