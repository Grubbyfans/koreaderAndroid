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
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
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

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // 阶段 B 试音：仅测试版打包 sherpa-onnx 运行时，正式版不含该依赖
    add("ttsTestImplementation", files("${rootProject.projectDir}/speech/libs/sherpa-onnx-1.13.4.aar"))
    // 阶段 E：测试版模型导入支持 ZIP / tar.bz2（正式版不含模型管理）
    add("ttsTestImplementation", "org.apache.commons:commons-compress:1.27.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
