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
    // Compose Compiler：CMP 1.6.10+ 要求显式应用（与 :app 同一个插件）。
    alias(libs.plugins.kotlin.compose)
    // 阶段3：Compose Multiplatform —— commonMain 里用 org.jetbrains.compose.* 写 UI。
    // 该插件同时负责 composeResources 资源的 Res 类生成（stringResource 等）。
    alias(libs.plugins.compose.multiplatform)
    // KSP：Room 编译器逐 target 挂（见下方 dependencies 块）
    alias(libs.plugins.ksp)
    // 阶段4：Room KMP。数据层（实体/DAO/VaultDatabase）从 :app 迁入 commonMain，
    // KSP 在每个 target 生成 _Impl 与 VaultDatabaseConstructor 的 actual。
    alias(libs.plugins.room)
}

// 版本号只在 gradle.properties 声明一处（计划.md §14.1）。阶段3 迁移后 screens 在
// commonMain 里读不到 Android 的 BuildConfig，所以这里读同一份 gradle.properties，
// 生成一个 commonMain 常量 `BuildInfo`，两端（Android/iOS）一致。
val vaultVersionCode: Long = (
    providers.environmentVariable("VAULT_VERSION_CODE").orNull
        ?: providers.gradleProperty("vaultVersionCode").orNull
        ?: "1"
    ).toLong()
val vaultVersionName: String = providers.environmentVariable("VAULT_VERSION_NAME").orNull
    ?: providers.gradleProperty("vaultVersionName").orNull
    ?: "0.1.0"

val generateBuildInfo = tasks.register("generateBuildInfo") {
    val dir = layout.buildDirectory.dir("generated/buildInfo/kotlin")
    inputs.property("versionName", vaultVersionName)
    inputs.property("versionCode", vaultVersionCode)
    outputs.dir(dir)
    doLast {
        val out = dir.get().asFile
        out.mkdirs()
        val f = out.resolve("BuildInfo.kt")
        f.writeText(
            """
            // 自动生成，勿手改。来源：shared/build.gradle.kts 的 generateBuildInfo。
            package com.lc33.tokenvault.platform

            /** 人类可读版本名，与 :app 的 BuildConfig.VERSION_NAME 同源（gradle.properties）。 */
            const val APP_VERSION_NAME: String = "$vaultVersionName"

            /** 整数版本号，与 :app 的 BuildConfig.VERSION_CODE 同源。 */
            const val APP_VERSION_CODE: Long = ${vaultVersionCode}L
            """.trimIndent() + "\n",
        )
    }
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
        commonMain {
            // 挂载 generateBuildInfo 生成的 BuildInfo.kt（版本号常量）。
            kotlin.srcDir(generateBuildInfo)
        }
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

            // 阶段3：Compose Multiplatform 基础库（UI 搬进 commonMain 用 org.jetbrains.compose.*）。
            api(libs.cmp.runtime)
            api(libs.cmp.foundation)
            api(libs.cmp.ui)
            api(libs.cmp.animation)
            implementation(libs.cmp.components.resources)
            implementation(libs.cmp.ui.tooling.preview)
            // multiplatform navigation / lifecycle（阶段3 替换 androidx.navigation.compose 等）。
            api(libs.cmp.navigation.compose)
            api(libs.cmp.lifecycle.runtime.compose)
            api(libs.cmp.lifecycle.viewmodel.compose)

            // Koin（阶段3：screens/ui 迁 commonMain 后 koinViewModel 用 KMP 版）。
            // koin-core + koin-compose-viewmodel 都是 KMP 库，提供 org.koin.compose.viewmodel.koinViewModel。
            api(libs.koin.core)
            api(libs.koin.compose.viewmodel)

            // MIUIX（阶段3：ui/miuix/ 迁入 commonMain）。这四个都是 KMP 库，有 iOS 产物。
            // miuix-blur 用跨平台版（不是 -android 版）。
            api(libs.miuix.ui)
            api(libs.miuix.preference)
            api(libs.miuix.icons)
            api(libs.miuix.blur.kmp)

            // Room KMP（阶段4：数据层迁入）。用 `api`：VaultDatabase / DAO 出现在
            // 仓库实现的公开构造签名里，:app 的 DI 与 androidTest 要能看见这些类型。
            api(libs.room.runtime)
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
            // iOS 没有系统 SQLite 的 androidx 驱动，只能用内置 SQLite 的 BundledSQLiteDriver。
            implementation(libs.sqlite.bundled)
        }

        // JVM 单测也用 JDK provider，让测试能在本机跑。
        jvmMain.dependencies {
            implementation(libs.cryptography.provider.jdk)
            implementation(libs.ktor.client.okhttp)
            // JVM 上 Room 同样只有 BundledSQLiteDriver 可用（app 的单测都是 FakeDao，
            // 不真开库，但 commonMain 的数据层要能在 jvm target 编过并保留运行可能性）。
            implementation(libs.sqlite.bundled)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

// Room 编译器：KSP 在 KMP 里没有统一的 common 入口，要逐 target 挂。
// 漏掉哪个 target，那个 target 就缺 VaultDatabase_Impl / VaultDatabaseConstructor 的 actual。
dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
}

// schema 目录跟着数据层一起从 app/schemas 迁到 shared/schemas（基线 1.json 已搬）。
room {
    schemaDirectory("$projectDir/schemas")
}

// ---------------------------------------------------------------- 修复：composeResources 进不了 Android APK
//
// AGP 9 的 KMP 库插件（com.android.kotlin.multiplatform.library）与 CMP 1.11.1 的
// assets 挂接断裂：CopyResourcesToAndroidAssetsTask（把 composeResources 拷进 AAR
// assets 的任务，Android 端 DefaultAndroidResourceReader 从 AssetManager 读）的
// outputDirectory 无人赋值——CMP 用的是旧版 addGeneratedSourceDirectory(wiredWith)
// 签名，AGP 9 不再代赋值。挂接静默失效，资源从不进 APK，中文系统上启动即崩：
// MissingResourceException: composeResources/.../values-zh-rCN/strings.commonMain.cvr。
// 上游修好前，这里手动补输出目录（任务类是 internal，只能反射拿属性，但
// DirectoryProperty 本身是公开接口）。赋值后 AGP 的挂接就能成立。
tasks.matching { it.name.endsWith("ComposeResourcesToAndroidAssets") }.configureEach {
    val outputDirectory = javaClass.getMethod("getOutputDirectory").invoke(this)
        as org.gradle.api.file.DirectoryProperty
    outputDirectory.set(
        layout.buildDirectory.dir("generated/compose/resourceGenerator/assetsForAndroid/${name}"),
    )
}
