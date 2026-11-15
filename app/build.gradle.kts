plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ciBuildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.niftysignal.ai"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        applicationId = "com.niftysignal.ai"
        minSdk = 23
        targetSdk = 35
        versionCode = ciBuildNumber
        versionName = "1.0.$ciBuildNumber"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("MGROW_KEYSTORE_PATH") ?: "mgrow-release.jks")
            storePassword = System.getenv("MGROW_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("MGROW_KEY_ALIAS")
            keyPassword = System.getenv("MGROW_KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.biometric:biometric:1.1.0")
    testImplementation("junit:junit:4.13.2")
}
