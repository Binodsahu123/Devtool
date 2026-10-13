import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URL
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}


val generateV8BuildContract = tasks.register("generateV8BuildContract") {
    val contractFile = file("src/main/java/com/swift/browser/extensionv8/V8BuildContract.kt")
    val cppContractHeader = file("src/main/cpp/include/orion_v8_build_contract.h")
    outputs.files(contractFile, cppContractHeader)
    doLast {
        contractFile.parentFile.mkdirs()
        contractFile.writeText("""package com.swift.browser.extensionv8

/** V8 Build Configuration Contract. */
internal object V8BuildContract {
    const val RUNTIME_PROVIDER = "NativeScript Android Runtime"
    const val NATIVESCRIPT_PACKAGE = "@nativescript/android"
    const val NATIVESCRIPT_VERSION = "9.1.1"
    const val TARGET_ABI = "arm64-v8a"
    const val TARGET_OS = "android"
    const val TARGET_CPU = "arm64"
    const val ANDROID_NDK_VERSION = "28.2.13676358"
    const val V8_VERSION = "14.9.207.39"
}
""")
        cppContractHeader.parentFile.mkdirs()
        cppContractHeader.writeText("""#pragma once
#ifndef ORION_V8_BUILD_CONTRACT_H
#define ORION_V8_BUILD_CONTRACT_H

#define ORION_V8_PROVIDER "NativeScript Android Runtime"
#define ORION_V8_PACKAGE "@nativescript/android"
#define ORION_V8_VERSION "9.1.1"
#define ORION_V8_TARGET_ABI "arm64-v8a"
#define ORION_V8_EXPECTED_VERSION "14.9.207.39"
#define ORION_ANDROID_NDK_VERSION "28.2.13676358"

#endif // ORION_V8_BUILD_CONTRACT_H
""")
    }
}

val ensureV8NativeLibraries = tasks.register("ensureV8NativeLibraries") {
    val arm64Lib = file("src/main/jniLibs/arm64-v8a/libNativeScript.so")
    outputs.file(arm64Lib)

    doLast {
        if (arm64Lib.exists() && arm64Lib.length() > 0L) {
            logger.lifecycle("[V8] NativeScript arm64 runtime already present.")
            return@doLast
        }

        logger.lifecycle("[V8] NativeScript runtime missing; acquiring @nativescript/android@9.1.1...")
        val stagingDir = file("${layout.buildDirectory.get().asFile}/orion-v8-native-staging")
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        val pyScript = """
import io
import os
import tarfile
import urllib.request
import zipfile

stage_dir = '${stagingDir.absolutePath.replace("\\", "/")}'
url = 'https://registry.npmjs.org/@nativescript/android/-/android-9.1.1.tgz'

request = urllib.request.Request(
    url,
    headers={'User-Agent': 'OrionBrowser/NativeV8'}
)
with urllib.request.urlopen(request, timeout=120) as response:
    tar_bytes = response.read()

aar_bytes = None
with tarfile.open(fileobj=io.BytesIO(tar_bytes), mode='r:gz') as tar:
    for member in tar.getmembers():
        if member.name.endswith('nativescript-optimized.aar'):
            extracted = tar.extractfile(member)
            if extracted is None:
                raise RuntimeError('nativescript-optimized.aar has no payload')
            aar_bytes = extracted.read()
            break

if not aar_bytes:
    raise RuntimeError(
        'nativescript-optimized.aar was not found in @nativescript/android@9.1.1'
    )

with zipfile.ZipFile(io.BytesIO(aar_bytes)) as aar:
    name = 'jni/arm64-v8a/libNativeScript.so'
    if name not in aar.namelist():
        raise RuntimeError(
            'arm64-v8a/libNativeScript.so is missing from nativescript-optimized.aar'
        )

    destination = os.path.join(stage_dir, 'arm64-v8a', 'libNativeScript.so')
    os.makedirs(os.path.dirname(destination), exist_ok=True)
    with open(destination, 'wb') as output:
        output.write(aar.read(name))
""".trimIndent()
        val process = ProcessBuilder("python3", "-c", pyScript)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            stagingDir.deleteRecursively()
            throw GradleException("[V8] Failed to download NativeScript binaries: $output")
        }

        val stagedLib = file("$stagingDir/arm64-v8a/libNativeScript.so")
        if (!stagedLib.exists() || stagedLib.length() == 0L) {
            stagingDir.deleteRecursively()
            throw GradleException(
                "[V8] Download completed but arm64 NativeScript runtime is missing or empty"
            )
        }

        arm64Lib.parentFile.mkdirs()
        stagedLib.copyTo(arm64Lib, overwrite = true)
        stagingDir.deleteRecursively()

        if (!arm64Lib.exists() || arm64Lib.length() == 0L) {
            throw GradleException(
                "[V8] NativeScript runtime installation failed: ${arm64Lib.absolutePath}"
            )
        }

        logger.lifecycle("[V8] Successfully installed NativeScript prebuilt arm64 V8 runtime.")
    }
}

val isReleaseBuild = gradle.startParameter.taskNames.any {
    it.endsWith("Release", ignoreCase = true) ||
    it.contains(":assembleRelease", ignoreCase = true) ||
    it.contains(":bundleRelease", ignoreCase = true)
}
val requestedNativeV8 = providers.gradleProperty("orionRequireNativeV8").orNull?.toBoolean()
    ?: System.getenv("ORION_REQUIRE_NATIVE_V8").toBoolean()
val requireNativeV8 = isReleaseBuild || requestedNativeV8

android {
    namespace = "com.swift.browser.extensionv8"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }

        val sdkRoot = System.getenv("ANDROID_SDK_ROOT")
            ?: System.getenv("ANDROID_HOME")
            ?: System.getProperty("android.sdk.path")
            ?: System.getenv("HOME")?.let { "$it/Android/Sdk" }
        val ndkMarker = sdkRoot?.let { file("$it/ndk/28.2.13676358/source.properties") }
        val hasNdk = file("src/main/cpp/CMakeLists.txt").exists() &&
            !System.getenv("NO_NDK").toBoolean() && ndkMarker?.exists() == true
        if (isReleaseBuild && !hasNdk) {
            throw GradleException("Release V8 runtime requires Android NDK 28.2.13676358 and CMake 3.22.1")
        }
        if (hasNdk) {
            externalNativeBuild {
                cmake {
                    cppFlags("-std=c++20 -frtti -fexceptions")
                    arguments(
                        "-DANDROID_STL=c++_shared",
                        "-DORION_REQUIRE_NATIVE_V8=$requireNativeV8",
                        "-DORION_ALLOW_V8_STUB=${!requireNativeV8}"
                    )
                }
            }
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add("src/main/jniLibs")
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

val verifyV8RuntimeContract = tasks.register("verifyV8RuntimeContract") {
    dependsOn(ensureV8NativeLibraries)
    val arm64Lib = file("src/main/jniLibs/arm64-v8a/libNativeScript.so")
    val v8VersionHeader = file("src/main/cpp/include/v8-version.h")
    inputs.files(arm64Lib, v8VersionHeader)
    doLast {
        if (!arm64Lib.exists() || arm64Lib.length() == 0L) {
            throw GradleException("[verifyV8RuntimeContract] Required NativeScript runtime library is missing: ${arm64Lib.absolutePath}")
        }
        val headerText = v8VersionHeader.readText()
        val major = Regex("""#define\s+V8_MAJOR_VERSION\s+(\d+)""").find(headerText)?.groupValues?.get(1) ?: "0"
        val minor = Regex("""#define\s+V8_MINOR_VERSION\s+(\d+)""").find(headerText)?.groupValues?.get(1) ?: "0"
        val build = Regex("""#define\s+V8_BUILD_NUMBER\s+(\d+)""").find(headerText)?.groupValues?.get(1) ?: "0"
        val patch = Regex("""#define\s+V8_PATCH_LEVEL\s+(\d+)""").find(headerText)?.groupValues?.get(1) ?: "0"
        val headerVersion = "$major.$minor.$build.$patch"

        val pyScript = """
with open('${arm64Lib.absolutePath}', 'rb') as f:
    data = f.read()
import re
matches = set(re.findall(rb"14\.[0-9]+\.[0-9]+(?:\.[0-9]+)?", data))
for m in matches:
    print(m.decode('ascii'))
""".trimIndent()
        val process = ProcessBuilder("python3", "-c", pyScript).redirectErrorStream(true).start()
        val runtimeVersion = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()

        if (runtimeVersion.isBlank()) {
            throw GradleException("[verifyV8RuntimeContract] Unable to prove V8 version from ${arm64Lib.name}; refusing an unverifiable production runtime")
        }
        logger.lifecycle("[verifyV8RuntimeContract] Header V8 version: $headerVersion | Runtime binary V8 version candidates: $runtimeVersion")
        val runtimeVersions = runtimeVersion.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (headerVersion !in runtimeVersions) {
            throw GradleException("[verifyV8RuntimeContract] MISMATCH DETECTED: expected V8 version $headerVersion was not found in runtime binary candidates $runtimeVersions from ${arm64Lib.name}")
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(generateV8BuildContract, ensureV8NativeLibraries, verifyV8RuntimeContract)
}
tasks.matching { it.name.contains("CMake", ignoreCase = true) }.configureEach {
    dependsOn(ensureV8NativeLibraries)
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.moshi.kotlin)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}


