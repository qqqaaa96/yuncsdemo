// Top-level build file where you can add configuration options common to all sub-projects/modules.
//
// 与 KernelSU manager 同步的版本组合：
//   AGP 9.4.1 + Kotlin 2.4.20 + Compose Compiler 插件 2.4.20 + Gradle 9.7.1 + JDK 21
// Kotlin 2.0 起 Compose 编译器已并入 Kotlin 插件（org.jetbrains.kotlin.plugin.compose），
// 不再靠 composeOptions.kotlinCompilerExtensionVersion。
plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9.0+ ships built-in Kotlin support, so 'org.jetbrains.kotlin.android'
    // must not be declared/applied anymore.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}

