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
import com.lc33.tokenvault.data.repo.UndoRestorer
import com.lc33.tokenvault.data.repo.RoomSettingsRepository
import com.lc33.tokenvault.data.repo.RoomWebDavSettingsRepository
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
import com.lc33.tokenvault.domain.repo.WebDavSettingsRepository
import com.lc33.tokenvault.domain.repo.TransactionRunner
import com.lc33.tokenvault.engine.AutoRefresher
import com.lc33.tokenvault.engine.BackupEngine
import com.lc33.tokenvault.engine.BalanceEngine
import com.lc33.tokenvault.engine.IdleLockSuspender
import com.lc33.tokenvault.engine.LogMaintenance
import com.lc33.tokenvault.engine.ProbeEngine
import com.lc33.tokenvault.engine.ProbeSession
import com.lc33.tokenvault.engine.RefreshRound
import com.lc33.tokenvault.engine.UpdateEngine
import com.lc33.tokenvault.engine.VaultRefreshRound
import com.lc33.tokenvault.engine.WebDavEngine
import com.lc33.tokenvault.engine.scopeCrashGuard
import com.lc33.tokenvault.net.HostGate
import com.lc33.tokenvault.net.HttpEngine
import com.lc33.tokenvault.net.WebDavClient
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
import com.lc33.tokenvault.ui.shell.KeyDetailViewModel
import com.lc33.tokenvault.ui.shell.KeyEditorViewModel
import com.lc33.tokenvault.ui.shell.LockViewModel
import com.lc33.tokenvault.ui.shell.LogViewModel
import com.lc33.tokenvault.ui.shell.LogEntryViewModel
import com.lc33.tokenvault.ui.shell.ManageViewModel
import com.lc33.tokenvault.ui.shell.MemberViewModel
import com.lc33.tokenvault.ui.shell.ProbeRunViewModel
import com.lc33.tokenvault.ui.shell.ProbeSettingsViewModel
import com.lc33.tokenvault.ui.shell.ProfileEditorViewModel
import com.lc33.tokenvault.ui.shell.ProfileListViewModel
import com.lc33.tokenvault.ui.shell.ProviderDetailViewModel
import com.lc33.tokenvault.ui.shell.ProviderEditorViewModel
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

    // 挂 [scopeCrashGuard]：活在这个作用域上的协程（TokenVaultApp 里的建库 seed 与三条进程级
    // 订阅）没有父协程接异常，默认处理器直接杀进程，而它们偏偏最容易崩在"库打不开"上。
    single(named(Qualifiers.APP_SCOPE)) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default + scopeCrashGuard)
    }

    single<RandomBytes> { SecureRandomBytes }

    // 类型必须显式写：`::nowMillis` 的推断类型是 KFunction0<Long>，Koin 按精确类型注册，
    // 仓库们用 get<() -> Long>(named(NOW)) 取时会 NoDefinitionFoundException——解锁后
    // 第一个 ViewModel 拉仓库就闪退（阶段4 迁移时丢过这个显式转型，设备上炸过一次）。
    // 原版 Android 代码里的 `System::currentTimeMillis as () -> Long` 就是同一个意思。
    single<() -> Long>(named(Qualifiers.NOW)) { ::nowMillis }

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
    single { get<VaultDatabase>().keySettingsDao() }
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

    // 「删除后可撤销」的共用写回器。它要按外键顺序、按原主键把快照写回，
    // 所以需要这几张表的 DAO 与事务边界；四个可撤销仓库共用同一份实例。
    single {
        UndoRestorer(
            providerDao = get(),
            apiKeyDao = get(),
            settingsDao = get(),
            modelDao = get(),
            accountDao = get(),
            groupDao = get(),
            profileDao = get(),
            transactions = get(),
        )
    }

    single<GroupRepository> { RoomGroupRepository(get(), get()) }
    single<ProviderRepository> { RoomProviderRepository(get(), get(named(Qualifiers.NOW)), get(), get()) }
    single<ApiKeyRepository> { RoomApiKeyRepository(get(), get(), get(), get(), get(named(Qualifiers.NOW)), get(), get()) }
    single<SettingsRepository> { RoomSettingsRepository(get(), get()) }
    single<WebDavSettingsRepository> { RoomWebDavSettingsRepository(get(), get(), get()) }
    single<ProviderAccountRepository> { RoomProviderAccountRepository(get(), get(), get(), get(named(Qualifiers.NOW)), get(), get()) }
    single<ModelRepository> { RoomModelRepository(get(), get(), get(named(Qualifiers.NOW)), get(), get()) }
    single<ClientProfileRepository> { RoomClientProfileRepository(get(), get()) }
    single<AuditLogRepository> { RoomAuditLogRepository(get(), get(), get(named(Qualifiers.NOW))) }
    single { LogMaintenance(settings = get(), audit = get(), probeRuns = get(), now = get(named(Qualifiers.NOW))) }
    single<ProbeRunRepository> { RoomProbeRunRepository(get()) }

    // ------------------------------------------------------------------ 网络与引擎

    single { HostGate(nowMillis = ::nowMillis) }
    // 应用内不提供代理（移动端难以可靠实现，走系统全局代理）：client 是稳定单例。
    single { com.lc33.tokenvault.net.buildClient() }
    single { HttpEngine(client = get(), hostGate = get(), audit = get()) }
    single { com.lc33.tokenvault.backup.BackupCodec(get()) }
    single<BackupStore> {
        RoomBackupStore(
            groupDao = get(), providerDao = get(), keyDao = get(), accountDao = get(),
            profileDao = get(), settingsDao = get(), modelDao = get(), probeRunDao = get(), appSettingDao = get(),
            cipher = get(), transactions = get(), bootStore = get(), now = get(named(Qualifiers.NOW)),
        )
    }
    single {
        BackupEngine(
            store = get(), codec = get(), random = get(), audit = get(),
            autoLocker = get(), now = get(named(Qualifiers.NOW)),
        )
    }
    single { BalanceEngine(get(), get(), get(), get(), get(), get(), get(named(Qualifiers.NOW)), get(named(Qualifiers.PLACEHOLDERS))) }
    single {
        ProbeEngine(
            providers = get(), keys = get(), models = get(), clientProfiles = get(), runRepository = get(),
            session = get(), engine = get(), audit = get(), settings = get(), autoLocker = get(),
            redactor = get(), knownSecrets = get(), now = get(named(Qualifiers.NOW)),
            placeholders = get(named(Qualifiers.PLACEHOLDERS)),
        )
    }
    // 自动刷新（§13.4 探测设置页）。"一轮刷什么"与"什么时候刷"分两个定义：前者是
    // ProbeEngine + BalanceEngine 的薄包装，后者是应用单例。RefreshRound 要显式写类型，
    // Koin 按精确类型解析（同上面那两个接口包装的理由）。
    single<RefreshRound> { VaultRefreshRound(probeEngine = get(), balanceEngine = get()) }
    single {
        AutoRefresher(
            settings = get(),
            session = get(),
            round = get(),
            scope = get(named(Qualifiers.APP_SCOPE)),
        )
    }
    single { WebDavClient(get(), audit = get()) }
    single {
        WebDavEngine(
            settings = get(), backup = get(), client = get(), audit = get(),
            now = get(named(Qualifiers.NOW)),
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
    // 六个设置类 ViewModel 共用的"写不进去"失败出口：必须单例，事件才收得到。
    single { com.lc33.tokenvault.ui.shell.SettingsFailures() }
    viewModelOf(::AppearanceViewModel)
    viewModelOf(::BalanceThresholdsViewModel)
    viewModelOf(::ClientKeywordsViewModel)
    viewModelOf(::DashboardViewModel)
    viewModelOf(::DataViewModel)
    viewModelOf(::ImportViewModel)
    viewModelOf(::KeyDetailViewModel)
    viewModelOf(::KeyEditorViewModel)
    viewModelOf(::LockViewModel)
    viewModelOf(::LogViewModel)
    viewModelOf(::LogEntryViewModel)
    viewModelOf(::ManageViewModel)
    viewModelOf(::MemberViewModel)
    viewModelOf(::ProbeRunViewModel)
    viewModelOf(::ProbeSettingsViewModel)
    viewModelOf(::ProfileEditorViewModel)
    viewModelOf(::ProfileListViewModel)
    viewModelOf(::ProviderDetailViewModel)
    viewModelOf(::ProviderEditorViewModel)
    viewModelOf(::SecurityViewModel)
    viewModelOf(::SyncViewModel)
    viewModelOf(::UpdateViewModel)
}
