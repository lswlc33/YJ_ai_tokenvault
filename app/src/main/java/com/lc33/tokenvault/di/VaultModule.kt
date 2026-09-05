package com.lc33.tokenvault.di

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lc33.tokenvault.crypto.RandomBytes
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
import com.lc33.tokenvault.domain.BiometricAvailability
import com.lc33.tokenvault.platform.BiometricCapability
import com.lc33.tokenvault.platform.BiometricKeyStore
import com.lc33.tokenvault.platform.BiometricUnlocker
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
    fun provideBiometricCapability(@ApplicationContext context: Context): BiometricCapability =
        BiometricCapability(context)

    @Provides
    @Singleton
    fun provideBiometricKeyStore(): BiometricKeyStore = BiometricKeyStore()

    @Provides
    @Singleton
    fun provideVaultSession(
        bootStore: BootStore,
        random: RandomBytes,
        capability: BiometricCapability,
    ): VaultSession = VaultSession(
        bootStore = bootStore,
        // 当前时间是平台能力，必须注入（红线 20）——纯 Kotlin 层与会话逻辑都不直接读它
        nowEpochMs = System::currentTimeMillis,
        random = random,
        biometricAvailability = capability::current,
    )

    @Provides
    @Singleton
    fun provideBiometricUnlocker(
        keyStore: BiometricKeyStore,
        bootStore: BootStore,
        session: VaultSession,
    ): BiometricUnlocker = BiometricUnlocker(keyStore, bootStore, session)

    @Provides
    @Singleton
    fun provideClipboard(
        @ApplicationContext context: Context,
        @AppScope scope: CoroutineScope,
    ): SecureClipboard = SecureClipboard(context, scope)

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
}

/** 让 `BiometricAvailability` 也能被直接注入（设置页要显示当前档位）。 */
@Module
@InstallIn(SingletonComponent::class)
object BiometricAvailabilityModule {

    @Provides
    fun provideCurrentAvailability(capability: BiometricCapability): BiometricAvailability =
        capability.current()
}
