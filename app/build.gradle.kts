plugins {
    id("com.android.application")
}

android {
    namespace = "com.myclick"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    defaultConfig {
        applicationId = "com.myclick"
        minSdk = 31              // Android 12
        targetSdk = 36
        versionCode = 48
        versionName = "1.0.47"
        // 模块本身不需要任何运行时三方库：UI 全部用 framework 控件手写。
    }

    signingConfigs {
        // release 默认复用 debug 签名，保证 assembleRelease 产出的 APK 直接可安装。
        getByName("debug") {
            // 有工程内 debug key 时优先使用它，避免换环境后签名漂移；
            // 没有则回落到 AGP 默认 debug.keystore（供干净检出使用）。
            val projectKey = rootProject.file(".android-user/debug.keystore")
            if (projectKey.isFile) {
                storeFile = projectKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = providers.gradleProperty("myclick.enableMinify").get().toBoolean()
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // app_process 以绝对路径 System.load() 原生库，必须让安装器把 .so
        // 解压到 nativeLibraryDir；默认的“直接从 APK 映射”模式没有实体文件。
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "kotlin/**",
            "DebugProbesKt.bin"
        )
    }
}

dependencies {
    // Xposed API 仅参与编译，绝不打进 APK（运行时由 LSPosed 提供）。
    // jar 已随工程交付，离线也能构建。等价远程依赖：
    //   compileOnly("de.robv.android.xposed:api:82")
    compileOnly(files("libs/xposed-api-82.jar"))

    // Shizuku：仅用于「Shizuku 模式」以 shell 身份读取触摸屏设备节点。
    // 这是工程里唯一的三方运行时依赖 —— 免 root 又要真实触摸坐标，只有这条路。
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // 版本必须与 AGP 9.4.1 内置 Kotlin 编译器（2.2.0）一致，否则 metadata 不兼容。
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
}
