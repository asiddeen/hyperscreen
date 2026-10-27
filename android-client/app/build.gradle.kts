plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val versionFile = rootProject.file("../VERSION")
val appVersionName = if (versionFile.exists()) versionFile.readText().trim() else "1.0.0"
val versionParts = appVersionName.split(".")
val appVersionCode = runCatching {
    val major = versionParts.getOrElse(0) { "1" }.toInt()
    val minor = versionParts.getOrElse(1) { "0" }.toInt()
    val patch = versionParts.getOrElse(2) { "0" }.toInt()
    major * 10000 + minor * 100 + patch
}.getOrDefault(1)

android {
    namespace = "dev.hyperscreen.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.hyperscreen.client"
        minSdk = 30 // Android 11+ for native MediaFormat.KEY_LOW_LATENCY & unbuffered stylus prediction
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

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
