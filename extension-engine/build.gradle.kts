plugins {
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.android.library)
    alias(libs.plugins.google.devtools.ksp)
}

android {
    namespace = "com.swift.browser.extensionengine"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        val sdkRoot = System.getenv("ANDROID_SDK_ROOT")
            ?: System.getenv("ANDROID_HOME")
            ?: System.getProperty("android.sdk.path")
            ?: System.getenv("HOME")?.let { "$it/Android/Sdk" }
        val ndkMarker = sdkRoot?.let { file("$it/ndk/28.2.13676358/source.properties") }
        val hasNdk = file("src/main/cpp/CMakeLists.txt").exists() &&
            !System.getenv("NO_NDK").toBoolean() && ndkMarker?.exists() == true
        if (hasNdk) {
            externalNativeBuild {
                cmake {
                    cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                }
            }
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        }
    }

    val sdkRoot = System.getenv("ANDROID_SDK_ROOT")
        ?: System.getenv("ANDROID_HOME")
        ?: System.getProperty("android.sdk.path")
        ?: System.getenv("HOME")?.let { "$it/Android/Sdk" }
    val ndkMarker = sdkRoot?.let { file("$it/ndk/28.2.13676358/source.properties") }
    val hasNdk = file("src/main/cpp/CMakeLists.txt").exists() &&
        !System.getenv("NO_NDK").toBoolean() && ndkMarker?.exists() == true
    if (hasNdk) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":developer-tools-engine"))
    implementation(project(":extension-v8-runtime"))
    implementation(project(":permission-engine"))
    implementation(project(":cookie-engine"))
    implementation(project(":desktop-engine"))
    implementation(project(":tab-engine"))
    implementation(project(":bookmark-engine"))
    implementation(project(":history-engine"))
    implementation(project(":download-engine"))
    implementation(project(":notification-engine"))
    implementation(project(":security-engine"))
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(project(":database-core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    
    // Room database dependencies
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Moshi for stable JSON parsing/serialization
    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.kotlin.codegen)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.core)
}
