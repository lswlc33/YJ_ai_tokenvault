package com.lc33.tokenvault.di

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lc33.tokenvault.BuildConfig
import com.lc33.tokenvault.crypto.KnownSecrets
import com.lc33.tokenvault.crypto.RandomBytes
import com.lc33.tokenvault.crypto.Redactor
import com.lc33.tokenvault.crypto.SecretBox
import com.lc33.tokenvault.crypto.SecureRandomBytes
import com.lc33.tokenvault.data.VaultDatabase
import com.lc33.tokenvault.data.dao.ApiKeyDao
import com.lc33.tokenvault.data.dao.AppSettingDao
import com.lc33.tokenvault.data.dao.AuditLogDao
import com.lc33.tokenvault.data.dao.ClientProfileDao
import com.lc33.tokenvault.data.dao.GroupDao
import com.lc33.tokenvault.data.dao.ModelCatalogDao
import com.lc33.tokenvault.data.dao.ModelDao
import com.lc33.tokenvault.data.dao.ProbeRunDao
import com.lc33.tokenvault.data.dao.ProviderAccountDao
import com.lc33.tokenvault.data.dao.ProviderDao
import com.lc33.tokenvault.data.repo.FieldCipher
import com.lc33.tokenvault.data.repo.ImportWriter
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
import com.lc33.tokenvault.data.repo.TransactionRunner
import com.lc33.tokenvault.data.seed.ProfileSeeder
import com.lc33.tokenvault.backup.BackupStore
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import com.lc33.tokenvault.domain.repo.ClientProfileRepository
import com.lc33.tokenvault.domain.repo.GroupRepository
import com.lc33.tokenvault.domain.repo.ModelRepository
import com.lc33.tokenvault.domain.repo.ProviderAccountRepository
import com.lc33.tokenvault.domain.repo.ProbeRunRepository
import com.lc33.tokenvault.domain.repo.ProviderRepository
import com.lc33.tokenvault.domain.repo.SettingsRepository
import com.lc33.tokenvault.engine.BackupEngine
import com.lc33.tokenvault.engine.BalanceEngine
import com.lc33.tokenvault.engine.ProbeEngine
import com.lc33.tokenvault.engine.UpdateEngine
import com.lc33.tokenvault.net.HostGate
import com.lc33.tokenvault.net.HttpEngine
import com.lc33.tokenvault.net.ProxyProvider
import com.lc33.tokenvault.platform.AndroidSecureClipboard
import com.lc33.tokenvault.platform.AutoLocker
import com.lc33.tokenvault.platform.BootStore
import com.lc33.tokenvault.platform.FileBootStore
import com.lc33.tokenvault.platform.SecureClipboard
import com.lc33.tokenvault.platform.VaultSession
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
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Koin 模块（阶段2 迁移 Hilt→Koin）。
 *
 * 三个 named 限定符对应原来 Hilt 的三个 `@Qualifier`：
 * - `named("now")`        → 原 `@NowEpochMs` 的墙上时间 lambda（红线 20：注入而非直接读）。
 * - `named("placeholders")` → 原 `@AppPlaceholders` 的客户端占位符 Map（红线 20）。
 * - `named("appScope")`   → 原 `@AppScope` 的应用级协程作用域。
 *
 * 仓库用 `@Binds` 绑到 `domain/repo/` 接口，与原来一致：ViewModel 只依赖接口。
 */
object Qualifiers {
    const val NOW = "now"
    const val PLACEHOLDERS = "placeholders"
    const val APP_SCOPE = "appScope"
}

val appModule = module {

    // ------------------------------------------------------------------ 基础能力

    single(named(Qualifiers.APP_SCOPE)) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    single<RandomBytes> { SecureRandomBytes }

    single(named(Qualifiers.NOW)) {
        System::currentTimeMillis as () -> Long
    }

    single(named(Qualifiers.PLACEHOLDERS)) {
        mapOf(
            "app_version" to BuildConfig.VERSION_NAME,
            "android_release" to Build.VERSION.RELEASE,
            "arch" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "generic"),
        )
    }

    single { SecretBox(get()) }
    single { KnownSecrets() }
    single { Redactor(knownSecrets = get<KnownSecrets>()::snapshot) }

    // ------------------------------------------------------------------ 平台

    single<BootStore> {
        FileBootStore(File(get<Context>().filesDir, FileBootStore.FILE_NAME))
    }

    single { VaultSession(bootStore = get(), nowEpochMs = System::currentTimeMillis, random = get(), knownSecrets = get()) }

    single {
        AutoLocker(
            session = get(),
            scope = get(named(Qualifiers.APP_SCOPE)),
            elapsedRealtimeMs = SystemClock::elapsedRealtime,
            onLock = { get<ProbeEngine>().onLock() },
        )
    }

    single<SecureClipboard> {
        AndroidSecureClipboard(get(), get(named(Qualifiers.APP_SCOPE)), get())
    }

    // ------------------------------------------------------------------ 数据库与 DAO

    single {
        Room.databaseBuilder(get<Context>(), VaultDatabase::class.java, VaultDatabase.FILE_NAME)
            .addCallback(
                object : androidx.room.RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA foreign_keys = ON")
                        VaultDatabase.applyHandWrittenSchema(db)
                    }
                },
            )
            .build()
    }

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

    single { HostGate(nowMillis = System::currentTimeMillis) }
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
            currentVersionName = BuildConfig.VERSION_NAME,
            repoUrl = UpdateEngine.RELEASES_URL,
        )
    }
}

// ViewModel 统一用 viewModelOf 注册（Koin 反射解析构造参数，SavedStateHandle 自动注入）。
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
