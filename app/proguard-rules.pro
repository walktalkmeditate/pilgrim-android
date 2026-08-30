# Project-specific ProGuard / R8 rules for Pilgrim.
#
# Compose + Hilt + Kotlin coroutines rules are handled by default consumer rules
# shipped with those libraries. Add project-specific keeps here as features land.

# Keep line numbers so stack traces remain meaningful in crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx-serialization (Stage 5-C, Stage 10-I): keep synthetic
# `$serializer` classes + `Companion.serializer()` accessors that
# R8 would otherwise rename. Without these, release builds throw
# `SerializationException: Serializer for class 'X' is not found`
# on the first call.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep all Pilgrim-package and network-DTO @Serializable companions
# + their generated $$serializer synthetic classes.
-keep,includedescriptorclasses class org.walktalkmeditate.pilgrim.**$$serializer { *; }
-keepclassmembers class org.walktalkmeditate.pilgrim.** {
    *** Companion;
}
-keepclasseswithmembers class org.walktalkmeditate.pilgrim.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ML Kit (Stage 13-XZ): the on-device vision/label/face/text detectors
# load their flavor implementations reflectively at static init via
# `MultiFlavorDetectorCreator`. R8 strips the reflectively-loaded
# classes, leaving its internal HashMap field null, and the first
# `ImageLabeling.getClient(...)` call NPEs from inside the Hilt graph
# (observed crash: `MlKitImageLabelerClient.<init>` while resolving a
# @HiltViewModel during Compose composition on Play release builds).
# Keep both the public surface and the bundled internal flavors so the
# reflective registry resolves.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_**

# whisper.cpp JNI (Phase 20 U5): `whisper-jni.cpp`'s
# nativeTranscribeSegments constructs WhisperSegment via a raw
# FindClass + GetMethodID + NewObject sequence — R8 has no visibility
# into that JNI reflection, so an unminified ctor name/signature
# mismatch after minification returns a null jclass/jmethodID in
# native code (not a Kotlin exception), and the ONLY decode path
# TranscriptionRunner calls (transcribeWithSegments) fails on every
# release-build transcription. Keep the exact constructor the native
# side resolves by descriptor (Ljava/lang/String;JJF)V.
-keep class org.walktalkmeditate.pilgrim.audio.WhisperSegment {
    <init>(java.lang.String, long, long, float);
}
