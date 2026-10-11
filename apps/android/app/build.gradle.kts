import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.internal.KaptTask
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.tasks.BaseKapt
import org.jetbrains.kotlin.gradle.tasks.Kapt
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.healthtimeline.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.healthtimeline.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 14
        versionName = "1.3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    val keystorePropertiesFile = rootProject.file("release-private/keystore.properties")
    val keystoreProperties = Properties().apply {
        if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (keystorePropertiesFile.exists()) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
    packaging.resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    // Java 9 OSGi bundle metadata is unused on Android and collides across JVM libraries.
    // Exclude only that manifest, retaining dependency licenses and notices.
    packaging.resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
}

kotlin {
    coreLibrariesVersion = "2.2.10"
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Keep the existing language/metadata contract supported by Room 2.7.2.
        // This does not downgrade the patched Kotlin/KAPT compiler binaries.
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")

    implementation(project(":shared:core"))
    // Room's processor also reads stdlib metadata (e.g. Continuation).
    // Retain the original runtime; security fixes are in the 2.4.20 build plugins.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")

    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    kapt("androidx.room:room-compiler:2.7.2")
    implementation("net.zetetic:sqlcipher-android:4.18.0")
    implementation("androidx.sqlite:sqlite-ktx:2.5.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.room:room-testing:2.7.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kapt {
    correctErrorTypes = true
    arguments { arg("room.schemaLocation", "$projectDir/schemas") }
}

// Check the task's actual worker input, not only gradle.properties. This also
// protects direct KAPT invocations that do not run verifyBuildToolchain first.
tasks.withType<BaseKapt>().configureEach {
    doFirst {
        check(!incAptCache.isPresent) {
            "$path must not pass an incremental APT cache directory to its worker"
        }
    }
}

tasks.register("verifyBuildToolchain") {
    group = "verification"
    description = "Verify the loaded Kotlin/KAPT/R8 toolchain and effective metadata settings."
    doLast {
        val expectedKotlin = "2.4.20"
        val expectedR8 = "9.1.56"
        val expectedLanguage = KotlinVersion.KOTLIN_2_2
        val checkedProjects = rootProject.allprojects.sortedBy { it.path }
        var pluginCount = 0
        var compilationCount = 0
        var kaptCount = 0
        for (checkedProject in checkedProjects) {
            checkedProject.plugins.withType(KotlinBasePlugin::class.java).forEach { plugin ->
                check(plugin.pluginVersion == expectedKotlin) {
                    "${checkedProject.path} loaded ${plugin.javaClass.name} ${plugin.pluginVersion}; expected $expectedKotlin"
                }
                logger.lifecycle("Kotlin plugin: ${checkedProject.path} ${plugin.javaClass.name} ${plugin.pluginVersion}")
                pluginCount++
            }
            checkedProject.tasks.withType(KotlinCompilationTask::class.java).forEach { compilation ->
                val options = compilation.compilerOptions
                check(options.languageVersion.orNull == expectedLanguage && options.apiVersion.orNull == expectedLanguage) {
                    "${compilation.path} must use language/API 2.2; actual ${options.languageVersion.orNull}/${options.apiVersion.orNull}"
                }
                val overridingArguments = options.freeCompilerArgs.get().filter { argument ->
                    listOf("-language-version", "-api-version", "-Xmetadata-version").any { option ->
                        argument == option || argument.startsWith("$option=")
                    }
                }
                check(overridingArguments.isEmpty()) {
                    "${compilation.path} must not override language/API/metadata through freeCompilerArgs: $overridingArguments"
                }
                logger.lifecycle("Kotlin compilation: ${compilation.path} language/API 2.2")
                compilationCount++
            }
            checkedProject.tasks.withType(BaseKapt::class.java).forEach { kaptTask ->
                val kaptPath = kaptTask.path
                check(!kaptTask.incAptCache.isPresent) {
                    "${kaptTask.path} has an incremental APT worker cache directory"
                }
                check(kaptTask is Kapt) { "${kaptTask.path} cannot expose its worker KAPT jars" }
                val processorJars = kaptTask.kaptJars.files.filter {
                    it.name.startsWith("kotlin-annotation-processing-gradle-") && it.extension == "jar"
                }
                check(processorJars.singleOrNull()?.name == "kotlin-annotation-processing-gradle-$expectedKotlin.jar") {
                    "${kaptTask.path} selected unexpected KAPT jars: ${processorJars.map { it.name }}"
                }
                logger.lifecycle("KAPT worker: ${kaptTask.path} cacheDir absent; ${processorJars.single().absolutePath}")
                // No-kotlinc KAPT uses a separate worker input. Do not infer a
                // compiler version from an absent or unrelated compilerClasspath.
                if (kaptTask is KaptTask) {
                    val compilerJars = kaptTask.compilerClasspath.files.filter {
                        it.name.startsWith("kotlin-compiler-embeddable-") && it.extension == "jar"
                    }
                    if (compilerJars.isEmpty()) {
                        logger.lifecycle("KAPT compiler: $kaptPath no compiler-embeddable jar exposed; worker KAPT jar checked above")
                    } else {
                        check(compilerJars.singleOrNull()?.name == "kotlin-compiler-embeddable-$expectedKotlin.jar") {
                            "$kaptPath selected unexpected compiler jars: ${compilerJars.map { it.name }}"
                        }
                        logger.lifecycle("KAPT compiler: $kaptPath ${compilerJars.single().absolutePath}")
                    }
                }
                kaptCount++
            }
        }
        check(pluginCount > 0 && compilationCount > 0 && kaptCount > 0) {
            "Toolchain verification found no Kotlin plugins, compilation tasks, or KAPT workers"
        }
        val androidPluginLoader = project.plugins.getPlugin("com.android.application").javaClass.classLoader
        val r8Class = Class.forName("com.android.tools.r8.Version", true, androidPluginLoader)
        // getVersionString includes build details; LABEL is the actual version.
        val actualR8 = r8Class.getField("LABEL").get(null).toString()
        check(actualR8 == expectedR8) { "Loaded R8 $actualR8; expected $expectedR8" }
        val r8BuildDetails = r8Class.getMethod("getVersionString").invoke(null)
        logger.lifecycle("Loaded R8: $r8BuildDetails from ${r8Class.protectionDomain.codeSource?.location}")
        logger.lifecycle("Toolchain verified: $pluginCount Kotlin plugins, $compilationCount compilation tasks, $kaptCount KAPT workers")
        logger.lifecycle("Scope: verifies selected/loaded build inputs, not artifact signatures or completed Android/iOS compilation; only tasks registered on this host are checked.")
    }
}

val localPipeAgent = rootProject.file(".tooling/pipe-agent/pipe-fallback-agent.jar")
if (localPipeAgent.exists()) {
    tasks.withType<Test>().configureEach {
        jvmArgs(
            "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
            "-javaagent:${localPipeAgent.absolutePath}"
        )
    }
}
