plugins {
    // 应用 Android Library 插件
    // 作用：
    // 1. 把当前模块当作 Android 库模块，而不是 Android 应用
    // 2. 提供 android { } 这套 DSL 配置能力
    // 3. 最终产物通常是 AAR，而不是可直接安装的 APK
    id("com.android.library")
}

android {
    // 当前 Android 模块的命名空间
    // 作用：
    // 1. 作为生成 R 类、BuildConfig（如果开启）等的包命名空间
    // 2. 也是 AGP 8+ 强制要求显式声明的内容
    // 3. 它不完全等同于 AndroidManifest 里的 package，但通常语义接近
    //
    // 这里写 com.zerotier.sdk，表示你希望这个模块对外呈现为这个 SDK 包空间
    namespace = "com.zerotier.sdk"

    // 编译 SDK 版本
    // 作用：
    // 1. 决定编译时使用哪个 Android API Level 的 android.jar
    // 2. 决定你在代码里可以直接调用哪些 Android 新 API
    //
    // 注意：
    // compileSdk 影响“编译能力”，不直接决定运行最低版本
    compileSdk = 36

    // 指定使用哪一版 NDK
    // 作用：
    // 1. 你的模块里用了 CMake + JNI + native 编译
    // 2. 这里明确要求 Gradle/AGP 使用这个版本的 Android NDK
    //
    // 如果不固定版本，团队成员或 CI 环境可能因为 NDK 版本不同导致构建结果不一致
    ndkVersion = "27.0.12077973"

    defaultConfig {
        // 最低支持的 Android 版本
        // 作用：
        // 1. 声明这个库最低支持 API 24
        // 2. 低于这个版本的设备不应使用该产物
        //
        // 对 library 来说，它更多是参与清单合并和变体配置，不像 application 那样直接定义安装门槛
        minSdk = 24
    }

    buildTypes {
        release {
            // 是否开启代码压缩、混淆、优化
            // false 表示 release 构建时不做 R8/ProGuard 压缩
            //
            // 影响：
            // 1. 构建更简单
            // 2. 调试符号更完整
            // 3. 产物可能更大
            // 4. Java/Kotlin 层代码不会被裁剪/混淆
            //
            // 对 JNI SDK 封装模块来说，很多时候先关掉，避免额外问题
            isMinifyEnabled = false
        }
    }

    compileOptions {
        // 指定 Java 源码级别
        // 作用：
        // 1. 告诉 Java 编译器按 Java 17 语法解析源码
        // 2. 例如可使用 Java 17 支持的语言特性（前提是 Android 工具链支持）
        sourceCompatibility = JavaVersion.VERSION_17

        // 指定目标字节码级别
        // 作用：
        // 1. 控制编译输出兼容的 Java 字节码目标版本
        // 2. 一般和 sourceCompatibility 保持一致
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            // 指定 CMakeLists.txt 的路径
            // 作用：
            // 1. 告诉 AGP：native 部分由 CMake 管理
            // 2. 这里直接复用上游 zerotier-one 提供的 CMake 构建脚本
            //
            // 本质上：
            // Gradle 负责“调度”
            // CMake 负责“真正的 C/C++ 编译规则”
            path = file("../externals/zerotier-one/java/CMakeLists.txt")

            // 指定 CMake 版本
            // 作用：
            // 1. 限定构建时使用的 CMake 版本
            // 2. 避免不同机器上 CMake 行为不一致
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            // 把外部 Java 源码目录挂入当前模块的 main 源码集
            // 作用：
            // 1. 当前模块编译时，会把 ../externals/zerotier-one/java/src 下的 Java 源码一起编译
            // 2. 这样你不需要复制源码进本模块
            //
            // 注意：
            // 这是“追加源码目录”，不是 Maven 那种标准目录约定
            // 而是显式告诉 Gradle：源码还在别的地方
            java.directories.add("../externals/zerotier-one/java/src")
        }
    }

    buildFeatures {
        // 禁止生成 BuildConfig.java
        // 作用：
        // 1. 默认有些模块会生成 BuildConfig 类
        // 2. 关闭后可减少一点生成代码和构建步骤
        //
        // 适用场景：
        // 如果你不需要 BuildConfig.DEBUG / BuildConfig.FLAVOR / 自定义 buildConfigField
        // 那么关闭是合理的
        buildConfig = false
    }
}

dependencies {
    // 当前没有声明任何 Java/Kotlin/Android 依赖
    //
    // 如果后续你需要：
    // - androidx.annotation
    // - junit
    // - testImplementation
    // - implementation(project(":xxx"))
    // 都是在这里加
}