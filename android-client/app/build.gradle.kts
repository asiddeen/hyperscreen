plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun resolveGitCommitCount(): Int {
    return runCatching {
        val rootDir = rootProject.rootDir.parentFile ?: rootProject.rootDir
        val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .directory(rootDir)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val text = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        val count = text.toInt()
        if (count > 0) count else 1
    }.getOrDefault(1)
}

fun resolveGitCommitHash(): String {
    return runCatching {
        val rootDir = rootProject.rootDir.parentFile ?: rootProject.rootDir
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(rootDir)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val hash = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        if (hash.isNotEmpty()) hash else "unknown"
    }.getOrDefault("unknown")
}

val versionFile = rootProject.file("../VERSION")
val appVersionName = if (versionFile.exists()) versionFile.readText().trim() else "1.0.0"
val versionParts = appVersionName.split(".")
val gitCommitCount = resolveGitCommitCount()
val gitCommitHash = resolveGitCommitHash()

val appVersionCode = runCatching {
    val major = versionParts.getOrElse(0) { "1" }.toInt()
    val minor = versionParts.getOrElse(1) { "0" }.toInt()
    major * 100000 + minor * 10000 + gitCommitCount
}.getOrDefault(gitCommitCount)

android {
    namespace = "dev.hyperscreen.client"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "dev.hyperscreen.client"
        minSdk = 30 // Android 11+ for native MediaFormat.KEY_LOW_LATENCY & unbuffered stylus prediction
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "GIT_HASH", "\"$gitCommitHash\"")
        buildConfigField("int", "GIT_COMMIT_COUNT", "$gitCommitCount")

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20 -O3 -Wall"
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                )
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.input:input-motionprediction:1.0.0-beta05")
    implementation("com.google.zxing:core:3.5.3")
}
