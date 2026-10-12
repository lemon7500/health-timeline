plugins { id("com.android.application") }

// Disposable compatibility probe. Never uses the production package, key or signing files.
android {
    namespace = "com.healthtimeline.compatprobe"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.healthtimeline.compatprobe"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "synthetic-sqlcipher-compat"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += "cipher"
    productFlavors {
        create("baseline") { dimension = "cipher" }
        create("candidate") { dimension = "cipher" }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").assets.srcDir(rootProject.file("apps/android/app/schemas"))
}

dependencies {
    "baselineImplementation"("net.zetetic:sqlcipher-android:4.18.0")
    "candidateImplementation"("net.zetetic:sqlcipher-android:4.19.1")
    implementation("androidx.sqlite:sqlite:2.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
