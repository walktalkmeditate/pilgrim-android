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
