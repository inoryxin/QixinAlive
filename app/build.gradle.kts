plugins {
    id("com.android.application")
}

import java.util.Properties

// ---------------------------------------------------------------- 签名
// 复用工作区里已有的祈歆签名（C:\Users\anzu\Documents\xin\keystore）。
// 可用环境变量 QIXIN_KEYSTORE_DIR 覆盖。
val keystoreDir: File = run {
    val env = System.getenv("QIXIN_KEYSTORE_DIR")
    if (!env.isNullOrBlank()) File(env) else rootProject.file("../keystore")
}

val keystoreProps: Properties = Properties().apply {
    val f = File(keystoreDir, "keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "xin.inory.alive"
    compileSdk = 35

    defaultConfig {
        applicationId = "xin.inory.alive"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.4.1"
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                // Properties 会把反斜杠当转义符，所以这里强制用正斜杠
                storeFile = File(keystoreDir, keystoreProps.getProperty("storeFile").replace('\\', '/'))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (keystoreProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // 自己用的 app，不为 lint 警告中断构建
        abortOnError = false
        checkReleaseBuilds = false
    }

    buildFeatures {
        buildConfig = true
    }
}

// 无第三方依赖：HTTP 用 HttpURLConnection，JSON 用 Android 自带 org.json。
// 这样既不用联网拉依赖，APK 也极小。
dependencies {
}
