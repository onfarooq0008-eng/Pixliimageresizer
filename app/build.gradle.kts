import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release ad unit IDs. Env vars YANDEX_NATIVE_ID / YANDEX_INTERSTITIAL_ID can override them; debug builds always use demo IDs.
fun adId(name: String, demo: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: demo

android {
    namespace = "com.pixli.imageresizer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pixli.imageresizer"
        minSdk = 29
        targetSdk = 36
        // CI passes -PversionCode=<run number>
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            // Filled from environment variables (set by the GitHub workflow)
            val keystore = System.getenv("KEYSTORE_FILE")
            if (!keystore.isNullOrBlank() && file(keystore).exists()) {
                storeFile = file(keystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "NATIVE_ID", "\"demo-native-content-yandex\"")
            buildConfigField("String", "INTERSTITIAL_ID", "\"demo-interstitial-yandex\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "NATIVE_ID", "\"${adId("YANDEX_NATIVE_ID", "R-M-20295158-1")}\"")
            buildConfigField("String", "INTERSTITIAL_ID", "\"${adId("YANDEX_INTERSTITIAL_ID", "R-M-20295158-2")}\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.exifinterface)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.yandex.mobileads)
}
