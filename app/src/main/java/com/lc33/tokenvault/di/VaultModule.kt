package com.lc33.tokenvault.di

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lc33.tokenvault.BuildConfig
import com.lc33.tokenvault.crypto.RandomBytes
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
import com.lc33.tokenvault.domain.repo.SettingsRepository
import com.lc33.tokenvault.engine.ProbeEngine
import com.lc33.tokenvault.net.HostGate
import com.lc33.tokenvault.net.OkHttpEngine
import com.lc33.tokenvault.platform.AndroidSecureClipboard
import com.lc33.tokenvault.platform.AutoLocker
import com.lc33.tokenvault.platform.BootStore
import com.lc33.tokenvault.platform.FileBootStore
import com.lc33.tokenvault.platform.SecureClipboard
import com.lc33.tokenvault.platform.VaultSession
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 应用级协程作用域。剪贴板自动清除、后台同步这些"活得比某个页面长"的任务用它。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

/**
 * 墙上时间。
 *
 * 做成可注入的 lambda 而不是让仓库直接调 `System.currentTimeMillis()`：**当前时间也算
 * 平台能力**（红线 20）。直接调的代价很具体——`createdAt` / `updatedAt` 的测试会变成
 * 时间敏感的，只能断言"大概是现在"，于是"保存时忘了更新 updatedAt"这种 bug 测不出来。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NowEpochMs

/**
 * 客户端伪装预设里的占位符值（`{app_version}` / `{android_release}` / `{arch}`）。
 *
 * 这些是平台能力（红线 20）：`app_version` 来自 `BuildConfig`、其余来自 `Build`，探测引擎
 * 是纯逻辑不该自己读。`{uuid}` / `{random_hex:N}` 由 `HeaderAssembler` 自己现算，不在这里。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppPlaceholders

@Module
@InstallIn(SingletonComponent::class)
object VaultModule {

    @Provides
    @Singleton
    @AppScope
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideRandom(): RandomBytes = SecureRandomBytes

    @Provides
    @Singleton
    @NowEpochMs
    fun provideNow(): () -> Long = System::currentTimeMillis

    @Provides
    @Singleton
    @AppPlaceholders
    fun provideAppPlaceholders(): Map<String, String> = mapOf(
        "app_version" to BuildConfig.VERSION_NAME,
        "android_release" to Build.VERSION.RELEASE,
        "arch" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "generic"),
    )

    /** 字段级加解密。`SecretBox` 自己无状态，随机源注进去（测试里换成确定源）。 */
    @Provides
    @Singleton
    fun provideSecretBox(random: RandomBytes): SecretBox = SecretBox(random)

    /**
     * 会话级已知明文清单（红线 32 第一道）。
     *
     * 做成独立单例，被 [com.lc33.tokenvault.crypto.Redactor]（消费）、
     * [com.lc33.tokenvault.platform.VaultSession]（锁定时清空）与
     * [com.lc33.tokenvault.ui.shell.ProviderDetailViewModel]（展开明文时登记）三方共享。
     */
    @Provides
    @Singleton
    fun provideKnownSecrets(): com.lc33.tokenvault.crypto.KnownSecrets =
        com.lc33.tokenvault.crypto.KnownSecrets()

    /** 落日志前的脱敏（红线 32）。已知明文第一道读 [KnownSecrets]，正则兜底。 */
    @Provides
    @Singleton
    fun provideRedactor(knownSecrets: com.lc33.tokenvault.crypto.KnownSecrets): com.lc33.tokenvault.crypto.Redactor =
        com.lc33.tokenvault.crypto.Redactor(knownSecrets = knownSecrets::snapshot)

    /**
     * boot 文件放在 `filesDir` 而不是 `SharedPreferences`：
     * 原子写要自己控制（临时文件 → fsync → rename，红线 26），而 SharedPreferences 的
     * 落盘时机不受我们控制，也没有"解析失败"这个可观察的状态。
     */
    @Provides
    @Singleton
    fun provideBootStore(@ApplicationContext context: Context): BootStore =
        FileBootStore(File(context.filesDir, FileBootStore.FILE_NAME))

    @Provides
    @Singleton
    fun provideVaultSession(
        bootStore: BootStore,
        random: RandomBytes,
        knownSecrets: com.lc33.tokenvault.crypto.KnownSecrets,
    ): VaultSession = VaultSession(
        bootStore = bootStore,
        // 当前时间是平台能力，必须注入（红线 20）——纯 Kotlin 层与会话逻辑都不直接读它
        nowEpochMs = System::currentTimeMillis,
        random = random,
        knownSecrets = knownSecrets,
    )

    /**
     * 自动锁定（§7.4）。用 `elapsedRealtime` 而不是墙上时间：改系统时间不该影响
     * "离开了多久"。观察者在 `TokenVaultApp` 里挂到 `ProcessLifecycleOwner` 上。
     */
    @Provides
    @Singleton
    fun provideAutoLocker(
        session: VaultSession,
        @AppScope scope: CoroutineScope,
        probeEngine: javax.inject.Provider<ProbeEngine>,
    ): AutoLocker = AutoLocker(
        session = session,
        scope = scope,
        elapsedRealtimeMs = SystemClock::elapsedRealtime,
        // 惰性取引擎而不是构造参数直接注入：AutoLocker 与 ProbeEngine 互相引用（引擎要
        // pause/resume 空闲锁定，锁定时要停引擎），直接注入会成 Hilt 构造环。onLock 只在
        // 运行时被调，所以这里延后到回调里再 get() 是安全的。
        onLock = { probeEngine.get().onLock() },
    )

    @Provides
    @Singleton
    fun provideClipboard(
        @ApplicationContext context: Context,
        @AppScope scope: CoroutineScope,
        settings: SettingsRepository,
    ): SecureClipboard = AndroidSecureClipboard(context, scope, settings)

    /**
     * 数据库。**应用级单例、启动即建**（§6.1 推论 1）：锁定只清 DEK、不关库，
     * 于是只碰公开数据的后台任务在锁定态也能跑。
     *
     * **禁用 `fallbackToDestructiveMigration`**（§6.3）：那个开关的含义是"schema 不匹配就
     * 把用户数据删了重建"，而这个应用里"数据"是用户的全部密钥。宁可开库失败、让用户去恢复
     * 备份，也不能悄悄删。
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VaultDatabase =
        Room.databaseBuilder(context, VaultDatabase::class.java, VaultDatabase.FILE_NAME)
            .addCallback(
                object : androidx.room.RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // 外键约束必须显式开（SQLite 默认 OFF）。不开的话，实体上写的
                        // ForeignKey.CASCADE（删供应商连带删 Key/账号/模型）与 SET_NULL
                        // （删分组置空 provider.groupId）全是摆设——删掉父行后子表里会留下
                        // 孤儿行，而编译与单测都发现不了。
                        db.execSQL("PRAGMA foreign_keys = ON")
                        // Room 的 @Index 表达不了部分唯一索引，所以 idx_keys_default 手写。
                        // 放在 onOpen 且 IF NOT EXISTS：万一某个版本漏了它，升级上来会自动补。
                        VaultDatabase.applyHandWrittenSchema(db)
                    }
                },
            )
            .build()

    @Provides fun provideGroupDao(db: VaultDatabase): GroupDao = db.groupDao()

    @Provides fun provideProviderDao(db: VaultDatabase): ProviderDao = db.providerDao()

    @Provides fun provideApiKeyDao(db: VaultDatabase): ApiKeyDao = db.apiKeyDao()

    @Provides
    fun provideProviderAccountDao(db: VaultDatabase): ProviderAccountDao = db.providerAccountDao()

    @Provides
    fun provideClientProfileDao(db: VaultDatabase): ClientProfileDao = db.clientProfileDao()

    @Provides fun provideModelDao(db: VaultDatabase): ModelDao = db.modelDao()

    @Provides
    fun provideModelCatalogDao(db: VaultDatabase): ModelCatalogDao = db.modelCatalogDao()

    @Provides fun provideProbeRunDao(db: VaultDatabase): ProbeRunDao = db.probeRunDao()

    @Provides fun provideAuditLogDao(db: VaultDatabase): AuditLogDao = db.auditLogDao()

    @Provides fun provideAppSettingDao(db: VaultDatabase): AppSettingDao = db.appSettingDao()

    // ------------------------------------------------------------------ 网络与探测

    /**
     * host 级最小间隔门闸（§8.1 末尾、红线 29）。**应用级单例**：间隔状态要跨轮、跨页面
     * 记住——撞过一次 429 的 host 在下一轮也不该立刻回到 800ms。
     */
    @Provides
    @Singleton
    fun provideHostGate(): HostGate = HostGate()

    /**
     * 手动 HTTP 代理的运行时提供者（§7.5）。订阅设置、缓存解析后的 [java.net.Proxy]，
     * [OkHttpEngine] 每次请求时读一次，改了就立刻生效。
     */
    @Provides
    @Singleton
    fun provideProxyProvider(settings: com.lc33.tokenvault.domain.repo.SettingsRepository): com.lc33.tokenvault.net.ProxyProvider =
        com.lc33.tokenvault.net.ProxyProvider(settings)

    /**
     * 探测引擎用的单个 OkHttpClient（§8.1）。超时与并发上限都在 [OkHttpEngine.buildDefaultClient]
     * 里，这一层只负责单例化。手动代理由 [ProxyProvider] 在每次请求时动态套用。
     */
    @Provides
    @Singleton
    fun provideOkHttpEngine(hostGate: HostGate, proxy: com.lc33.tokenvault.net.ProxyProvider): OkHttpEngine =
        OkHttpEngine(OkHttpEngine.buildDefaultClient(), hostGate, proxy::current)

    /**
     * 更新检查引擎（§13.4）。复用同一个 [OkHttpEngine]（UA 兜底 / 手动代理 / 超时都
     * 一致），但语义独立：单次匿名 GET，不碰探测的 host 门闸与鉴权。
     * `currentVersionName` 来自 [BuildConfig]，`repoUrl` 是公开仓库的 Releases 端点。
     */
    @Provides
    @Singleton
    fun provideUpdateEngine(engine: OkHttpEngine): com.lc33.tokenvault.engine.UpdateEngine =
        com.lc33.tokenvault.engine.UpdateEngine(
            engine = engine,
            currentVersionName = BuildConfig.VERSION_NAME,
            repoUrl = com.lc33.tokenvault.engine.UpdateEngine.RELEASES_URL,
        )

    /**
     * 探测引擎宿主（§8.5）。**`@Singleton` 不是 ViewModel**，所以绑定在这里而不是让
     * 某个页面去 `hiltViewModel`——它要能跨页面存活。
     */
    @Provides
    @Singleton
    fun provideBackupCodec(random: RandomBytes): com.lc33.tokenvault.backup.BackupCodec =
        com.lc33.tokenvault.backup.BackupCodec(random)

    @Provides
    @Singleton
    fun provideProbeEngine(
        providers: com.lc33.tokenvault.domain.repo.ProviderRepository,
        keys: com.lc33.tokenvault.domain.repo.ApiKeyRepository,
        clientProfiles: com.lc33.tokenvault.domain.repo.ClientProfileRepository,
        keyDao: ApiKeyDao,
        runDao: ProbeRunDao,
        session: VaultSession,
        engine: OkHttpEngine,
        audit: com.lc33.tokenvault.domain.repo.AuditLogRepository,
        settings: com.lc33.tokenvault.domain.repo.SettingsRepository,
        autoLocker: AutoLocker,
        redactor: com.lc33.tokenvault.crypto.Redactor,
        knownSecrets: com.lc33.tokenvault.crypto.KnownSecrets,
        @NowEpochMs now: () -> Long,
        @AppPlaceholders placeholders: Map<String, String>,
    ): ProbeEngine = ProbeEngine(
        providers = providers,
        keys = keys,
        clientProfiles = clientProfiles,
        keyDao = keyDao,
        runDao = runDao,
        session = session,
        engine = engine,
        audit = audit,
        settings = settings,
        autoLocker = autoLocker,
        redactor = redactor,
        knownSecrets = knownSecrets,
        now = now,
        placeholders = placeholders,
    )
}
