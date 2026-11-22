plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.standby.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.standby.app"
        minSdk = 28
        targetSdk = 35
        // GitHub's build number: every build is a higher version, so the in-app updater can tell new from old
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toInt() ?: 1
        versionName = "1.$versionCode"
    }
    // One fixed key for every build, so a new APK installs over the old one and keeps settings and permissions.
    // Key and password come from GitHub Secrets (see the workflow); without them the build falls back to the default debug key.
    val keyPassword = System.getenv("KEYSTORE_PASSWORD")
    val fixed = if (keyPassword != null && file("standby.keystore").exists()) signingConfigs.create("fixed") {
        storeFile = file("standby.keystore")
        storePassword = keyPassword
        keyAlias = "standby"
        this.keyPassword = keyPassword
        storeType = "pkcs12"
    } else null
    buildTypes {
        debug { if (fixed != null) signingConfig = fixed }
        release {
            isMinifyEnabled = false
            if (fixed != null) signingConfig = fixed
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    testImplementation("junit:junit:4.13.2")
}
