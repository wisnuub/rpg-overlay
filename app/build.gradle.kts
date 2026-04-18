plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.orna.autobattle"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.orna.autobattle"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "2.0"
    }

    signingConfigs {
        create("release") {
            val keyPath = System.getenv("SIGNING_STORE_FILE")
            if (keyPath != null) {
                storeFile = file(keyPath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("SIGNING_STORE_FILE") != null)
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
