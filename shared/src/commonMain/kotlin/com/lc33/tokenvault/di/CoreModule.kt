package com.lc33.tokenvault.di

import com.lc33.tokenvault.crypto.KnownSecrets
import com.lc33.tokenvault.crypto.RandomBytes
import com.lc33.tokenvault.crypto.Redactor
import com.lc33.tokenvault.crypto.SecretBox
import com.lc33.tokenvault.crypto.SecureRandomBytes
import com.lc33.tokenvault.data.VaultDatabase
import com.lc33.tokenvault.data.repo.FieldCipher
import com.lc33.tokenvault.data.repo.RoomApiKeyRepository
import com.lc33.tokenvault.data.repo.RoomAuditLogRepository
import com.lc33.tokenvault.data.repo.RoomBackupStore
import com.lc33.tokenvault.data.repo.RoomClientProfileRepository
import com.lc33.tokenvault.data.repo.RoomGroupRepository
import com.lc33.tokenvault.data.repo.RoomModelRepository
import com.lc33.tokenvault.data.repo.RoomProviderAccountRepository
import com.lc33.tokenvault.data.repo.RoomProbeRunRepository
import com.lc33.tokenvault.data.repo.RoomProviderRepository
import com.lc33.tokenvault.data.repo.RoomSettingsRepository
import com.lc33.tokenvault.data.repo.RoomTransactionRunner
import com.lc33.tokenvault.data.seed.ProfileSeeder
import com.lc33.tokenvault.backup.BackupStore
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import com.lc33.tokenvault.domain.repo.ClientProfileRepository
import com.lc33.tokenvault.domain.repo.GroupRepository
import com.lc33.tokenvault.domain.repo.ImportWriter
import com.lc33.tokenvault.domain.repo.ModelRepository
import com.lc33.tokenvault.domain.repo.ProviderAccountRepository
import com.lc33.tokenvault.domain.repo.ProbeRunRepository
import com.lc33.tokenvault.domain.repo.ProviderRepository
import com.lc33.tokenvault.domain.repo.SettingsRepository
import com.lc33.tokenvault.domain.repo.TransactionRunner
import com.lc33.tokenvault.engine.BackupEngine
import com.lc33.tokenvault.engine.BalanceEngine
import com.lc33.tokenvault.engine.IdleLockSuspender
import com.lc33.tokenvault.engine.ProbeEngine
import com.lc33.tokenvault.engine.ProbeSession
import com.lc33.tokenvault.engine.UpdateEngine
import com.lc33.tokenvault.net.HostGate
import com.lc33.tokenvault.net.HttpEngine
import com.lc33.tokenvault.net.ProxyProvider
import com.lc33.tokenvault.platform.APP_VERSION_NAME
import com.lc33.tokenvault.platform.AutoLocker
import com.lc33.tokenvault.platform.VaultSession
import com.lc33.tokenvault.platform.monotonicNanoTime
import com.lc33.tokenvault.platform.nowMillis
import com.lc33.tokenvault.ui.shell.AppearanceViewModel
import com.lc33.tokenvault.ui.shell.BalanceThresholdsViewModel
import com.lc33.tokenvault.ui.shell.ClientKeywordsViewModel
import com.lc33.tokenvault.ui.shell.DashboardViewModel
import com.lc33.tokenvault.ui.shell.DataViewModel
import com.lc33.tokenvault.ui.shell.ImportViewModel
import com.lc33.tokenvault.ui.shell.LockViewModel
import com.lc33.tokenvault.ui.shell.LogViewModel
import com.lc33.tokenvault.ui.shell.ManageViewModel
import com.lc33.tokenvault.ui.shell.ProbeRunViewModel
import com.lc33.tokenvault.ui.shell.ProbeSettingsViewModel
import com.lc33.tokenvault.ui.shell.ProfileEditorViewModel
import com.lc33.tokenvault.ui.shell.ProfileListViewModel
import com.lc33.tokenvault.ui.shell.ProviderDetailViewModel
import com.lc33.tokenvault.ui.shell.ProviderEditorViewModel
import com.lc33.tokenvault.ui.shell.ProxyViewModel
import com.lc33.tokenvault.ui.shell.SecurityViewModel
import com.lc33.tokenvault.ui.shell.SyncViewModel
import com.lc33.tokenvault.ui.shell.UpdateViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Koin 跨平台模块（阶段4：从 :app 的 AppModule 拆出，平台无关的部分全部搬进 commonMain）。
 *
 * 三个 named 限定符对应原来 Hilt 的三个 `@Qualifier`：
 * - [Qualifiers.NOW]          → 原 `@NowEpochMs` 的墙上时间 lambda（红线 20：注入而非直接读）。
 * - [Qualifiers.PLACEHOLDERS] → 原 `@AppPlaceholders` 的客户端占位符 Map（红线 20）。
 * - [Qualifiers.APP_SCOPE]    → 原 `@AppScope` 的应用级协程作用域。
 *
 * 墙上时间与单调时钟都走 platform/TimeNow 的 expect/actual，Android 与 iOS 各有 actual，
 * commonMain 里只拿函数引用。平台相关的东西（数据库、boot 存储、剪贴板、占位符值）
 * 全部住在 [platformModule] 的各平台 actual 里。
 */
object Qualifiers {
    const val NOW = "now"
    const val PLACEHOLDERS = "placeholders"
    const val APP_SCOPE = "appScope"
}

val coreModule = module {

    // ------------------------------------------------------------------ 基础能力

    single(named(Qualifiers.APP_SCOPE)) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    single<RandomBytes> { SecureRandomBytes }

    single(named(Qualifiers.NOW)) {
        ::nowMillis
    }

    single { SecretBox(get()) }
    single { KnownSecrets() }
    single { Redactor(knownSecrets = get<KnownSecrets>()::snapshot) }

    // ------------------------------------------------------------------ 会话与自动锁定

    single { VaultSession(bootStore = get(), nowEpochMs = ::nowMillis, random = get(), knownSecrets = get()) }

    // 阶段2 抽出的引擎侧接口。get() 只按精确类型解析、不查子类型，漏了这两个定义时启动即
    // NoDefinitionFoundException（ProbeEngine/BackupEngine 构造不出来）。
    single<ProbeSession> { get<VaultSession>() }
    single<IdleLockSuspender> { get<AutoLocker>() }

    single {
        AutoLocker(
            session = get(),
            scope = get(named(Qualifiers.APP_SCOPE)),
            elapsedRealtimeMs = { monotonicNanoTime() / 1_000_000 },
            onLock = { get<ProbeEngine>().onLock() },
        )
    }

    // ------------------------------------------------------------------ 数据库与 DAO

    single { get<VaultDatabase>().groupDao() }
    single { get<VaultDatabase>().providerDao() }
    single { get<VaultDatabase>().apiKeyDao() }
    single { get<VaultDatabase>().providerAccountDao() }
    single { get<VaultDatabase>().clientProfileDao() }
    single { get<VaultDatabase>().modelDao() }
    single { get<VaultDatabase>().modelCatalogDao() }
    single { get<VaultDatabase>().probeRunDao() }
    single { get<VaultDatabase>().auditLogDao() }
    single { get<VaultDatabase>().appSettingDao() }

    // ------------------------------------------------------------------ 仓库

    single<TransactionRunner> { RoomTransactionRunner(get()) }
    single { FieldCipher(get(), get()) }
    single { ImportWriter(get(), get(), get(), get(), get()) }
    single { ProfileSeeder(get()) }

    single<GroupRepository> { RoomGroupRepository(get()) }
    single<ProviderRepository> { RoomProviderRepository(get(), get(), get(), get(named(Qualifiers.NOW))) }
    single<ApiKeyRepository> { RoomApiKeyRepository(get(), get(), get(), get(named(Qualifiers.NOW))) }
    single<SettingsRepository> { RoomSettingsRepository(get()) }
    single<ProviderAccountRepository> { RoomProviderAccountRepository(get(), get(), get(), get(named(Qualifiers.NOW))) }
    single<ModelRepository> { RoomModelRepository(get(), get(named(Qualifiers.NOW))) }
    single<ClientProfileRepository> { RoomClientProfileRepository(get()) }
    single<AuditLogRepository> { RoomAuditLogRepository(get(), get(), get(named(Qualifiers.NOW))) }
    single<ProbeRunRepository> { RoomProbeRunRepository(get()) }

    // ------------------------------------------------------------------ 网络与引擎

    single { HostGate(nowMillis = ::nowMillis) }
    single { ProxyProvider(get()) }
    single { HttpEngine(client = get<ProxyProvider>().client, hostGate = get()) }
    single { com.lc33.tokenvault.backup.BackupCodec(get()) }
    single<BackupStore> {
        RoomBackupStore(
            groupDao = get(), providerDao = get(), keyDao = get(), accountDao = get(),
            profileDao = get(), modelDao = get(), probeRunDao = get(), appSettingDao = get(),
            cipher = get(), transactions = get(), bootStore = get(), now = get(named(Qualifiers.NOW)),
        )
    }
    single {
        BackupEngine(
            store = get(), codec = get(), random = get(), audit = get(),
            autoLocker = get(), now = get(named(Qualifiers.NOW)),
        )
    }
    single { BalanceEngine(get(), get(), get(), get(), get(named(Qualifiers.NOW))) }
    single {
        ProbeEngine(
            providers = get(), keys = get(), clientProfiles = get(), runRepository = get(),
            session = get(), engine = get(), audit = get(), settings = get(), autoLocker = get(),
            redactor = get(), knownSecrets = get(), now = get(named(Qualifiers.NOW)),
            placeholders = get(named(Qualifiers.PLACEHOLDERS)),
        )
    }
    single {
        UpdateEngine(
            engine = get(),
            // 版本号从 shared 生成的 BuildInfo 拿（与 :app 的 BuildConfig 同源于
            // gradle.properties），commonMain 读不到 Android 的 BuildConfig。
            currentVersionName = APP_VERSION_NAME,
            repoUrl = UpdateEngine.RELEASES_URL,
        )
    }
}

// ViewModel 统一用 viewModelOf 注册（Koin 反射解析构造参数，SavedStateHandle 自动注入）。
// 用 koin-compose-viewmodel 的 DSL（org.koin.viewmodel.dsl），Android/iOS 通用。
val viewModelModule = module {
    viewModelOf(::AppearanceViewModel)
    viewModelOf(::BalanceThresholdsViewModel)
    viewModelOf(::ClientKeywordsViewModel)
    viewModelOf(::DashboardViewModel)
    viewModelOf(::DataViewModel)
    viewModelOf(::ImportViewModel)
    viewModelOf(::LockViewModel)
    viewModelOf(::LogViewModel)
    viewModelOf(::ManageViewModel)
    viewModelOf(::ProbeRunViewModel)
    viewModelOf(::ProbeSettingsViewModel)
    viewModelOf(::ProfileEditorViewModel)
    viewModelOf(::ProfileListViewModel)
    viewModelOf(::ProviderDetailViewModel)
    viewModelOf(::ProviderEditorViewModel)
    viewModelOf(::ProxyViewModel)
    viewModelOf(::SecurityViewModel)
    viewModelOf(::SyncViewModel)
    viewModelOf(::UpdateViewModel)
}
