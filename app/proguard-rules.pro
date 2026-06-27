# ─── StealthGuard ProGuard Rules ──────────────────────────────────────────────
# Applied only to release builds (minifyEnabled true in build.gradle).
# The debug build skips ProGuard entirely for faster iteration.

# ─── Android / Kotlin basics ──────────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes Exceptions

# ─── Room Database ────────────────────────────────────────────────────────────
# Room generates code at compile time (kapt). The generated _Impl classes
# must not be renamed or removed or the DB will fail to open at runtime.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-dontwarn androidx.room.paging.**

# ─── Kotlin Coroutines ────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ─── Google Play Services (Location / Tasks) ──────────────────────────────────
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# ─── WorkManager ──────────────────────────────────────────────────────────────
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ─── Jetpack Security (EncryptedSharedPreferences) ────────────────────────────
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# ─── SpeechRecognizer ─────────────────────────────────────────────────────────
-keep class android.speech.** { *; }

# ─── App-specific classes that must survive obfuscation ───────────────────────
# BroadcastReceivers and Services registered in the manifest must keep their
# class names because the OS looks them up by name at runtime.
-keep class com.system.cacheclean.service.BootReceiver { *; }
-keep class com.system.cacheclean.service.GuardForegroundService { *; }
-keep class com.system.cacheclean.service.StealthAccessibilityService { *; }
-keep class com.system.cacheclean.service.ServiceRestartWorker { *; }
-keep class com.system.cacheclean.sos.SOSManager { *; }
-keep class com.system.cacheclean.ui.FakeCallActivity { *; }
-keep class com.system.cacheclean.ui.MainActivity { *; }
-keep class com.system.cacheclean.ui.AdminActivity { *; }

# ─── Entities (Room field names used via reflection) ─────────────────────────
-keep class com.system.cacheclean.db.entity.** { *; }

# ─── Enum classes ─────────────────────────────────────────────────────────────
# Kotlin enum .name / .valueOf() used throughout (Gender, AudioType, CallState)
-keepclassmembers enum com.system.cacheclean.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ─── ViewBinding ──────────────────────────────────────────────────────────────
-keep class com.system.cacheclean.databinding.** { *; }

# ─── Suppress common warnings that don't affect functionality ─────────────────
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
