-keepattributes Signature
-keepattributes *Annotation*
-keep class com.musicremover.app.data.** { *; }
-dontwarn okhttp3.**
-dontwarn retrofit2.**

# ONNX Runtime: the JNI layer looks up Java classes by name
-keep class ai.onnxruntime.** { *; }

# Gson TypeToken subclasses need their generic signature (HistoryStore, MainViewModel)
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Gson DTOs for assets/models.json
-keep class com.musicremover.app.processing.internal.ModelCatalogJson { *; }
-keep class com.musicremover.app.processing.internal.ModelSpecJson { *; }
# Gson DTOs for filesDir/cookies/sites.json
-keep class com.musicremover.app.processing.internal.CookieIndexJson { *; }
-keep class com.musicremover.app.processing.internal.CookieSiteJson { *; }

# youtubedl-android: JNI and runtime asset lookups, and the unzip of the Python/ffmpeg packages
-keep class com.yausername.** { *; }
-keep class org.apache.commons.compress.archivers.zip.** { *; }
