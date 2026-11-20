import java.io.File
import java.util.zip.ZipFile
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.detekt)
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
        // packageReleaseResources only feeds lint and shrinking: it produces nothing that is signed.
        task.name.contains("Release", ignoreCase = true) &&
            !task.name.endsWith("Resources") &&
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
        // Release builds pass the values derived from the git tag (scripts/release_version.py); local builds keep these.
        versionCode = providers.gradleProperty("sunloom.versionCode").orNull?.toInt() ?: 13
        versionName = providers.gradleProperty("sunloom.versionName").orNull ?: "1.7.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // There is no native code. This keeps the package to the two Googlebook OS architectures (Article XVIII §1),
        // which also stops WorkManager/Compose from pulling in other ABIs; do not remove it as "unused".
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        base.archivesName.set(appName)
    }

    // Two distributions of one app (Article XVIII §1): "github" (sideloaded APK) may check GitHub for a newer
    // release; "play" contains no update code at all. Same applicationId and signing key, so one can replace the other.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") { dimension = "distribution" }
        create("play") { dimension = "distribution" }
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
        warningsAsErrors = true
        disable += setOf(
            "ObsoleteSdkInt",
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "NewerVersionAvailable",
            // Result depends on the newest platform installed where lint runs, so CI would turn red whenever a new
            // Android release ships. targetSdk is raised deliberately, after testing that release's behavior changes.
            "OldTargetApi"
        )
    }
}

detekt {
    // Static analysis gate (Phase 0 of the code-quality plan). Pre-existing findings live in the baseline so
    // that only new code is held to the rules; shrink the baseline as the large functions are split.
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml")
    source.setFrom("src/main/java")
    parallel = true
}

// Fast loop for the platform-independent core (Article XIII): `./gradlew :app:testDomain` runs only the tests under
// com.sunloom.app.domain, which are plain JVM tests (no Robolectric). The filter is applied only when this task was asked
// for by name, so `testGithubDebugUnitTest` and CI still run everything.
val domainOnly = gradle.startParameter.taskNames.any { it == "testDomain" || it.endsWith(":testDomain") }
tasks.register("testDomain") {
    group = "verification"
    description = "Runs only the domain-layer unit tests (GitHub flavor): the quick loop while working on the core."
    dependsOn("testGithubDebugUnitTest")
}
if (domainOnly) {
    tasks.withType<Test>().configureEach { filter.includeTestsMatching("com.sunloom.app.domain.*") }
}

// Mutation testing of the platform-independent core (a report to read, not a gate): `./gradlew :app:mutationDomain`
// changes the compiled domain classes one small way at a time (flips a comparison, drops a call) with PIT and re-runs
// the domain tests; a change no test notices ("survived") is a missing or weak test. Plain PIT through its command
// line rather than a Gradle plugin, which would have to keep up with the Android Gradle plugin. It reuses the unit-test
// classpath, so nothing extra is compiled. The report is app/build/reports/pitest/domain/index.html.
//   -Psunloom.mutation.classes=com.sunloom.app.domain.safety.*   narrows what is mutated
//   -Psunloom.mutation.tests=...                                  narrows which tests run (default: the domain tests)
//   -Psunloom.mutation.threads=N                                  parallel test runs (default 4)
val pitest: Configuration by configurations.creating
tasks.register<JavaExec>("mutationDomain") {
    group = "verification"
    description = "Mutation-tests the domain layer with PIT and writes a report (no pass/fail threshold)."
    val testTask = tasks.named<Test>("testGithubDebugUnitTest")
    val reportDir = layout.buildDirectory.dir("reports/pitest/domain")
    val classPathFile = layout.buildDirectory.file("pitest/classpath.txt")
    // The unit-test classpath carries the app's classes in a jar, and PIT mutates only code in directories: use the
    // compiled-classes directory instead (the same one the coverage report reads).
    val appClasses = layout.buildDirectory.dir("intermediates/built_in_kotlinc/githubDebug/compileGithubDebugKotlin/classes")
    val sourceDir = layout.projectDirectory.dir("src/main/java")
    val targetClasses = providers.gradleProperty("sunloom.mutation.classes").orElse("com.sunloom.app.domain.*")
    val targetTests = providers.gradleProperty("sunloom.mutation.tests").orElse("com.sunloom.app.domain.*")
    val threads = providers.gradleProperty("sunloom.mutation.threads").orElse("4")
    classpath = pitest
    mainClass.set("org.pitest.mutationtest.commandline.MutationCoverageReport")
    // Only what builds the classpath (the compile tasks), not the test task itself: PIT runs the tests its own way.
    dependsOn(provider { testTask.get().classpath }, "compileGithubDebugKotlin")
    outputs.upToDateWhen { false }
    notCompatibleWithConfigurationCache("reads the unit-test classpath when it runs")
    doFirst {
        val file = classPathFile.get().asFile
        file.parentFile.mkdirs()
        val appClassesDir = appClasses.get().asFile
        val entries = listOf(appClassesDir) + testTask.get().classpath.files.filter { it.exists() && "runtime_app_classes_jar" !in it.path }
        // Fail loudly rather than report nonsense: if the layout changes, PIT could mutate a copy the tests never load,
        // and every mutant would look like it survived.
        check(File(appClassesDir, "com/sunloom/app/domain").isDirectory) {
            "mutationDomain: no compiled domain classes in $appClassesDir (did the build output layout change?)"
        }
        // The domain tests live in the same package as the code, so look for copies of specific main-class files, not the package.
        val sample = File(appClassesDir, "com/sunloom/app/domain").walkTopDown().filter { it.extension == "class" }.take(5)
            .map { it.relativeTo(appClassesDir).invariantSeparatorsPath }.toList()
        check(sample.isNotEmpty()) { "mutationDomain: found no domain class files under $appClassesDir" }
        val staleCopies = entries.drop(1).filter { entry ->
            if (entry.isDirectory) sample.any { File(entry, it).exists() }
            else entry.extension == "jar" && ZipFile(entry).use { zip -> sample.any { zip.getEntry(it) != null } }
        }
        check(staleCopies.isEmpty()) {
            "mutationDomain: another classpath entry also holds the domain classes, so PIT could mutate a copy the tests do not load: $staleCopies"
        }
        file.writeText(entries.joinToString("\n") { it.absolutePath })
        args(
            "--reportDir", reportDir.get().asFile.absolutePath,
            "--classPathFile", file.absolutePath,
            "--sourceDirs", sourceDir.asFile.absolutePath,
            "--mutableCodePaths", appClassesDir.absolutePath,
            "--targetClasses", targetClasses.get(),
            "--targetTests", targetTests.get(),
            // Kotlin inserts a null check for every parameter; flipping those finds nothing about our code.
            "--avoidCallsTo", "kotlin.jvm.internal",
            "--mutators", "DEFAULTS",
            "--outputFormats", "HTML,XML,CSV",
            "--timestampedReports", "false",
            "--threads", threads.get(),
            "--jvmArgs", "-Xmx512m",
            "--verbose", "false"
        )
    }
}

composeCompiler {
    includeSourceInformation.set(false)
}

tasks.matching { it.name.contains("ComposeMapping", ignoreCase = true) }.configureEach {
    enabled = false
}

// Only the Play flavor ships a bundle; the GitHub flavor ships an APK.
tasks.matching { it.name == "packagePlayReleaseBundle" }.configureEach {
    doFirst {
        val mappingDir = layout.buildDirectory.dir("outputs/mapping/playRelease").get().asFile
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
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    pitest(libs.pitest.command.line)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // On-device smoke tests (src/androidTest), run on emulators in CI.
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.work.runtime.ktx)
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testGithubDebugUnitTest")
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
    val kotlinClasses = fileTree("${project.layout.buildDirectory.get()}/intermediates/built_in_kotlinc/githubDebug/compileGithubDebugKotlin/classes") {
        exclude(fileFilter)
    }
    classDirectories.setFrom(files(kotlinClasses))
    sourceDirectories.setFrom(files("${project.projectDir}/src/main/java"))
    executionData.setFrom(
        fileTree(project.layout.buildDirectory.get()) {
            include(
                "outputs/unit_test_code_coverage/githubDebugUnitTest/testGithubDebugUnitTest.exec",
                "jacoco/testGithubDebugUnitTest.exec"
            )
        }
    )
}

// Coverage gate (Article XVI). Reads per-package floors from coverage-floors.csv (`package,minLinePercent` and, for the
// packages that have one, `,minBranchPercent`) and fails when the jacocoTestReport CSV falls below any of them. Run by CI
// only: it is deliberately not wired into `check`, and the floors are set against CI's numbers, so on an arm64 dev box
// (which skips the real-SQLite and WorkManager tests) a few packages report lower than their floor.
tasks.register("coverageFloors") {
    dependsOn("jacocoTestReport")
    val report = layout.buildDirectory.file("reports/jacoco/jacocoTestReport/jacocoTestReport.csv")
    val floors = layout.projectDirectory.file("coverage-floors.csv")
    doLast {
        // Per package: line missed, line covered, branch missed, branch covered.
        val covered = mutableMapOf<String, LongArray>()
        report.get().asFile.readLines().drop(1).filter { it.isNotBlank() }.forEach { line ->
            val c = line.split(',')
            val acc = covered.getOrPut(c[1]) { LongArray(4) }
            acc[0] += c[7].toLong()
            acc[1] += c[8].toLong()
            acc[2] += c[5].toLong()
            acc[3] += c[6].toLong()
        }
        val failures = mutableListOf<String>()
        floors.asFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
            val parts = line.split(',').map { it.trim() }
            val pkg = parts[0]
            val minLine = parts.getOrNull(1)?.toDoubleOrNull()
                ?: throw GradleException("coverage-floors.csv: '$line' needs a numeric line floor after the package")
            val minBranch = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let {
                it.toDoubleOrNull() ?: throw GradleException("coverage-floors.csv: '$line' has a branch floor that is not a number")
            }
            val acc = covered[pkg] ?: run { failures += "$pkg: no coverage data"; return@forEach }
            val linePct = 100.0 * acc[1] / (acc[0] + acc[1]).coerceAtLeast(1)
            if (linePct + 1e-9 < minLine) failures += "%.1f%% of lines in %s is below the %s%% floor".format(linePct, pkg, parts[1])
            if (minBranch != null) {
                val branches = acc[2] + acc[3]
                if (branches == 0L) {
                    failures += "$pkg: a branch floor is set but the report has no branch data"
                } else if (100.0 * acc[3] / branches + 1e-9 < minBranch) {
                    failures += "%.1f%% of branches in %s is below the %s%% floor".format(100.0 * acc[3] / branches, pkg, parts[2])
                }
            }
        }
        if (failures.isNotEmpty()) throw GradleException("Coverage fell below its floor:\n  " + failures.joinToString("\n  "))
        println("Coverage floors met for ${floors.asFile.readLines().count { it.isNotBlank() && !it.startsWith("#") }} packages.")
    }
}
