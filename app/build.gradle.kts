plugins {
    id("com.android.application")
    // AGP 9.0+ has built-in Kotlin support: the old
    // 'org.jetbrains.kotlin.android' plugin must NOT be applied.
    id("org.jetbrains.kotlin.plugin.compose")
    // miuix-nav 的 NavKey 需要可序列化
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "sy.great.toolbox"
    compileSdk {
        version =
            release(37) {
                minorApiLevel = 0
            }
    }

    defaultConfig {
        applicationId = "sy.great.toolbox"
        // miuix-blur 0.9.4 requires minSdk >= 33
        minSdk = 33
        targetSdk = 37
        // 版本规则（与项目约定一致）：
        //   · 小改动不动版本号
        //   · 中间改动递增末位：1.0 -> 1.0.1 -> 1.0.2 ...
        //   · 每个中间改动都带 beta 后缀
        // versionCode 必须单调递增，否则装不上：新版本 = 原值 * 100 + 中间序号
        versionCode = 102
        versionName = "1.0.2-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // ---- Radare2 JNI ----
        // 只编 arm64-v8a：预编译的 libr_*.so 只有这个 ABI。
        // 加其它 ABI 会因缺库而链接失败。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // ---- assets 不压缩 ----
    //
    // assets/shbin 下是 bash / openssl / coreutils 及其依赖库，
    // 它们在运行时解压到 filesDir，再由 /system/bin/linker64 加载执行。
    //
    // 这些是 ELF，被 aapt deflate 压缩后解压出来会损坏（Exec format error）。
    // AGP 的 noCompress 只按【后缀】匹配，写路径不生效，
    // 所以 assets 里的可执行文件统一加了 .so 后缀（bash.so / gzip.so …），
    // .so 默认就不压缩；运行时再把后缀去掉。
    androidResources {
        noCompress += "so"
        noCompress += "sh"
    }

    // ---- Radare2 JNI 桥（CMake） ----
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "27.0.12077973"

    // jniLibs 里的预编译 libr_*.so 不参与 strip（它们没有调试符号，
    // 而且 strip 可能破坏 r2 的插件机制）
    packaging {
        jniLibs {
            keepDebugSymbols += "**/libr_*.so"
            keepDebugSymbols += "**/libcore_ghidra.so"
            keepDebugSymbols += "**/libandroid-reverse-native.so"
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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        // Shizuku UserService 需要 AIDL 生成 IUserService 接口
        aidl = true
    }
    // 注意：Kotlin 2.0 起 Compose 编译器由 org.jetbrains.kotlin.plugin.compose 提供，
    // 不再需要 composeOptions.kotlinCompilerExtensionVersion。
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")

    // lifecycle / activity 必须与 KernelSU manager 对齐：
    //
    // activity-compose 1.13.0+ 才会通过 ComponentActivity 自动提供
    // LocalNavigationEventDispatcherOwner，而 miuix 的 WindowDialog
    // 内部用 NavigationBackHandler 依赖它。
    // 用 1.9.3 时报：
    //   IllegalStateException: No NavigationEventDispatcher was provided
    //     via LocalNavigationEventDispatcherOwner
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Compose：与 KernelSU manager 同步（BOM 2026.09.00 + material3 1.5.0-alpha28）
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.5.0-alpha28")
    // 固定版本：BOM 2026.09.00 已移除 material-icons-extended，
    // 不固定版本会拉不到，导致 Icons.Rounded.* 全部 Unresolved。
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    // 液态玻璃底栏 + KernelSU 主页 UI：miuix-kmp 全套
    //（Backdrop / LayerBackdrop / MiuixTheme / Card / BasicComponent / ArrowPreference）
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-nav-android:0.9.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 序列化运行时（miuix-nav 的 NavKey 用）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")

    // ---- NavigationEvent（miuix WindowDialog 必需）----
    //
    // miuix 的 WindowDialog 内部调 NavigationBackHandler，
    // 它要求宿主提供 LocalNavigationEventDispatcherOwner。
    // 不引这个库会报：
    //   IllegalStateException: No NavigationEventDispatcher was provided
    //     via LocalNavigationEventDispatcherOwner
    // KernelSU 的 manager 也引了同一个库（navigationevent 1.1.2）。
    implementation("androidx.navigationevent:navigationevent-compose:1.1.2")

    // ---- 液态玻璃（Liquid Glass）----
    //
    // 注意：这里不引 io.github.kyant0:backdrop。
    // 那个库是 JetBrains Compose Multiplatform 产物，
    // 与本项目的 AndroidX Compose 在 GraphicsLayer / Modifier.Node
    // 底层二进制上不完全兼容，运行时会崩在渲染线程。
    //
    // 已把核心逻辑（AGSL shader + RuntimeShader 折射）
    // 移植为项目内的 ui/glass/LiquidGlass.kt，只依赖 Android 系统 API 与 AndroidX Compose。

    // ---- 二维码 / 条形码生成 ----
    //
    // core 负责编码（纯 Java，无 UI 依赖），体积小。
    // 不引 android-core，避免把旧版 Camera 引入。
    implementation("com.google.zxing:core:3.5.3")

    // ---- Markdown 渲染（与 KernelSU 同一套）----
    // 更新日志 / 公告弹窗用 CommonMark 转 HTML 后在 WebView 里显示，
    // 排版效果与 KernelSU 的 GithubMarkdown 一致（大号标题、灰粗分割线、表格、代码块）。
    implementation("org.commonmark:commonmark:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.22.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.22.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.22.0")

    // Shizuku（ADB shell 模式）：官方 API + 权限 provider
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

