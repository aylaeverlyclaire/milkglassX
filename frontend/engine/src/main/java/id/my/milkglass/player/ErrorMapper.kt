package id.my.milkglass.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import id.my.milkglass.resolver.FailureReason
import id.my.milkglass.resolver.StreamResolutionException
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
        val cause = error.cause ?: error
        return when {
            error is StreamResolutionException -> error.reason
            cause is StreamResolutionException -> cause.reason
            cause is HttpDataSource.InvalidResponseCodeException -> {
                when (cause.responseCode) {
                    403, 429 -> FailureReason.HTTP_403
                    404 -> FailureReason.NOT_FOUND
                    410 -> FailureReason.EXPIRED
                    else -> FailureReason.EXPIRED
                }
            }
            error is PlaybackException &&
                (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) -> {
                FailureReason.CODEC
            }
            cause is UnknownHostException -> FailureReason.DNS
            cause is SocketTimeoutException -> FailureReason.TIMEOUT
            else -> FailureReason.EXPIRED
        }
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
            FailureReason.NOT_FOUND -> detail ?: "Episode MKV tidak ditemukan di Otakudesu, Samehadaku, Oploverz, maupun Kusonime."
            FailureReason.TIMEOUT -> "Koneksi timeout saat mengambil video dari sumber."
            FailureReason.CODEC -> "Format video/codec tidak didukung oleh ExoPlayer (butuh libmpv)."
            FailureReason.DNS -> "Gagal resolve DNS sumber video."
            else -> detail ?: MSG_SOURCES_UNAVAILABLE
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
