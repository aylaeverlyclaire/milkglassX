package id.my.milkglass

import androidx.media3.common.MimeTypes
import id.my.milkglass.player.PlayerManager
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerMimeTest {
    @Test fun unknownLocalStreamDefaultsToMatroska() {
        assertEquals(MimeTypes.VIDEO_MATROSKA, PlayerManager.mimeTypeFor("http://127.0.0.1:8123/stream/abc"))
        assertEquals(MimeTypes.VIDEO_MATROSKA, PlayerManager.mimeTypeFor("http://127.0.0.1:8123/v/ep1.mkv?x=1"))
    }

    @Test fun explicitContainersKeepTheirType() {
        assertEquals(MimeTypes.VIDEO_MP4, PlayerManager.mimeTypeFor("https://h/a/b.mp4?token=1"))
        assertEquals(MimeTypes.VIDEO_WEBM, PlayerManager.mimeTypeFor("https://h/a/b.webm"))
        assertEquals(MimeTypes.APPLICATION_M3U8, PlayerManager.mimeTypeFor("https://h/a/index.m3u8"))
    }
}
