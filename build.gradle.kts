// Top-level build file where you can add configuration options common to all sub-projects/modules.
//
// 说明：为引入液态玻璃底栏（依赖 top.yukonga.miuix.kmp:miuix-*:0.9.4），
// 需要较新的 Kotlin / Compose。这里使用真实存在的稳定组合：
//   AGP 8.7.3 + Kotlin 2.0.21 + Compose Compiler 插件 2.0.21
// Kotlin 2.0 起 Compose 编译器已并入 Kotlin 插件（org.jetbrains.kotlin.plugin.compose），
// 不再靠 composeOptions.kotlinCompilerExtensionVersion。
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

