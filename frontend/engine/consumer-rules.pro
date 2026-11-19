# Consumer ProGuard / R8 rules for Milkglass Android Engine module

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>(...);
}
-keep class id.my.milkglass.data.** { *; }
-dontwarn androidx.room.paging.**

# WorkManager
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class id.my.milkglass.download.MkvDownloadWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Media3 / ExoPlayer (including HLS & OkHttp DataSource)
-keep class androidx.media3.exoplayer.hls.HlsMediaSource$Factory {
    public <init>(androidx.media3.datasource.DataSource$Factory);
}
-keep class androidx.media3.datasource.okhttp.OkHttpDataSource$Factory { *; }
-dontwarn androidx.media3.**

# Jsoup
-keep class org.jsoup.nodes.** { *; }
-dontwarn org.jsoup.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class id.my.milkglass.**$$serializer { *; }
-keepclassmembers class id.my.milkglass.** {
    *** Companion;
}
-keepclasseswithmembers class id.my.milkglass.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Optional MPVLib reflection bridge
-dontwarn is.xyz.mpv.**
-keep class is.xyz.mpv.MPVLib { *; }
