package com.milkglass.app.core.remote

import androidx.compose.runtime.Immutable

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

const val SNAPSHOT_SCHEMA = 1

const val RECHECK_MIN_SEC = 60L
const val RECHECK_MAX_SEC = 6L * 3600L
const val RECHECK_DEFAULT_SEC = 900L

const val BACKOFF_BASE_MS = 2L * 60_000L
const val BACKOFF_MAX_MS = 30L * 60_000L

@Serializable
@Immutable
data class StatusData(
    val version: String? = null,
    @SerialName("recheck_after_sec") val recheckAfterSec: Long? = null,
)

@Serializable
@Immutable
data class StatusResponse(
    val status: String,
    val data: StatusData? = null,
)

@Serializable
@Immutable
data class SnapshotMeta(
    val version: String? = null,

    val count: Int = 0,
)

@Serializable
@Immutable
data class SnapshotResponse(
    val status: String,
    val data: List<RemoteAnime> = emptyList(),
    val meta: SnapshotMeta = SnapshotMeta(),
    val message: String? = null,
)

@Serializable
@Immutable
data class SnapshotState(
    val schema: Int = SNAPSHOT_SCHEMA,
    val version: String,

    val fetchedAtMs: Long,

    val validUntilMs: Long,
    val failures: Int = 0,

    val complete: Boolean = true,
    val statusEtag: String? = null,
    val snapshotEtag: String? = null,
)

interface SnapshotStore {
    fun readBody(): String?
    fun readState(): SnapshotState?
    fun writeBody(json: String)
    fun writeState(state: SnapshotState)

    suspend fun readBodyIo(): String? = withContext(Dispatchers.IO) { readBody() }
    suspend fun readStateIo(): SnapshotState? = withContext(Dispatchers.IO) { readState() }
    suspend fun writeBodyIo(json: String) = withContext(Dispatchers.IO) { writeBody(json) }
    suspend fun writeStateIo(state: SnapshotState) = withContext(Dispatchers.IO) { writeState(state) }
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
        synchronized(this) {
            try {
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, target.name + ".tmp")
                FileOutputStream(tmp).use { fos ->
                    fos.write(text.toByteArray(Charsets.UTF_8))
                    fos.fd.sync()
                }
                try {
                    Files.move(
                        tmp.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(
                        tmp.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } catch (e: Exception) {
            }
        }
    }
}

class CachedSnapshot(val entries: List<CatalogEntry>, val complete: Boolean)

@Immutable
sealed interface RefreshOutcome {

    data object Skipped : RefreshOutcome

    data object Unchanged : RefreshOutcome

    class Updated(val entries: List<CatalogEntry>, val complete: Boolean) : RefreshOutcome

    data object Unsupported : RefreshOutcome

    data object Failed : RefreshOutcome
}
