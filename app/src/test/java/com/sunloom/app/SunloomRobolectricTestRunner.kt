package com.sunloom.app

import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.internal.SandboxTestRunner
import org.robolectric.internal.bytecode.InstrumentationConfiguration
import org.robolectric.internal.bytecode.Sandbox
import java.lang.reflect.Method

/**
 * Custom RobolectricTestRunner that:
 * 1. Works around Robolectric's prefix bug where `PACKAGES_TO_NEVER_ACQUIRE` contains `"com.sun"`
 *    (without a trailing dot), which inadvertently matches `"com.sunloom.*"`, and
 * 2. Bootstraps [TestEnvironmentBootstrap] inside each Robolectric sandbox classloader so
 *    production code remains 100% free of Robolectric or `os.arch` branches.
 */
class SunloomRobolectricTestRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {
    override fun createClassLoaderConfig(method: FrameworkMethod?): InstrumentationConfiguration {
        val base = super.createClassLoaderConfig(method)
        val builder = InstrumentationConfiguration.Builder(base)
        return object : InstrumentationConfiguration(
            builder.classNameTranslations,
            builder.interceptedMethods,
            builder.instrumentedPackages,
            builder.instrumentedClasses,
            builder.classesToNotAcquire,
            builder.packagesToNotAcquire,
            builder.classesToNotInstrument,
            builder.packagesToNotInstrument,
            builder.classesToNotInstrumentRegex
        ) {
            override fun shouldAcquire(name: String): Boolean {
                if (name.startsWith("com.sunloom.app.SunloomRobolectricTestRunner")) {
                    return false
                }
                if (name.startsWith("com.sunloom.")) {
                    return !builder.classesToNotAcquire.contains(name)
                }
                return super.shouldAcquire(name)
            }
        }
    }

    override fun beforeTest(sandbox: Sandbox, method: FrameworkMethod, bootstrappedMethod: Method) {
        super.beforeTest(sandbox, method, bootstrappedMethod)
        runCatching {
            val cls = bootstrappedMethod.declaringClass.classLoader
                ?.loadClass("com.sunloom.app.TestEnvironmentBootstrap")
            cls?.getMethod("install")?.invoke(null)
        }
    }
}
