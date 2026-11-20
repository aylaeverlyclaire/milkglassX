package com.milkglass.app.core.storage

import com.milkglass.app.core.model.Conversation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object StateCodec {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    fun encode(state: PersistedState): String =
        json.encodeToString(
            PersistedState.serializer(),
            state.copy(
                accessToken = null,
                downloads = state.downloads.filterValues { it == 100 },
            ),
        )

    fun decode(text: String?, defaults: PersistedState, seedConversations: List<Conversation>): PersistedState {
        if (text.isNullOrBlank()) return defaults
        val direct = runCatching { json.decodeFromString(PersistedState.serializer(), text) }.getOrNull()
        var s = if (direct != null) {
            direct
        } else {
            val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: return defaults
            var merged = json.encodeToJsonElement(PersistedState.serializer(), defaults) as JsonObject
            for ((k, v) in obj) {
                if (k !in merged.keys) continue
                val candidate = JsonObject(merged + (k to v))
                val ok = runCatching { json.decodeFromJsonElement(PersistedState.serializer(), candidate) }.isSuccess
                if (ok) merged = candidate
            }
            json.decodeFromJsonElement(PersistedState.serializer(), merged)
        }
        if (s.accessToken != null) s = s.copy(accessToken = null)
        val sub = s.subscription
        if (sub != null && sub.active == 0) s = s.copy(subscription = null)
        val photo = s.profilePhoto
        if (photo != null && !photo.startsWith("data:image/")) s = s.copy(profilePhoto = null)
        if (!s.conversations.all { it.id.isNotEmpty() }) s = s.copy(conversations = seedConversations)
        return s
    }
}

class StateStore(private val file: File) {
    @Volatile
    private var lastSavedJson: String? = null

    fun save(state: PersistedState): Boolean = synchronized(this) { runCatching {
        val encoded = StateCodec.encode(state)
        if (encoded == lastSavedJson && file.exists()) return@runCatching true
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(encoded.toByteArray(Charsets.UTF_8))
            fos.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        lastSavedJson = encoded
        true
    }.getOrDefault(false) }

    fun load(defaults: PersistedState, seedConversations: List<Conversation>): PersistedState = synchronized(this) {
        val raw = if (file.exists()) runCatching { file.readText(Charsets.UTF_8) }.getOrNull() else null
        if (raw != null) lastSavedJson = raw
        StateCodec.decode(raw, defaults, seedConversations)
    }

    fun wipe() = synchronized(this) {
        lastSavedJson = null
        file.delete()
    }

    companion object {
        const val STORAGE_KEY = "hanabi:v4"
        const val FILE_NAME = "hanabi_v4.json"
    }
}
