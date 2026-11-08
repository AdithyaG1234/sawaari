plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firebase (group rides) is switched on once app/google-services.json is added.
// Without it the app still builds; the Group tab explains the one-time setup.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "app.sawaari"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.sawaari"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.0.0"
        // Google Places key for ratings: from the PLACES_API_KEY environment variable (a GitHub secret in CI)
        // or local.properties. Without it the app just shows rider ratings.
        val placesKey = System.getenv("PLACES_API_KEY")
            ?: rootProject.file("local.properties").takeIf { it.exists() }?.readLines()
                ?.firstOrNull { it.startsWith("PLACES_API_KEY=") }?.substringAfter("=")
            ?: ""
        buildConfigField("String", "PLACES_API_KEY", "\"${placesKey.trim()}\"")
    }

    // A fixed debug key checked into the repo, so every CI build is signed the same way and
    // installs over the previous one. (Only for test builds; a Play Store release needs its own secret key.)
    signingConfigs {
        getByName("debug") {
            storeFile = file("sawaari-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        // Shrink the debug build too (it's the one we hand out), so it downloads reliably on mobile data.
        debug {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Release APKs (published on GitHub for v1.0 tags) use the same key as earlier builds, so they
        // install over them. Before going on the Play Store, switch to a private upload key kept in CI secrets.
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    // One APK per phone chip type, so riders download ~half the size.
    // arm64-v8a covers almost every phone from the last several years.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST",
                "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/9/module-info.class",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Free, open-source maps with offline region support (no API key, no per-user fees)
    implementation("org.maplibre.gl:android-sdk:13.7.1")

    // Free, open-source offline routing. 4.0 is the newest line that still runs on Android
    // with the plain "fastest" weighting; the routing packs are built with the same version.
    implementation("com.graphhopper:graphhopper-core:4.0") {
        exclude(group = "org.codehaus.janino") // only for server-side custom models, can't run on Android
        exclude(group = "com.fasterxml.jackson.dataformat", module = "jackson-dataformat-xml") // GPX export, unused
    }

    // Group rides: live locations over Firebase Realtime Database (free Spark plan)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
