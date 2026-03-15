plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ai.androidclaw"
    compileSdk = 36

    lint {
        // AGP 8.13 / Kotlin FIR is crashing on debugUnitTest and debugAndroidTest
        // analysis in this environment. Keep production-source lint enabled.
        ignoreTestSources = true
        // Keep the fast loop deterministic and local; these checks perform
        // remote version lookups and can stall on this workstation.
        disable += setOf("NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    sourceSets {
        getByName("androidTest") {
            assets.srcDir("$projectDir/schemas")
        }
    }

    defaultConfig {
        applicationId = "ai.androidclaw.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("qa") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release", "debug")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        animationsDisabled = true
        unitTests.isIncludeAndroidResources = true
        managedDevices {
            localDevices {
                create("pixel8Api36") {
                    device = "Pixel 8"
                    apiLevel = 36
                    systemImageSource = "aosp"
                    testedAbi = "x86_64"
                }
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.snakeyaml)

    kapt(libs.androidx.room.compiler)

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.junit4)
    testImplementation(libs.junit.ext)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
}

// Preferred managed-device command on hosts with emulator acceleration:
// ./gradlew :app:pixel8Api36DebugAndroidTest -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
// Canonical Windows-emulator fallback from WSL:
// ./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.app.MainActivitySmokeTest
// Release-like install/launch smoke without androidTest:
// ./scripts/run_windows_android_test.sh --variant qa --launch-smoke --avd AndroidClawApi34 --launch-component ai.androidclaw.app/.MainActivity
// Installable release-like QA lane:
// ./gradlew :app:assembleQa :app:assembleAndroidTest
// Exact-alarm regression sweep:
// ./scripts/run_exact_alarm_regression.sh --api34-avd AndroidClawApi34 --api31-avd AndroidClawApi31
