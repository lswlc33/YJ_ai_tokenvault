import java.util.Properties

// AGP 9 自带 Kotlin 支持，所以这里没有 org.jetbrains.kotlin.android。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// 发布签名是本机的：keystore 与口令都不进仓库。四个值来自环境变量（CI 从 secret 注入），
// 或本地的 ~/.android/tokenvault-release.properties。
//
// 任一值缺失就不创建 signingConfig，于是 assembleRelease / assembleNightly 停在未签名 APK。
// 这个失败是故意的：用别的密钥签出来的包无法覆盖安装用户已有的版本，
// 静默回退到 debug 签名比构建失败更糟。
val localReleaseProperties = Properties().apply {
    val propertiesFile = file("${System.getProperty("user.home")}/.android/tokenvault-release.properties")
    if (propertiesFile.isFile) propertiesFile.inputStream().use { input -> load(input) }
}

fun releaseCredential(environmentName: String, propertyName: String): String? =
    providers.environmentVariable(environmentName).orNull ?: localReleaseProperties.getProperty(propertyName)

val releaseStoreFile = releaseCredential("VAULT_RELEASE_STORE_FILE", "storeFile")
val releaseStorePassword = releaseCredential("VAULT_RELEASE_STORE_PASSWORD", "storePassword")
val releaseKeyAlias = releaseCredential("VAULT_RELEASE_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = releaseCredential("VAULT_RELEASE_KEY_PASSWORD", "keyPassword")
val releaseSigningConfigured = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it != null }

// 版本号只在 gradle.properties 声明一处，"关于"页从 BuildConfig 读（计划.md §14.1）。
val appVersionCode = (
    providers.environmentVariable("VAULT_VERSION_CODE").orNull
        ?: providers.gradleProperty("vaultVersionCode").orNull
        ?: "1"
    ).toInt()
val appVersionName = providers.environmentVariable("VAULT_VERSION_NAME").orNull
    ?: providers.gradleProperty("vaultVersionName").orNull
    ?: "0.1.0"

android {
    namespace = "com.lc33.tokenvault"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lc33.tokenvault"
        minSdk = 33
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        // hilt-android-testing 需要一个会创建 HiltTestApplication 的 Runner
        testInstrumentationRunner = "com.lc33.tokenvault.HiltTestRunner"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 无密钥时产出未签名包，绝不回退到 debug 签名
            signingConfig = signingConfigs.findByName("release")
        }
        // nightly 与 release 配置完全一致，只是构建自任意提交而非 tag。
        // initWith 会把签名配置一起复制，这正是要的：nightly 与 release 能互相覆盖安装。
        create("nightly") {
            initWith(getByName("release"))
        }
    }

    buildFeatures {
        compose = true
        // "关于"页的版本号从 BuildConfig 读
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        localeFilters += listOf("zh-rCN", "en")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // 不做 ABI 分包：全项目零 native 库（BouncyCastle 纯 Java、不上 SQLCipher、不用 argon2kt），
    // 分出来的包内容完全一样。
}

// 会真的联网、会消耗额度的本地探针（M0.5 的协议踩点、M7 的余额适配器实测）默认跳过：
// 它们靠 `assumeTrue` 自己让开，所以 CI 与 pre-commit 都不会碰到网络。
// 需要跑的时候显式打开：
//
//     .\gradlew.bat :app:testDebugUnitTest --tests "*ProtocolSpike*" -DvaultSpike=true --info
//
// 用系统属性而不是环境变量，是因为 Gradle 守护进程可能带着上一次的环境活很久。
tasks.withType<Test>().configureEach {
    systemProperty("vaultSpike", providers.systemProperty("vaultSpike").getOrElse(""))
    testLogging {
        showStandardStreams = true
    }
}

// 用 Room Gradle 插件声明 schema 目录，不用 ksp arg（计划.md §15.9）。
// schemas/*.json 提交进仓库，否则迁移测试没有基线。
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // MIUIX —— 只允许 ui/miuix/ 直接 import
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.squircle)
    implementation(libs.miuix.blur)

    // Compose（不引入 material3）
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    // 数据层
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.androidx.hilt.compiler)

    // 后台任务
    implementation(libs.androidx.work.runtime)

    // 网络
    implementation(libs.okhttp)

    // 序列化 / 时间 / 并发
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.coroutines.android)

    // 加密
    implementation(libs.bouncycastle)
    implementation(libs.androidx.biometric)

    // JVM 单测（主力）
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)

    // 仪器测试
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
