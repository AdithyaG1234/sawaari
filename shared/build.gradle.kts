// Code shared by the Android and iPhone apps: ride logic now, more screens step by step.
// Targets: "jvm" (used by the Android app and for fast tests) and the iPhone + iPhone simulator.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        // The iPhone screens use Compose Multiplatform. Only the iPhone side pulls it in for now,
        // so the Android app keeps using its own Jetpack Compose unchanged.
        iosMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation("junit:junit:4.13.2")
        }
    }
}

// Compose is only used on the iPhone side of this module for now (see iosMain).
composeCompiler {
    targetKotlinPlatforms.set(setOf(org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.native))
}

// The shared module has no Compose resource files yet (images, strings), and the resource sync task
// fails when Xcode runs it without any. Turn it back on when the first resource is added.
tasks.matching { it.name == "syncComposeResourcesForIos" }.configureEach { enabled = false }
