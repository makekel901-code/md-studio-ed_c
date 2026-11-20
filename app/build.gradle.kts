plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStorePath = System.getenv("MDSTUDIO_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("MDSTUDIO_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("MDSTUDIO_KEY_ALIAS")
val releaseKeyPassword = System.getenv("MDSTUDIO_KEY_PASSWORD")

android {
    kotlinOptions {
        jvmTarget = "17"
    }
    namespace = "com.amd.mdstudio"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.amd.mdstudio"
        minSdk = 28
        targetSdk = 35
        versionCode = 10000
        versionName = "1.0.0"
    }
    signingConfigs {
        create("release") {
            if (!releaseStorePath.isNullOrBlank()) {
                storeFile = file(releaseStorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (!releaseStorePath.isNullOrBlank()) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation "org.jetbrains.kotlin:kotlin-stdlib:2.2.20"
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
