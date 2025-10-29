@file:Suppress("UnstableApiUsage")

import com.android.build.gradle.internal.api.ApkVariantOutputImpl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget


plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.serialization)


}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "app.fluffy"
        minSdk = 24
        targetSdk = 36
        versionCode = 520
        versionName = "3.2.10"

        androidResources {
            localeFilters += setOf("en", "ar", "de", "es-rES", "es-rUS", "fr", "hr", "hu", "in", "it", "ja", "pl", "pt-rBR", "ru-rRU", "sv", "tr", "uk", "zh")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

   
    val enableApkSplits = (providers.gradleProperty("enableApkSplits").orNull ?: "true").toBoolean()
    val includeUniversalApk = (providers.gradleProperty("includeUniversalApk").orNull ?: "true").toBoolean()
    val targetAbi = providers.gradleProperty("targetAbi").orNull

    splits {
        abi {
            isEnable = enableApkSplits
            reset()
            if (enableApkSplits) {
                if (targetAbi != null) {
                    include(targetAbi)
                } else {
                    include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                }
            }
            isUniversalApk = includeUniversalApk && enableApkSplits
        }
    }

    applicationVariants.all {
        val buildingApk = gradle.startParameter.taskNames.any { it.contains("assemble", ignoreCase = true) }
        if (!buildingApk) return@all

        val variant = this
        outputs.all {
            if (this is ApkVariantOutputImpl) {
                val abiName = filters.find { it.filterType == "ABI" }?.identifier
                val base = variant.versionCode

                if (abiName != null) {
                    // Split APKs get stable per-ABI version codes and names
                    val abiVersionCode = when (abiName) {
                        "x86" -> base - 3
                        "x86_64" -> base - 2
                        "armeabi-v7a" -> base - 1
                        "arm64-v8a" -> base
                        else -> base
                    }
                    versionCodeOverride = abiVersionCode
                    outputFileName = "fluffy-${variant.versionName}-${abiName}.apk"
                } else {
                    versionCodeOverride = base + 1
                    outputFileName = "fluffy-${variant.versionName}-universal.apk"
                }
            }
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "${rootProject.projectDir}/release.keystore")
            storePassword = System.getenv("STORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = false
            enableV4Signing = false
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("Long", "BUILD_TIME", "0L")
            isShrinkResources = true
        }
        getByName("debug") {
            isShrinkResources = false
            isDebuggable = true
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "2.0.0"
    }

    namespace = "app.fluffy"


    dependenciesInfo {
        includeInApk = false
    }
}

// Configure all tasks that are instances of AbstractArchiveTask
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

dependencies {

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.animation)
    ksp(libs.androidx.room.compiler)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(libs.kotlin.stdlib)
    implementation(libs.core.ktx)

    // Android lifecycle
    implementation(libs.lifecycle.viewmodel.ktx)

    // Work Manager
    implementation(libs.work.runtime.ktx)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.gson)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.runtime)


    //Material dependencies
    implementation(libs.material)
    implementation(libs.material3.android)

    // Compose dependencies
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.constraintlayout.compose.android)
    implementation(libs.kotlin.reflect)
    implementation(libs.androidbrowserhelper)
    implementation(libs.androidx.datastore.preferences.core)

    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.tv.foundation)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)

    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.media3.exoplayer.dash)



    implementation(libs.zip4j)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.zstd.jni)

    // Shizuku
    implementation(libs.api)
    implementation(libs.provider)


    // Testing
//    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
}
