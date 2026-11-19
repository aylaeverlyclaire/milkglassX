package id.my.milkglass.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import id.my.milkglass.MilkglassEndpoints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "milkglass_session_datastore"
)

internal fun isTrustedBackendHost(host: String): Boolean =
    MilkglassEndpoints.isTrustedBackendHost(host)

internal fun computeHmacSha256Hex(key: String, message: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val digest = mac.doFinal(message.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
        sb.append(String.format("%02x", b))
    }
    return sb.toString()
}

internal fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length || a.isEmpty()) return false
    var diff = 0
    for (i in a.indices) {
        diff = diff or (a[i].code xor b[i].code)
    }
    return diff == 0
}

class SessionTokenStore(
    context: Context,
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val dataStore = context.applicationContext.sessionDataStore
    private val diskLock = Any()

    @Volatile
    var accessToken: String? = null

    @Volatile
    var proofKey: String? = null

    @Volatile
    private var cachedRefreshToken: String? = null

    @Volatile
    private var isLoadedFromDisk: Boolean = false

    fun hasActiveSession(): Boolean {
        if (!accessToken.isNullOrBlank() || !cachedRefreshToken.isNullOrBlank()) {
            return true
        }
        return !getRefreshTokenBlocking().isNullOrBlank()
    }

    fun getRefreshTokenBlocking(): String? {
        if (isLoadedFromDisk) {
            return cachedRefreshToken
        }
        synchronized(diskLock) {
            if (isLoadedFromDisk) {
                return cachedRefreshToken
            }
            val loadedPair = runCatching {
                runBlocking(Dispatchers.IO) {
                    withTimeout(2_000L) {
                        dataStore.data.map { prefs -> prefs[KEY_REFRESH_TOKEN] to prefs[KEY_PROOF_KEY] }.first()
                    }
                }
            }.getOrNull()
            cachedRefreshToken = loadedPair?.first
            if (proofKey.isNullOrBlank()) proofKey = loadedPair?.second
            isLoadedFromDisk = true
            return cachedRefreshToken
        }
    }

    fun saveTokensBlocking(newAccessToken: String, newRefreshToken: String, newProofKey: String? = null) {
        synchronized(diskLock) {
            accessToken = newAccessToken
            cachedRefreshToken = newRefreshToken
            if (!newProofKey.isNullOrBlank()) proofKey = newProofKey
            isLoadedFromDisk = true
            runCatching {
                runBlocking(Dispatchers.IO) {
                    withTimeout(2_000L) {
                        dataStore.edit { prefs ->
                            prefs[KEY_REFRESH_TOKEN] = newRefreshToken
                            if (!newProofKey.isNullOrBlank()) prefs[KEY_PROOF_KEY] = newProofKey
                        }
                    }
                }
            }
        }
    }

    fun saveTokensAsync(newAccessToken: String, newRefreshToken: String, newProofKey: String? = null) {
        synchronized(diskLock) {
            accessToken = newAccessToken
            cachedRefreshToken = newRefreshToken
            if (!newProofKey.isNullOrBlank()) proofKey = newProofKey
            isLoadedFromDisk = true
        }
        ioScope.launch {
            runCatching {
                dataStore.edit { prefs ->
                    prefs[KEY_REFRESH_TOKEN] = newRefreshToken
                    if (!newProofKey.isNullOrBlank()) prefs[KEY_PROOF_KEY] = newProofKey
                }
            }
        }
    }

    fun verifyServerSignature(body: String, signatureHeader: String?): Boolean {
        val pk = proofKey?.takeIf { it.isNotBlank() } ?: return true
        val sig = signatureHeader?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return true
        val expected = computeHmacSha256Hex(pk, body)
        return constantTimeEquals(sig, expected)
    }

    fun clearSession() {
        synchronized(diskLock) {
            accessToken = null
            proofKey = null
            cachedRefreshToken = null
            isLoadedFromDisk = true
        }
        ioScope.launch {
            runCatching {
                dataStore.edit { prefs ->
                    prefs.clear()
                }
            }
        }
    }

    companion object {
        private val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        private val KEY_PROOF_KEY = stringPreferencesKey("proof_key")
    }
}

class AuthBearerInterceptor(
    private val tokenStore: SessionTokenStore
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!original.isHttps || !isTrustedBackendHost(original.url.host)) {
            return chain.proceed(original)
        }

        val jwt = tokenStore.accessToken
        val builder = original.newBuilder()
        var modified = false
        if (!jwt.isNullOrBlank() && original.header("Authorization") == null) {
            builder.header("Authorization", "Bearer $jwt")
            modified = true
        }
        val pk = tokenStore.proofKey
        if (!pk.isNullOrBlank() && original.header("X-Milkglass-Sig") == null) {
            val ts = (System.currentTimeMillis() / 1000L).toString()
            val pathAndQuery = original.url.encodedPath + (original.url.encodedQuery?.let { "?$it" } ?: "")
            val canonical = "${original.method.uppercase()}\n$pathAndQuery\n$ts"
            val sig = computeHmacSha256Hex(pk, canonical)
            builder.header("X-Milkglass-Ts", ts)
            builder.header("X-Milkglass-Sig", sig)
            modified = true
        }
        return chain.proceed(if (modified) builder.build() else original)
    }
}

class SynchronizedTokenAuthenticator(
    private val tokenStore: SessionTokenStore,
    refreshHttpClient: OkHttpClient,
    private val apiBaseUrl: String = MilkglassEndpoints.DEFAULT_BACKEND_BASE_URL,
    private val onRequireReLogin: () -> Unit = {}
) : Authenticator {

    private val refreshMutex = Mutex()

    private val cleanRefreshClient: OkHttpClient = refreshHttpClient.newBuilder()
        .authenticator(Authenticator.NONE)
        .build()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (!isTrustedBackendHost(response.request.url.host)) return null
        if (responseCount(response) >= 2) return null

        val failedBearer = response.request.header("Authorization")
            ?.removePrefix("Bearer ")
            ?.trim()

        return try {
            runBlocking(Dispatchers.IO) {
                withTimeout(10_000L) {
                    refreshMutex.withLock {
                        val currentInMemoryJwt = tokenStore.accessToken

                        if (!currentInMemoryJwt.isNullOrBlank() && currentInMemoryJwt != failedBearer) {
                            return@withLock response.request.newBuilder()
                                .header("Authorization", "Bearer $currentInMemoryJwt")
                                .build()
                        }

                        val savedRefreshToken = tokenStore.getRefreshTokenBlocking()
                        if (savedRefreshToken.isNullOrBlank()) {
                            tokenStore.clearSession()
                            if (!failedBearer.isNullOrBlank()) {
                                onRequireReLogin()
                            }
                            return@withLock null
                        }

                        val payload = JSONObject()
                            .put("refresh_token", savedRefreshToken)
                            .toString()

                        val refreshReq = Request.Builder()
                            .url("${apiBaseUrl.trimEnd('/')}/v1/auth/refresh")
                            .post(payload.toRequestBody("application/json".toMediaType()))
                            .build()

                        cleanRefreshClient.newCall(refreshReq).execute().use { refreshRes ->
                            if (!refreshRes.isSuccessful) {
                                if (refreshRes.code == 401) {
                                    tokenStore.clearSession()
                                    onRequireReLogin()
                                }
                                return@withLock null
                            }

                            val bodyStr = refreshRes.body?.string().orEmpty()
                            val dataObj = JSONObject(bodyStr).optJSONObject("data") ?: return@withLock null
                            val newAccess = dataObj.getString("access_token")
                            val newRefresh = dataObj.getString("refresh_token")
                            val newProofKey = dataObj.optString("proof_key").takeIf { it.isNotBlank() }

                            tokenStore.saveTokensBlocking(newAccess, newRefresh, newProofKey)

                            response.request.newBuilder()
                                .header("Authorization", "Bearer $newAccess")
                                .build()
                        }
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
