import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9.0+ registers Kotlin natively; no separate kotlin.android plugin.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}
val mapboxAccessToken: String = localProperties.getProperty("MAPBOX_ACCESS_TOKEN")
    ?: System.getenv("MAPBOX_ACCESS_TOKEN")
    ?: ""

fun String.toJavaStringLiteral(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "org.walktalkmeditate.pilgrim"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "org.walktalkmeditate.pilgrim"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "MAPBOX_ACCESS_TOKEN", mapboxAccessToken.toJavaStringLiteral())

        // NB: ABI filters are set per build-type (debug/release) rather
        // than here. AGP merges defaultConfig + buildType filters, so a
        // defaultConfig entry can't be cleared from a buildType block.
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val hasReleaseKeystore = keystorePropertiesFile.exists()
    if (hasReleaseKeystore) {
        signingConfigs {
            create("release") {
                val props = Properties().apply { load(keystorePropertiesFile.inputStream()) }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            // Debug builds target the dev devices only (OnePlus 13 + any
            // arm64 emulator). Skipping armeabi-v7a + x86_64 cuts the
            // debug APK's native-lib footprint and halves device-install
            // time on device-test loops.
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Release builds include all supported ABIs — Play Store's
            // bundle + per-device-ABI split will deliver only the one
            // each installing device needs.
            ndk {
                abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Page-aligned (16 KB) JNI .so packaging for Android 15+.
            useLegacyPackaging = false
        }
    }

    androidResources {
        // The whisper.cpp ggml model is already incompressible binary;
        // packaging it as STORE rather than DEFLATE skips runtime
        // decompression and lets openFd() expose a real length so the
        // installer can verify on-disk size against bundled size.
        noCompress += "bin"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Bump the unit-test JVM heap. Robolectric loads ~50–100 MB
            // of Android-runtime stub state per test class, plus Compose
            // + Hilt + Room runtimes — the Gradle default `-Xmx512m`
            // GC-thrashes after ~10 classes and looks like a hang on CI
            // (manifested as a 22-minute timeout on PR #65 with no test
            // reports produced). 2 GB gives headroom for the full
            // ~1080-test suite without GC pauses dominating wall time.
            it.maxHeapSize = "2g"

            // Fork a fresh JVM every 50 test classes. Robolectric's
            // SandboxClassLoader retains every class it ever loaded for
            // every API level it ever encountered — across hundreds of
            // test classes this hits PermGen-style memory pressure even
            // with 2 GB heap. Recycling the worker every 50 classes
            // bounds the lifetime memory footprint without paying JVM
            // startup cost for every class. Default `0` = no recycling.
            it.setForkEvery(50L)

            // Run two test workers in parallel inside the unit-tests
            // job (different from `--max-workers` which controls Gradle
            // task parallelism). 4-core CI runner can comfortably host
            // two Robolectric JVMs at 2 GB each. Cuts wall time roughly
            // in half on CI while leaving 2 cores for Gradle daemon +
            // build cache I/O.
            it.maxParallelForks = 2
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
        // Stage 12-D: values-fr/ is a STUB locale that intentionally
        // contains only `locale_resolution_marker` to verify the
        // values-XX/ resource resolution path. iOS is English-only too
        // (Base.lproj + en.lproj only). Lint's MissingTranslation
        // would error on all 391 keys missing in fr — until real
        // translations land, suppress globally so the stub doesn't
        // block CI.
        disable += "MissingTranslation"
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.browser)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.navigation.compose)

    // Stage 9-A: Jetpack Glance for the home-screen widget.
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.play.services.location)

    implementation(libs.mapbox.maps.android)

    implementation(libs.androidx.media3.exoplayer)

    // Stage 5-C: HTTP + JSON for the voice-guide manifest fetch. First
    // introduction of OkHttp + kotlinx.serialization; Phase 8
    // (Collective Counter, Share Worker) will reuse the same stack.
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // Stage 7-A: Coil for AsyncImage on the photo reliquary grid. Built-in
    // content:// URI support; no network module needed (we only load
    // local photo-picker URIs).
    implementation(libs.coil.compose)

    // Stage 7-B: bundled ML Kit Image Labeling for per-photo analysis.
    // Bundled (not Play Services variant) so analysis works offline —
    // matches Pilgrim's local-first ethos. ~5.7 MB APK bump.
    // kotlinx-coroutines-play-services bridges ML Kit's Task<T> into
    // `suspend` via `.await()`.
    implementation(libs.mlkit.image.labeling)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    // Stage 3-C: Compose UI test on the JVM via Robolectric. Used by
    // CalligraphyPathComposableTest to exercise the Path + DrawScope
    // pipeline without a device.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
