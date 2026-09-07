// :shared —— 跨平台共享模块（阶段2）。
//
// commonMain 收进「零 android 依赖」的纯 Kotlin 包：domain / crypto / probe /
// importer / balance / catalog / endpoint（engine 包因耦合 data/net/platform，
// 待接口抽象后迁入，见迁移计划阶段2）。
//
// 用 AGP 9 官方 KMP 库插件 `com.android.kotlin.multiplatform.library`：
// Android target 用 `kotlin.android {}` 块（AGP ≥ 8.12 的写法），
// 不再用 androidTarget() + 顶层 android {}。
//
// 目标：
//   - android：Android 照常构建，产物给 :app 依赖
//   - iosArm64 / iosSimulatorArm64：iOS 构建（阶段4，仅在 macOS CI 上编译）
//   - jvm：JVM 单测（本机无 Mac 也能跑测试）
//
// iOS target 在本机 Windows 上无法编译（Kotlin/Native 的 iOS 目标要求 macOS），
// 但声明 target 本身不会在配置阶段失败，只有显式执行 iOS 编译任务才会。
// 本机日常只跑 android / jvm 任务。

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // iOS 三件套：真机 + 模拟器。Kotlin/Native 的 iOS target 只能在 macOS 编译，
    // 本机 Windows 跑不到，但声明无害（阶段4 在 mac runner 上用）。
    iosArm64()
    iosSimulatorArm64()

    // 给 iOS 产出的 framework 命名。默认会按 project 名 `shared` 产 `Shared.framework`，
    // 这里显式写死 baseName，让 iosApp 的 Xcode 工程能稳定引用 `Shared`。
    // 用 dynamic framework（默认），这样 `embedAndSignAppleFrameworkForXcode` 任务会被注册，
    // Xcode 的 build phase 脚本能调用它完成 embed；未签名构建时（CODE_SIGNING_ALLOWED=NO）
    // 它只 embed 不签名。
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
        }
    }

    // JVM 目标：让纯 Kotlin 包在 JVM 上跑单测（本机无 Mac 也能验证逻辑）。
    jvm()

    // Android target（AGP 9 官方 KMP 库插件的写法）。
    android {
        namespace = "com.lc33.tokenvault.shared"
        compileSdk = 37
        minSdk = 33
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // 各平台 source set 的依赖。cryptography-kotlin / kotlinx-serialization /
    // kotlinx-datetime / kotlinx-coroutines 都是 KMP 库，直接放 commonMain。
    sourceSets {
        commonMain.dependencies {
            implementation(libs.cryptography.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)
            // backup/ 的 gzip（GzipSink/GzipSource 全 KMP，native 走 zlib）。
            implementation(libs.okio)
            // Ktor Client（阶段2 OkHttp→Ktor）：net/ 层的 HTTP 引擎。
            // 用 `api` 而不是 `implementation`：HttpEngine / ProxyProvider 的公开构造签名里
            // 有 io.ktor.client.HttpClient，:app 需要能看见它。
            api(libs.ktor.client.core)
        }

        // Android 端用 JDK provider（JCA 实现，与现有行为一致）。
        androidMain.dependencies {
            implementation(libs.cryptography.provider.jdk)
            implementation(libs.ktor.client.okhttp)
        }

        // iOS 端用 Apple provider（CryptoKit 原生）。
        iosMain.dependencies {
            implementation(libs.cryptography.provider.apple)
            implementation(libs.ktor.client.darwin)
        }

        // JVM 单测也用 JDK provider，让测试能在本机跑。
        jvmMain.dependencies {
            implementation(libs.cryptography.provider.jdk)
            implementation(libs.ktor.client.okhttp)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
        }
    }
}
