pluginManagement {
    // Kotlin 2.4 requires R8 >= 9.1.29; use a published stable 9.1 patch.
    // Official override: https://r8.googlesource.com/r8/+/refs/heads/main/README.md
    buildscript {
        repositories { google(); mavenCentral() }
        dependencies { classpath("com.android.tools:r8:9.1.56") }
    }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HealthTimeline"
include(":androidApp")
project(":androidApp").projectDir = file("apps/android/app")
include(":shared:core")
// Disposable synthetic-data compatibility probe; never shipped as the medical app.
include(":sqlcipherCompat")
project(":sqlcipherCompat").projectDir = file("tools/sqlcipher-compat")
