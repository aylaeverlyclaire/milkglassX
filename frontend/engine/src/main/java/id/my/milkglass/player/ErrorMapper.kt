package id.my.milkglass.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import id.my.milkglass.resolver.FailureReason
import id.my.milkglass.resolver.StreamResolutionException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class FriendlyPlayerError(
    val reason: FailureReason,
    val userMessage: String,
    val cause: Throwable? = null
)

object ErrorMapper {

    const val MSG_NO_INTERNET = "Koneksimu lagi bermasalah."
    const val MSG_SOURCES_UNAVAILABLE = "Sumber video lagi tidak tersedia."

    fun classifyThrowable(error: Throwable): FailureReason {
        val chain = generateSequence(error) { it.cause }.take(6).toList()
        chain.firstNotNullOfOrNull { it as? StreamResolutionException }?.let { return it.reason }
        chain.firstNotNullOfOrNull { it as? HttpDataSource.InvalidResponseCodeException }?.let { http ->
            return when (http.responseCode) {
                403, 429 -> FailureReason.HTTP_403
                404 -> FailureReason.NOT_FOUND
                410 -> FailureReason.EXPIRED
                in 500..599 -> FailureReason.NETWORK
                else -> FailureReason.EXPIRED
            }
        }
        if (error is PlaybackException &&
            (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                chain.any { it.message?.contains("varint", ignoreCase = true) == true })
        ) {
            return FailureReason.CODEC
        }
        if (chain.any { it is UnknownHostException }) return FailureReason.DNS
        if (chain.any { it is SocketTimeoutException }) return FailureReason.TIMEOUT
        if (error is PlaybackException &&
            (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        ) {
            return FailureReason.NETWORK
        }
        if (chain.any { it is HttpDataSource.HttpDataSourceException || it is IOException }) return FailureReason.NETWORK
        return FailureReason.EXPIRED
    }

    fun mapExoPlayerError(error: PlaybackException, context: Context? = null): FriendlyPlayerError {
        val reason = classifyThrowable(error)
        val msg = if (context != null) toUserMessage(context, error) else MSG_SOURCES_UNAVAILABLE
        return FriendlyPlayerError(reason = reason, userMessage = msg, cause = error)
    }

    fun mapResolverError(error: Throwable, context: Context? = null): FriendlyPlayerError {
        val reason = classifyThrowable(error)
        val msg = if (context != null) toUserMessage(context, error) else MSG_SOURCES_UNAVAILABLE
        return FriendlyPlayerError(reason = reason, userMessage = msg, cause = error)
    }

    fun toUserMessage(context: Context, error: Throwable? = null): String {
        if (!isNetworkConnected(context)) {
            return MSG_NO_INTERNET
        }
        val reason = error?.let { classifyThrowable(it) }
        val detail = error?.message?.takeIf { it.isNotBlank() }
        return when (reason) {
            FailureReason.HTTP_403 -> detail ?: "Diblokir Cloudflare (403). Selesaikan verifikasi WebView."
            FailureReason.NOT_FOUND -> detail ?: "Episode tidak ditemukan di semua sumber."
            FailureReason.TIMEOUT -> "Koneksi timeout saat mengambil video dari sumber."
            FailureReason.NETWORK -> "Koneksi jaringan terganggu saat mengambil video dari sumber."
            FailureReason.CODEC -> {
                val codeName = (error as? PlaybackException)?.errorCodeName
                if (!codeName.isNullOrBlank()) {
                    "Codec/format tidak didukung perangkat ($codeName)."
                } else {
                    "Format video/codec tidak didukung perangkat."
                }
            }
            FailureReason.DNS -> "Gagal resolve DNS sumber video."
            else -> {
                val base = detail ?: MSG_SOURCES_UNAVAILABLE
                if (error is PlaybackException) {
                    val c = error.cause?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
                    listOfNotNull(base, "[${error.errorCodeName}]", c).joinToString(" ").take(220)
                } else base
            }
        }
    }

    private fun isNetworkConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
