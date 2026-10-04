plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "eu.nosini.unifiedshim"
    compileSdk = 35

    defaultConfig {
        applicationId = "eu.nosini.unifiedshim"
        // Private space is API 35; the owner side runs fine lower. 29 keeps the
        // owner side broadly compatible while abstract sockets + FGS are available.
        minSdk = 29
        targetSdk = 35
        versionCode = 6
        versionName = "0.5.0"
    }

    signingConfigs {
        create("release") {
            // Self-signed key for sideloading. Both profiles must run this same
            // release-signed APK — the cross-profile auth handshake is keyed by the
            // signing certificate, so debug- and release-signed copies won't pair.
            storeFile = rootProject.file("unified-shim-release.jks")
            storePassword = "unifiedshim"
            keyAlias = "unifiedshim"
            keyPassword = "unifiedshim"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    // Owner side is a UnifiedPush *connector* registered with the ntfy app —
    // we never open our own ntfy connection.
    implementation("org.unifiedpush.android:connector:2.5.0")
}
