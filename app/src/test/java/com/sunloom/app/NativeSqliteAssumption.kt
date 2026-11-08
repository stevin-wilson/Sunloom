package com.sunloom.app

import org.junit.Assume.assumeTrue

/**
 * Robolectric's native runtime (real SQLite, which WorkManager and `SqliteLibraryStore` need) has no linux-aarch64
 * build, so tests that need it skip on the aarch64 dev box and run on CI's x86_64 runner.
 */
object NativeSqliteAssumption {
    fun assumeAvailable() {
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        assumeTrue("Robolectric native SQLite is unavailable on $arch; runs on CI (x86_64)", arch != "aarch64" && arch != "arm64")
    }
}
