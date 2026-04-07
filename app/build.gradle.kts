plugins {
    // 应用 Android Application 插件
    // 作用：
    // 1. 把当前模块定义为 Android 应用模块
    // 2. 允许使用 android { } DSL
    // 3. 最终可以构建 APK / AAB，而不是 AAR
    alias(libs.plugins.android.application)

    // 应用 Kotlin Compose 相关插件
    // 作用：
    // 1. 为 Jetpack Compose 提供 Kotlin 编译期支持
    // 2. 一般用于 Compose 编译器接入
    //
    // 注意：
    // 这通常不能替代 kotlin-android 插件
    alias(libs.plugins.kotlin.compose)
}

android {
    // 当前应用的命名空间
    // 作用：
    // 1. 作为生成的 R 类等 Android 生成代码所在包空间
    // 2. 与 applicationId 不完全等价
    namespace = "io.github.jimmy.ztlink"

    // 编译时使用的 Android SDK 版本
    // 这里用了新版 DSL 写法，不是简单的 compileSdk = 36
    //
    // 作用：
    // 1. 决定编译时可用的 Android API
    // 2. 使用 Android API 36
    //
    // 这里的 minorApiLevel = 1 说明你在声明更细粒度的 SDK release 版本
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // 应用包名，安装到设备上的唯一应用标识
        // 作用：
        // 1. 真正决定安装包身份
        // 2. 上架、安装、升级都看这个值
        applicationId = "io.github.jimmy.ztlink"

        // 最低支持 Android 版本
        minSdk = 24

        // 目标适配的 Android 版本
        // 含义：
        // 1. 告诉系统你已经按 API 36 适配
        // 2. 会影响系统兼容行为开关
        targetSdk = 36

        // 应用内部版本号，必须递增
        // Android 系统和应用市场主要认这个做升级判断
        versionCode = 1

        // 展示给用户看的版本名
        versionName = "1.0"

        // Android 仪器化测试运行器
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // release 包是否开启混淆、压缩、优化
            // false 表示不启用 R8/ProGuard 压缩
            isMinifyEnabled = false

            // 指定混淆规则文件
            // 即使当前关闭了 minify，这里也只是预先配置好规则
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        // Java 源码语言级别
        sourceCompatibility = JavaVersion.VERSION_17

        // Java 字节码目标级别
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // 开启 Jetpack Compose
        // 作用：
        // 1. 启用 Compose UI 构建支持
        // 2. 没有这个，Compose 相关代码无法按预期工作
        compose = true
    }
}

dependencies {

    // 依赖本地模块 zerotier-sdk
    implementation(project(":zerotier-sdk"))

    // AndroidX Core KTX，提供更方便的 Kotlin 扩展
    implementation(libs.androidx.core.ktx)

    // Lifecycle runtime KTX
    // 提供生命周期相关 Kotlin 扩展能力
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Activity Compose
    // 让 Activity 能承载 Compose UI，如 setContent { ... }
    implementation(libs.androidx.activity.compose)

    // Compose BOM（Bill of Materials）
    // 作用：
    // 统一 Compose 相关依赖版本，避免 ui/material3/tooling 版本不一致
    implementation(platform(libs.androidx.compose.bom))

    // Compose UI 核心库
    implementation(libs.androidx.compose.ui)

    // Compose 图形能力库
    implementation(libs.androidx.compose.ui.graphics)

    // Compose 预览支持库
    implementation(libs.androidx.compose.ui.tooling.preview)

    // Material 3 组件库
    implementation(libs.androidx.compose.material3)

    // 单元测试依赖
    testImplementation(libs.junit)

    // Android 仪器测试 JUnit 扩展
    androidTestImplementation(libs.androidx.junit)

    // Espresso UI 测试库
    androidTestImplementation(libs.androidx.espresso.core)

    // Android 测试环境下也使用 Compose BOM 统一版本
    androidTestImplementation(platform(libs.androidx.compose.bom))

    // Compose UI 测试库
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    // debug 环境下启用 Compose 调试工具
    debugImplementation(libs.androidx.compose.ui.tooling)

    // debug 环境下启用 Compose 测试 manifest 支持
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}