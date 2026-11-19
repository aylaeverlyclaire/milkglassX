-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.milkglass.app.**$$serializer { *; }
-keepclassmembers class com.milkglass.app.** { *** Companion; }
-keepclasseswithmembers class com.milkglass.app.** { kotlinx.serialization.KSerializer serializer(...); }

-keep public class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class id.my.milkglass.download.MkvDownloadWorker { *; }
-keep class id.my.milkglass.download.MkvDownloadManager { *; }
-keep class is.xyz.mpv.** { *; }
-dontwarn is.xyz.mpv.**
-dontwarn org.jsoup.**

# Dekoder FFmpeg (JNI) dimuat lewat refleksi oleh DefaultRenderersFactory
-keep class androidx.media3.decoder.ffmpeg.** { *; }
