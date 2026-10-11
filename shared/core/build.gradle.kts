import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    // Also keep kotlin-test/reflect on the metadata line understood by AGP lint.
    coreLibrariesVersion = "2.2.10"
    compilerOptions {
        // Match Android's existing metadata contract on every shared target.
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
    }
    androidTarget()

    if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
        iosX64()
        iosArm64()
        iosSimulatorArm64()
        targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
            binaries.framework {
                baseName = "HealthTimelineCore"
                isStatic = true
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.healthtimeline.shared"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
}
