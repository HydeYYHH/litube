# Optimization and Obfuscation
# ----------------------------

# Enable aggressive optimizations
-optimizationpasses 5
-allowaccessmodification
-mergeinterfacesaggressively
-repackageclasses ''
-overloadaggressively

# General keeps
-keepattributes Signature,EnclosingMethod,InnerClasses,*Annotation*,JavascriptInterface
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Suppress Kotlin Metadata warnings
-dontwarn kotlin.Metadata
-dontwarn kotlin.jvm.JvmDefault
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Gson
# ----
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers class * extends com.google.gson.reflect.TypeToken { *; }

# Data Models (Keep fields for Gson serialization)
-keepclassmembers class com.hhst.youtubelite.extractor.** { <fields>; }
-keepclassmembers class com.hhst.youtubelite.downloader.core.history.** { <fields>; }
-keep class com.hhst.youtubelite.extension.Extension { *; }
-keepclassmembers class com.hhst.youtubelite.extension.Extension { <fields>; }

# JavaScript Interface
# --------------------
-keepclassmembers class com.hhst.youtubelite.browser.JavascriptInterface {
   @android.webkit.JavascriptInterface <methods>;
}

# NewPipe Extractor
# -----------------
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }

# OkHttp / Okio
# -------------
-dontwarn okio.**
-dontwarn okhttp3.**
-dontwarn com.squareup.okhttp3.**
-keep class com.squareup.okhttp3.** { *; }
-keep interface com.squareup.okhttp3.** { *; }

# Mp4Parser / IsoParser
# ---------------------
-keep class com.googlecode.mp4parser.** { *; }
-keep class com.coremedia.iso.** { *; }
-keep class com.mp4parser.** { *; }
-dontwarn java.awt.**
-dontwarn javax.imageio.**

# Glide
# -----
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep public class * extends com.bumptech.glide.module.LibraryGlideModule
-dontwarn com.bumptech.glide.GeneratedAppGlideModuleImpl
-keep class com.bumptech.glide.*GeneratedAppGlideModuleImpl { *; }
-dontwarn com.bumptech.glide.load.resource.bitmap.VideoDecoder

# Dagger Hilt
# -----------
-keep class dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keep class * extends androidx.lifecycle.ViewModel
-keep @dagger.hilt.android.AndroidEntryPoint class *
-keep @dagger.hilt.android.lifecycle.HiltViewModel class *

# MMKV
# ----
-keep class com.tencent.mmkv.** { *; }

# Media3 / ExoPlayer
# ------------------
-keep class androidx.media3.common.util.UnstableApi
-keep class androidx.media3.exoplayer.dash.DashMediaSource$Factory
-dontwarn androidx.media3.**

# Suppress common library warnings
# --------------------------------
-dontwarn com.google.common.collect.ArrayListMultimap
-dontwarn com.google.common.collect.Multimap
-dontwarn javax.money.**
-dontwarn org.javamoney.**
-dontwarn org.joda.time.**
-dontwarn springfox.documentation.**
-dontwarn java.beans.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
