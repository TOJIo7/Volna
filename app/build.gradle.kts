plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tj.volna.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "tj.volna.music"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"
    }

    // One fixed key, so new builds install over the old app and keep your music and playlists.
    signingConfigs {
        create("volna") {
            storeFile = file("volna.keystore")
            storePassword = "volna123"
            keyAlias = "volna"
            keyPassword = "volna123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("volna")
        }
        debug {
            signingConfig = signingConfigs.getByName("volna")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.media:media:1.7.0")
}
