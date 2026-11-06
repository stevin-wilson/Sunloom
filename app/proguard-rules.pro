# Sunloom ProGuard Rules
-keep class com.sunloom.app.domain.model.** { *; }

# Keep WorkManager CoroutineWorker subclasses and their reflective constructors for Play Store R8 builds
-keep class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
