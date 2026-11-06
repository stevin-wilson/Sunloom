package com.sunloom.app

import com.sunloom.app.data.repository.InMemoryLibraryStore
import com.sunloom.app.data.repository.LocalLibraryRepository
import java.io.File

/**
 * Installs test-only persistence & seed-sizing overrides inside Robolectric's sandbox classloader
 * so production classes carry zero Robolectric / CPU-architecture checks.
 */
object TestEnvironmentBootstrap {
    @JvmStatic
    fun install() {
        LocalLibraryRepository.clearCachedDefaultStoreForTests()
        LocalLibraryRepository.testStoreFactoryOverride = { ctx ->
            InMemoryLibraryStore(File(ctx.filesDir, "sunloom_store_v2"))
        }
        LocalLibraryRepository.defaultMaxSeedRenderPixels = 640 * 400
    }
}
