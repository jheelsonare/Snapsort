plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signing values come from a local shell or GitHub environment secrets, never source control.
val releaseSigningValues = listOf(
    "SNAPSORT_KEYSTORE_FILE", "SNAPSORT_KEYSTORE_PASSWORD", "SNAPSORT_KEY_ALIAS", "SNAPSORT_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull }
val hasReleaseSigning = releaseSigningValues.values.all { !it.isNullOrBlank() }
require(hasReleaseSigning || releaseSigningValues.values.all { it.isNullOrBlank() }) {
    "Provide all four SNAPSORT release signing variables, or none for an unsigned local release."
}

android {
    namespace = "com.snapsort.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.snapsort.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }
    if (hasReleaseSigning) {
        signingConfigs.create("release") {
            storeFile = file(releaseSigningValues.getValue("SNAPSORT_KEYSTORE_FILE")!!)
            require(storeFile!!.isFile) { "The configured release keystore file does not exist." }
            storePassword = releaseSigningValues.getValue("SNAPSORT_KEYSTORE_PASSWORD")
            keyAlias = releaseSigningValues.getValue("SNAPSORT_KEY_ALIAS")
            keyPassword = releaseSigningValues.getValue("SNAPSORT_KEY_PASSWORD")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
