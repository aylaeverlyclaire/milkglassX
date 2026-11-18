package id.my.milkglass.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "milkglass_session_datastore"
)

internal fun isTrustedBackendHost(host: String): Boolean {
    return host.equals("milkglass.my.id", ignoreCase = true) ||
        host.endsWith(".milkglass.my.id", ignoreCase = true)
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
            val loaded = runCatching {
                runBlocking(Dispatchers.IO) {
                    withTimeout(2_000L) {
                        dataStore.data.map { prefs -> prefs[KEY_REFRESH_TOKEN] }.first()
                    }
                }
            }.getOrNull()
            cachedRefreshToken = loaded
            isLoadedFromDisk = true
            return loaded
        }
    }

    fun saveTokensBlocking(newAccessToken: String, newRefreshToken: String) {
        synchronized(diskLock) {
            accessToken = newAccessToken
            cachedRefreshToken = newRefreshToken
            isLoadedFromDisk = true
            runCatching {
                runBlocking(Dispatchers.IO) {
                    withTimeout(2_000L) {
                        dataStore.edit { prefs ->
                            prefs[KEY_REFRESH_TOKEN] = newRefreshToken
                        }
                    }
                }
            }
        }
    }

    fun saveTokensAsync(newAccessToken: String, newRefreshToken: String) {
        synchronized(diskLock) {
            accessToken = newAccessToken
            cachedRefreshToken = newRefreshToken
            isLoadedFromDisk = true
        }
        ioScope.launch {
            runCatching {
                dataStore.edit { prefs ->
                    prefs[KEY_REFRESH_TOKEN] = newRefreshToken
                }
            }
        }
    }

    fun clearSession() {
        synchronized(diskLock) {
            accessToken = null
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
    }
}

class AuthBearerInterceptor(
    private val tokenStore: SessionTokenStore
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!isTrustedBackendHost(original.url.host)) {
            return chain.proceed(original)
        }

        val jwt = tokenStore.accessToken
        val req = if (!jwt.isNullOrBlank() && original.header("Authorization") == null) {
            original.newBuilder()
                .header("Authorization", "Bearer $jwt")
                .build()
        } else {
            original
        }
        return chain.proceed(req)
    }
}

class SynchronizedTokenAuthenticator(
    private val tokenStore: SessionTokenStore,
    refreshHttpClient: OkHttpClient,
    private val apiBaseUrl: String = "https://milkglass.my.id",
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
                            .url("$apiBaseUrl/v1/auth/refresh")
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

                            tokenStore.saveTokensBlocking(newAccess, newRefresh)

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
