package com.milkglass.app.core.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

const val SNAPSHOT_SCHEMA = 1

const val RECHECK_MIN_SEC = 60L
const val RECHECK_MAX_SEC = 6L * 3600L
const val RECHECK_DEFAULT_SEC = 900L

const val BACKOFF_BASE_MS = 2L * 60_000L
const val BACKOFF_MAX_MS = 30L * 60_000L

@Serializable
data class StatusData(
    val version: String? = null,
    @SerialName("recheck_after_sec") val recheckAfterSec: Long? = null,
)

@Serializable
data class StatusResponse(
    val status: String,
    val data: StatusData? = null,
)

@Serializable
data class SnapshotMeta(
    val version: String? = null,
    
    val count: Int = 0,
)

@Serializable
data class SnapshotResponse(
    val status: String,
    val data: List<RemoteAnime> = emptyList(),
    val meta: SnapshotMeta = SnapshotMeta(),
    val message: String? = null,
)

@Serializable
data class SnapshotState(
    val schema: Int = SNAPSHOT_SCHEMA,
    val version: String,
    
    val fetchedAtMs: Long,
    
    val validUntilMs: Long,
    val failures: Int = 0,
    
    val complete: Boolean = true,
)

interface SnapshotStore {
    fun readBody(): String?
    fun readState(): SnapshotState?
    fun writeBody(json: String)
    fun writeState(state: SnapshotState)
}

class FileSnapshotStore(private val dir: File) : SnapshotStore {
    private val body get() = File(dir, "snapshot.json")
    private val state get() = File(dir, "snapshot.state.json")

    override fun readBody(): String? = readOrNull(body)

    override fun readState(): SnapshotState? = try {
        readOrNull(state)?.let { RemoteJson.decodeFromString(SnapshotState.serializer(), it) }
    } catch (e: Exception) {
        null
    }

    override fun writeBody(json: String) = atomicWrite(body, json)

    override fun writeState(state: SnapshotState) =
        atomicWrite(this.state, RemoteJson.encodeToString(SnapshotState.serializer(), state))

    private fun readOrNull(f: File): String? = try {
        if (f.isFile) f.readText(Charsets.UTF_8) else null
    } catch (e: Exception) {
        null
    }

    
    private fun atomicWrite(target: File, text: String) {
        try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        } catch (e: Exception) {
            
        }
    }
}

class CachedSnapshot(val entries: List<CatalogEntry>, val complete: Boolean)

sealed interface RefreshOutcome {
    
    data object Skipped : RefreshOutcome
    
    data object Unchanged : RefreshOutcome
    
    class Updated(val entries: List<CatalogEntry>, val complete: Boolean) : RefreshOutcome
    
    data object Unsupported : RefreshOutcome
    
    data object Failed : RefreshOutcome
}
