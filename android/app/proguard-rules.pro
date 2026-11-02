-keepattributes Signature
-keepattributes *Annotation*
-keep class com.musicremover.app.data.** { *; }
-dontwarn okhttp3.**
-dontwarn retrofit2.**

# ONNX Runtime: the JNI layer looks up Java classes by name
-keep class ai.onnxruntime.** { *; }

# Gson DTOs for assets/models.json
-keep class com.musicremover.app.processing.internal.ModelCatalogJson { *; }
-keep class com.musicremover.app.processing.internal.ModelSpecJson { *; }
