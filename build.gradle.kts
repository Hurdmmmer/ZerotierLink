// =========================
// 根 build.gradle.kts（Top-level build file）
// 作用：
// 1. 声明插件版本（集中管理）
// 2. 提供给子模块使用（apply false）
// 3. 不直接参与具体模块构建
// =========================


// -------------------------
// 插件声明（但不应用）
// -------------------------
plugins {

    // Android Application 插件（用于 app 模块）
    // apply false 表示：
    // - 这里只声明版本
    // - 不在 root 项目使用
    // - 具体由子模块（app/build.gradle.kts）使用
    alias(libs.plugins.android.application) apply false


    // Kotlin Compose 插件
    // 作用：
    // - 为 Jetpack Compose 提供 Kotlin 编译支持
    // - 通常用于 Compose 编译器接入
    //
    // apply false 同样表示：
    // - 不在根项目启用
    // - 由需要的模块自行使用
    alias(libs.plugins.kotlin.compose) apply false


    // Android Library 插件（用于 SDK / library 模块）
    // 作用：
    // - 构建 AAR 库
    // - 被 app 或其他模块依赖
    //
    // apply false：
    // - 仅声明版本
    // - 实际由 :zerotier-sdk 模块使用
    alias(libs.plugins.android.library) apply false
}