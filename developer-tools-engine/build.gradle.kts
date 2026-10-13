plugins {
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.swift.browser.developertoolsengine"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    // Upstream Unix Shells implementation uses NanoHTTPD to serve the bundled Chromium DevTools frontend.
    implementation("org.nanohttpd:nanohttpd:2.3.1")

}
