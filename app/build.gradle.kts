import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun signingValue(property: String, env: String): String? =
    System.getenv(env) ?: keystoreProperties.getProperty(property)

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
        // Self-signed key for sideloading. Both profiles must run the same
        // release-signed APK — the cross-profile auth handshake is keyed by the
        // signing certificate, so debug- and release-signed copies won't pair.
        // CI passes the key through SIGNING_* environment variables; local builds
        // read the gitignored keystore.properties. With neither, the release APK
        // is left unsigned.
        val storeFile = signingValue("storeFile", "SIGNING_STORE_FILE")
        if (storeFile != null) {
            create("release") {
                this.storeFile = rootProject.file(storeFile)
                storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD") ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
