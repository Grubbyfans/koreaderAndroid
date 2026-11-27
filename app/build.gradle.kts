plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.yuezhou.reader"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yuezhou.reader"
        minSdk = 26
        targetSdk = 36
        versionCode = 46
        versionName = "0.1.45"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 固定测试版签名：不再依赖各机器 ~/.android/debug.keystore，避免换机或密钥重建后签名漂移。
    // android/android 是 Android 公开默认值；此密钥用于本地安装验证。
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // 安装隔离：ttsTest 使用独立 applicationId，可与正式版并存；production 保持原包名不变。
    flavorDimensions += "distribution"
    productFlavors {
        create("production") {
            dimension = "distribution"
            // 临时沿用旧版签名，以便本地 Release APK 可以覆盖安装。
            signingConfig = signingConfigs.getByName("debug")
        }
        create("ttsTest") {
            dimension = "distribution"
            applicationIdSuffix = ".tts.test"
            versionNameSuffix = "-tts-test"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":reader"))
    implementation(project(":speech"))
    implementation(project(":nettts"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
