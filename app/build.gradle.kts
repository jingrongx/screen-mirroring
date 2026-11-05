import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 读取仓库配置（自动更新指向的 GitHub 仓库）
val repoProps = Properties().apply {
    val f = rootProject.file("gradle.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val repoOwner = repoProps.getProperty("repo.owner", "jingrongx")
val repoName = repoProps.getProperty("repo.name", "screen-mirroring")
val repoBranch = repoProps.getProperty("repo.branch", "main")

// Release 签名：优先 CI 环境变量，其次 gradle.properties；未配置时回退 debug 签名，保证开箱即用
val keystorePath = System.getenv("KEYSTORE_FILE")
    ?: repoProps.getProperty("release.storeFile")?.let { rootProject.file(it).absolutePath }
val keystoreReady = !keystorePath.isNullOrBlank() && File(keystorePath).exists()

android {
    namespace = "io.github.jingrongx.screenmirror"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.jingrongx.screenmirror"
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"

        buildConfigField("String", "REPO_OWNER", "\"$repoOwner\"")
        buildConfigField("String", "REPO_NAME", "\"$repoName\"")
        buildConfigField("String", "REPO_BRANCH", "\"$repoBranch\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystorePath ?: "dummy-not-exists.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: repoProps.getProperty("release.storePassword", "")
            keyAlias = System.getenv("KEY_ALIAS") ?: repoProps.getProperty("release.keyAlias", "")
            keyPassword = System.getenv("KEY_PASSWORD") ?: repoProps.getProperty("release.keyPassword", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreReady) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)

    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.stream.webrtc)
}
