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
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(property)?.takeIf { it.isNotBlank() }

android {
    namespace = "eu.nosini.unifiedshim"
    compileSdk = 35

    defaultConfig {
        applicationId = "eu.nosini.unifiedshim"
        // Private space is API 35; the owner side runs fine lower. 29 keeps the
        // owner side broadly compatible while loopback TCP + FGS are available.
        minSdk = 29
        targetSdk = 35
        versionCode = 7
        versionName = "0.6.0"
    }

    signingConfigs {
        // Self-signed key for sideloading. Both profiles must run the same
        // release-signed APK — the cross-profile auth handshake is keyed by the
        // signing certificate, so debug- and release-signed copies won't pair.
        // CI passes the key through SIGNING_* environment variables; local builds
        // read the gitignored keystore.properties, with environment variables
        // taking precedence. With no storeFile at all, the release APK is left
        // unsigned. Once a storeFile is given, the remaining values and the file
        // itself are required, and a missing one fails the build.
        val storeFile = signingValue("storeFile", "SIGNING_STORE_FILE")
        if (storeFile != null) {
            val file = rootProject.file(storeFile)
            if (!file.exists()) {
                throw GradleException(
                    "keystore.properties / SIGNING_* incomplete: storeFile not found: ${file.path}",
                )
            }
            val storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
                ?: throw GradleException(
                    "keystore.properties / SIGNING_* incomplete: storePassword not set",
                )
            val keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
                ?: throw GradleException(
                    "keystore.properties / SIGNING_* incomplete: keyAlias not set",
                )
            create("release") {
                this.storeFile = file
                this.storePassword = storePassword
                this.keyAlias = keyAlias
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
