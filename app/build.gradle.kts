plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "at.goinon.badvigaun.terminplan"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig { applicationId = "at.goinon.badvigaun.terminplan"; minSdk = 26; targetSdk = 37; versionCode = 20; versionName = "1.0.20" }
    signingConfigs { getByName("debug") { storeFile = rootProject.file(".local/debug.keystore"); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android" } }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            ndk {
                debugSymbolLevel = "FULL"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures { compose = true }
    packaging {
        jniLibs {
            keepDebugSymbols += setOf(
                "**/libandroidx.graphics.path.so",
                "**/libc++_shared.so",
                "**/libmlkit_google_ocr_pipeline.so",
                "**/libopencv_java5.so"
            )
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("org.opencv:opencv:5.0.0.1")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    testImplementation("junit:junit:4.13.2")
}
