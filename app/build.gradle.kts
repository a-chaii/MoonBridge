plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.achaii.moonbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.achaii.moonbridge"
        minSdk = 24
        targetSdk = 35
        versionCode = 21
        versionName = "0.9.1"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildFeatures {
        // 界面要显示版本号（BuildConfig.VERSION_NAME）
        buildConfig = true
    }
}

// Force use of ARM64 binaries for AAPT2 in Proot environment
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.android.tools.build" && requested.name == "aapt2") {
            useTarget("com.android.tools.build:aapt2:${requested.version}:linux-aarch64")
        }
    }
}

dependencies {
    // Xposed API：只参与编译，运行时由 LSPosed 提供
    compileOnly("de.robv.android.xposed:api:82")

    // RecyclerView：只用于「包裹目标 App 的 Adapter」时声明类型，
    // 运行时解析 com.heytap.mydevices 里自带的那份，所以绝不能打进包
    compileOnly("androidx.recyclerview:recyclerview:1.2.1")

    implementation(libs.androidx.core.ktx)
    // Material 3 组件：整个模块界面都用这一套，跟随系统深浅色
    implementation("com.google.android.material:material:1.12.0")

    testImplementation(libs.junit)
}