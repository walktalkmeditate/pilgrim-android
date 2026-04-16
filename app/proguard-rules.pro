# Project-specific ProGuard / R8 rules for Pilgrim.
#
# Compose + Hilt + Kotlin coroutines rules are handled by default consumer rules
# shipped with those libraries. Add project-specific keeps here as features land.

# Keep line numbers so stack traces remain meaningful in crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
