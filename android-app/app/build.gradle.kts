plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.mikepenz.aboutlibraries.plugin.android")
}

android {
    buildToolsVersion = "37.0.0"
    namespace = "io.github.xgl34222220.hetu"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "io.github.xgl34222220.hetu"
        minSdk = 26
        targetSdk = 35
        versionCode = 2085
        versionName = "0.12.15-v20-pdf"
    }

    // Explicit CI debug identity. No private key is committed or exported.
    signingConfigs {
        getByName("debug") {
            System.getenv("HETU_TEST_KEYSTORE")?.takeIf { it.isNotBlank() }?.let {
                storeFile = file(it)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
            isV1SigningEnabled = true
            isV2SigningEnabled = true
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "2g"
            it.systemProperty("robolectric.graphicsMode", "NATIVE")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }


    buildTypes {
        getByName("debug") {
            isDebuggable = true
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libhetu_core.so"
        }
    }
}


dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.03.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.graphics:graphics-shapes:1.0.1")
    implementation("androidx.compose.material3:material3") { version { strictly("1.5.0-alpha22") } }
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // Miuix 0.9.4 brings MCU 5.0.1. The old 2.0.0 theme calls constructors
    // removed from that runtime; keep the theme and utilities on one ABI.
    val materialKolorVersion = "5.0.1"
    implementation("com.materialkolor:material-kolor:$materialKolorVersion") {
        version { strictly(materialKolorVersion) }
    }
    constraints {
        implementation("com.materialkolor:material-color-utilities:$materialKolorVersion") {
            version { strictly(materialKolorVersion) }
        }
        implementation("com.materialkolor:material-color-utilities-android:$materialKolorVersion") {
            version { strictly(materialKolorVersion) }
        }
    }
    implementation("dev.chrisbanes.haze:haze:1.6.10")
    implementation("dev.chrisbanes.haze:haze-materials:1.6.10")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-squircle-android:0.9.4")
    implementation("com.patrykandpatrick.vico:compose:3.3.1")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("io.coil-kt.coil3:coil-svg:3.6.3")
    implementation("com.mikepenz:aboutlibraries-compose-m3:15.2.0")
    implementation(platform("io.github.rosemoe:editor-bom:0.24.6"))
    implementation("io.github.rosemoe:editor")
}

// Presentation-only: safe YAML icon projection, SVG decoding, rendered regression tests.
dependencies {
    implementation("org.yaml:snakeyaml:2.3")
    implementation("com.networknt:json-schema-validator:1.5.9")
    implementation("com.caverock:androidsvg-aar:1.4")
    testImplementation(platform("androidx.compose:compose-bom:2026.03.00"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
