# Sunloom ProGuard Rules
-keep class com.sunloom.app.domain.model.** { *; }

# Keep WorkManager CoroutineWorker subclasses and their reflective constructors for Play Store R8 builds
-keep class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Release builds drop verbose, debug and info logging so it never reaches a person's logcat. Warnings and errors stay:
# they are what a bug report needs.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
