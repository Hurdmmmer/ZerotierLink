// =========================
// Gradle Settings 配置文件
// 作用：
// 1. 定义插件从哪里下载
// 2. 定义依赖仓库策略
// 3. 定义有哪些模块（project）
// =========================


// -------------------------
// 插件解析配置（plugins {} 里的 id 从哪里下载）
// -------------------------
pluginManagement {
    repositories {
        // Google 仓库（Android 相关插件的主要来源）
        google {
            content {
                // 只允许以下 group 从 google 仓库解析
                // 目的：提升性能 + 避免错误仓库命中

                // Android Gradle Plugin（AGP）
                includeGroupByRegex("com\\.android.*")

                // Google 相关库（如 com.google.gms 等）
                includeGroupByRegex("com\\.google.*")

                // AndroidX 库
                includeGroupByRegex("androidx.*")
            }
        }

        // Maven Central（通用 Java/Kotlin 依赖仓库）
        mavenCentral()

        // Gradle 官方插件仓库（大部分 Gradle 插件来源）
        gradlePluginPortal()
    }
}


// -------------------------
// Settings 级插件（作用于整个构建系统，而不是某个模块）
// -------------------------
plugins {
    // Foojay Toolchain Resolver
    // 作用：
    // 1. 自动下载并管理 JDK（Java Toolchain）
    // 2. 保证开发机 / CI / 不同环境使用同一版本 JDK
    // 3. 避免“我这能跑你那不行”的问题
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}


// -------------------------
// 依赖解析策略（所有模块统一规则）
// -------------------------
dependencyResolutionManagement {

    // 仓库使用策略
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    // FAIL_ON_PROJECT_REPOS 含义：
    // - 禁止子模块（app / sdk）自己声明 repositories
    // - 如果子模块写 repositories，会直接报错
    //
    // 目的：
    // 1. 防止仓库污染（有人乱加私服）
    // 2. 保证依赖来源统一
    // 3. 提高构建安全性和一致性

    repositories {
        // Android 官方仓库（AndroidX、Compose、AGP 依赖等）
        google()

        // 通用 Java/Kotlin 依赖仓库
        mavenCentral()
    }
}


// -------------------------
// 根工程名称（显示用）
// -------------------------
rootProject.name = "Zerotier Link"


// -------------------------
// 声明当前工程包含的模块
// -------------------------
    include(":app")
include(":zerotier-sdk")

// 作用：
// 1. 告诉 Gradle 这个工程包含两个模块
// 2. Gradle 才会去加载对应目录下的 build.gradle.kts
//
// 对应目录结构通常为：
// root/
//   ├── app/
//   └── zerotier-sdk/
//
// 如果不 include：
// implementation(project(":zerotier-sdk")) 会直接报错