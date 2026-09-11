import java.util.Properties
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

// AGP 9 自带 Kotlin 支持，所以这里没有 org.jetbrains.kotlin.android。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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
        // 阶段2 迁移 Koin 后不再需要 HiltTestApplication，用标准 runner。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // MigrationTestHelper 从测试 APK 的 assets 里读 Room 导出的 schema JSON。
    sourceSets.getByName("androidTest") {
        assets.srcDir(layout.projectDirectory.dir("../shared/schemas"))
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

// ---------------------------------------------------------------- 修复：把 shared 的 composeResources 接进 app assets
//
// AGP 9 的 KMP 库插件与 CMP 1.11.1 的 assets 挂接断裂（详见 shared/build.gradle.kts
// 同名修复块），shared 侧把 copyAndroidMainComposeResourcesToAndroidAssets 修活了，
// 但 AGP 的 addGeneratedSourceDirectory 挂接依然不生效——任务从不进 app 依赖图。
// 只能在这里自己接线：把 shared 的 composeResources 源目录拷成 assets 需要的形状
// （composeResources/<Res 包>/…，DefaultAndroidResourceReader 从 AssetManager 读），
// 再经 androidComponents 挂进每个 variant 的 assets（这个 API 在传统 application
// 插件上是好的）。
//
// 注意：`tokenvault.shared.generated.resources` 是 CMP 自动推导的 Res 包名
// （见 shared/build/generated/…/commonResClass 下的实际目录），改名会静默崩，
// 改 shared 模块名/包名时要同步改这里。
// 把 shared 的 composeResources 拷成 assets 形状。自定义任务类而不是 Copy：
// addGeneratedSourceDirectory 需要一个可引用的 @get:OutputDirectory 属性，
// Gradle 9 的 Copy 任务上 destinationDirectory 在 Kotlin DSL 里解析不到。
abstract class CopySharedComposeAssetsTask : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Inject
    protected abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun action() {
        // 先清空输出：避免残留旧文件（换源/改包名后的度产物不能跟着进 APK）。
        fileSystem.delete { delete(output) }
        fileSystem.copy {
            from(sources)
            into(output.dir("composeResources/tokenvault.shared.generated.resources"))
            includeEmptyDirs = false
        }
    }
}

val copySharedComposeAssets by tasks.registering(CopySharedComposeAssetsTask::class) {
    // 源不是源码目录而是 CMP 转换后的 prepared 产物（strings.xml → .cvr）。
    // commonMain 在前、androidMain 在后：同名资源 androidMain 覆盖，与 CMP 的源集
    // 语义一致（androidMain 目前没有自己的 composeResources，目录不存在时 from 静默跳过）。
    dependsOn(
        project(":shared").tasks.named("prepareComposeResourcesTaskForCommonMain"),
        project(":shared").tasks.named("prepareComposeResourcesTaskForAndroidMain"),
    )
    sources.from(
        project(":shared").layout.buildDirectory.dir("generated/compose/resourceGenerator/preparedResources/commonMain/composeResources"),
        project(":shared").layout.buildDirectory.dir("generated/compose/resourceGenerator/preparedResources/androidMain/composeResources"),
    )
    output.set(layout.buildDirectory.dir("generated/composeAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copySharedComposeAssets, CopySharedComposeAssetsTask::output)
    }
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


dependencies {
    // 跨平台共享模块（阶段2）：纯 Kotlin 包都移到这里
    implementation(project(":shared"))

    // MIUIX —— 只允许 ui/miuix/ 直接 import
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
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

    // 数据层已迁入 :shared（Room KMP，阶段4）。room-runtime 由 shared 的 api 传递，
    // schema 基线与 KSP 编译器都住在 shared/build.gradle.kts。

    // DI（阶段2 迁移 Hilt→Koin）
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.koin.compose.viewmodel)

    // 后台任务
    implementation(libs.androidx.work.runtime)

    // 网络
    implementation(libs.okhttp)

    // 序列化 / 时间 / 并发
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.coroutines.android)

    // 加密：cryptography-kotlin（PBKDF2 / HKDF / AES-GCM / HMAC），替代 BouncyCastle
    implementation(libs.cryptography.core)
    implementation(libs.cryptography.provider.jdk)
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
}
