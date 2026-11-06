import java.io.File
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    id("jacoco")
}

val appName = "Sunloom"

val uploadStoreFilePath: String? =
    System.getenv("SUNLOOM_UPLOAD_STORE_FILE")
        ?: System.getenv("ANDROID_UPLOAD_KEYSTORE_PATH")
        ?: providers.gradleProperty("sunloom.upload.storeFile").orNull

val uploadStorePassword: String? =
    System.getenv("SUNLOOM_UPLOAD_STORE_PASSWORD")
        ?: System.getenv("ANDROID_UPLOAD_KEYSTORE_PASSWORD")
        ?: System.getenv("KEYSTORE_PASSWORD")
        ?: providers.gradleProperty("sunloom.upload.storePassword").orNull

val uploadKeyAlias: String? =
    System.getenv("SUNLOOM_UPLOAD_KEY_ALIAS")
        ?: System.getenv("ANDROID_UPLOAD_KEY_ALIAS")
        ?: providers.gradleProperty("sunloom.upload.keyAlias").orNull

val uploadKeyPassword: String? =
    System.getenv("SUNLOOM_UPLOAD_KEY_PASSWORD")
        ?: System.getenv("ANDROID_UPLOAD_KEY_PASSWORD")
        ?: System.getenv("KEY_PASSWORD")
        ?: providers.gradleProperty("sunloom.upload.keyPassword").orNull

val hasUploadSigningCredentials =
    !uploadStoreFilePath.isNullOrBlank() &&
        File(uploadStoreFilePath).exists() &&
        !uploadStorePassword.isNullOrBlank() &&
        !uploadKeyAlias.isNullOrBlank() &&
        !uploadKeyPassword.isNullOrBlank()

gradle.taskGraph.whenReady {
    val isReleaseBuildRequested = allTasks.any { task ->
        task.name.contains("Release", ignoreCase = true) &&
            (task.name.startsWith("assemble") || task.name.startsWith("bundle") || task.name.startsWith("package"))
    }
    if (isReleaseBuildRequested && !hasUploadSigningCredentials) {
        throw GradleException(
            "Release build requires external upload signing credentials. " +
                "Provide SUNLOOM_UPLOAD_STORE_FILE, SUNLOOM_UPLOAD_STORE_PASSWORD, " +
                "SUNLOOM_UPLOAD_KEY_ALIAS, and SUNLOOM_UPLOAD_KEY_PASSWORD in environment " +
                "or sunloom.upload.* in user-level gradle.properties."
        )
    }
}

android {
    namespace = "com.sunloom.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sunloom.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "1.7.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        base.archivesName.set(appName)
    }

    signingConfigs {
        create("release") {
            if (hasUploadSigningCredentials) {
                storeFile = File(requireNotNull(uploadStoreFilePath))
                storePassword = requireNotNull(uploadStorePassword)
                keyAlias = requireNotNull(uploadKeyAlias)
                keyPassword = requireNotNull(uploadKeyPassword)
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasUploadSigningCredentials) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.maxHeapSize = "512m"
                it.maxParallelForks = 1
                val cacheHome = System.getenv("ANDROID_USER_HOME")?.let { p -> File(p).parent }
                    ?: System.getProperty("user.home")
                it.systemProperty("user.home", cacheHome)
                it.systemProperty("android.junit.runner", "com.sunloom.app.SunloomRobolectricTestRunner")
                it.extensions.configure(org.gradle.testing.jacoco.plugins.JacocoTaskExtension::class.java) {
                    isIncludeNoLocationClasses = true
                    excludes = listOf("jdk.internal.*")
                }
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        disable += setOf(
            "ObsoleteSdkInt",
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "NewerVersionAvailable"
        )
    }
}

composeCompiler {
    includeSourceInformation.set(false)
}

tasks.matching { it.name.contains("ComposeMapping", ignoreCase = true) }.configureEach {
    enabled = false
}

tasks.matching { it.name == "packageReleaseBundle" }.configureEach {
    doFirst {
        val mappingDir = layout.buildDirectory.dir("outputs/mapping/release").get().asFile
        val prtFile = File(mappingDir, "mapping.prt")
        val txtFile = File(mappingDir, "mapping.txt")
        if (!txtFile.exists() && prtFile.exists()) {
            prtFile.copyTo(txtFile, overwrite = true)
        }
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    testImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(true)
    }
    val fileFilter = listOf(
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*Test*.*",
        "android/**/*.*",
        "**/ComposableSingletons*.*"
    )
    val kotlinClasses = fileTree("${project.layout.buildDirectory.get()}/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes") {
        exclude(fileFilter)
    }
    classDirectories.setFrom(files(kotlinClasses))
    sourceDirectories.setFrom(files("${project.projectDir}/src/main/java"))
    executionData.setFrom(
        fileTree(project.layout.buildDirectory.get()) {
            include(
                "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
                "jacoco/testDebugUnitTest.exec"
            )
        }
    )
}
