plugins {
    id("com.android.application")
}

android {
    namespace = "com.myclick"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.myclick"
        minSdk = 31              // Android 12
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // 模块本身不需要任何运行时三方库：UI 全部用 framework 控件手写。
    }

    signingConfigs {
        // release 默认复用 debug 签名，保证 assembleRelease 产出的 APK 直接可安装。
        getByName("debug") {
            // 使用 AGP 默认 debug.keystore
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

    // 版本必须与 AGP 9.4.1 内置 Kotlin 编译器（2.2.0）一致，否则 metadata 不兼容。
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
}
