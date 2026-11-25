plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.simbridge.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.simbridge.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 7
        versionName = "1.4.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // A fixed signing key keeps every build installable over the previous one.
    // CI decodes it from repository secrets; locally it falls back to ~/.simbridge-signing.
    val signingDir = File(System.getProperty("user.home"), ".simbridge-signing")
    val keystorePath = System.getenv("SIMBRIDGE_KEYSTORE_FILE")
        ?: File(signingDir, "simbridge.jks").takeIf { it.exists() }?.path
    val keystorePassword = System.getenv("SIMBRIDGE_KEYSTORE_PASSWORD")
        ?: File(signingDir, "ANDROID_KEYSTORE_PASSWORD.txt").takeIf { it.exists() }?.readText()?.trim()

    signingConfigs {
        if (keystorePath != null && keystorePassword != null) {
            create("simbridge") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = "simbridge"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        signingConfigs.findByName("simbridge")?.let { fixedKey ->
            getByName("debug") { signingConfig = fixedKey }
            getByName("release") { signingConfig = fixedKey }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.zxing:core:3.5.3")
    // Official Conscrypt provider for reliable TLS 1.3 exportKeyingMaterial on Android 10-12 (zero reflection)
    implementation("org.conscrypt:conscrypt-android:2.5.2")
}
