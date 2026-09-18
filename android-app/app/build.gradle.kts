import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val generatedRuleAssets = layout.buildDirectory.dir("generated/bichen-rule-assets").get().asFile
val syncBichenRuleAssets = tasks.register<Sync>("syncBichenRuleAssets") {
    from(rootProject.file("../module/sources.tsv"))
    from(rootProject.file("../module/rules")) {
        into("rules")
        include("adaway.txt", "china.txt", "tracking.txt", "hagezi.txt")
    }
    into(generatedRuleAssets)
}

android {
    namespace = "io.github.xgl34222220.bichen"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.xgl34222220.bichen"
        minSdk = 26
        targetSdk = 35
        versionCode = 461
        versionName = "0.4.0-test.61"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main").assets.srcDir(generatedRuleAssets)
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
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
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(syncBichenRuleAssets)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.03.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("com.materialkolor:material-kolor:2.0.0")
    implementation("dev.chrisbanes.haze:haze:1.6.10")
    implementation("dev.chrisbanes.haze:haze-materials:1.6.10")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-squircle-android:0.9.3")
}
