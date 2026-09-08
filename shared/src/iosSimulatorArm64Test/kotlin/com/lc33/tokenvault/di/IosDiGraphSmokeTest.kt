package com.lc33.tokenvault.di

import com.lc33.tokenvault.backup.BackupStore
import com.lc33.tokenvault.crypto.Redactor
import com.lc33.tokenvault.data.VaultDatabase
import com.lc33.tokenvault.data.seed.ProfileSeeder
import com.lc33.tokenvault.domain.AutoLockPolicy
import com.lc33.tokenvault.domain.AutoLockTimeout
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
import com.lc33.tokenvault.engine.ProbeEngine
import com.lc33.tokenvault.engine.ProbeSession
import com.lc33.tokenvault.engine.UpdateEngine
import com.lc33.tokenvault.net.HttpEngine
import com.lc33.tokenvault.platform.AutoLocker
import com.lc33.tokenvault.platform.BootStore
import com.lc33.tokenvault.platform.SecureClipboard
import com.lc33.tokenvault.platform.VaultSession
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named

/**
 * iOS 真机「打开即闪退」的镜像测试（2026-09-08）：initIosApp 启动 Koin 后，
 * 后台协程在 ~0.6 秒内第一次开数据库（设置订阅 + 种内置预设）。
 * 这条路径在编译期、JVM 单测里都验证不到——iOS 的 Room/BundledSQLiteDriver、
 * IosBootStore、剪贴板，只有真跑在模拟器上才算数。
 *
 * 与 jvmTest 的 DiGraphSmokeTest 相同的图 + 三个真实动作：
 * 真开库、真种预设、真读一条设置。
 */
class IosDiGraphSmokeTest {

    private lateinit var koin: Koin

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `启动路径整张 DI 图 + 真开数据库能走通`() {
        koin = startKoin {
            modules(platformModule, coreModule)
        }.koin

        // named 限定符（Android 端闪退过一次的那类坑）
        assertNotNull(koin.get<() -> Long>(named(Qualifiers.NOW)))
        assertNotNull(koin.get<Map<String, String>>(named(Qualifiers.PLACEHOLDERS)))
        assertNotNull(koin.get<CoroutineScope>(named(Qualifiers.APP_SCOPE)))

        // 平台件与会话
        assertNotNull(koin.get<VaultDatabase>())
        assertNotNull(koin.get<BootStore>())
        assertNotNull(koin.get<SecureClipboard>())
        assertNotNull(koin.get<VaultSession>())
        assertNotNull(koin.get<ProbeSession>())
        assertNotNull(koin.get<AutoLocker>())

        // 仓库与引擎
        assertNotNull(koin.get<TransactionRunner>())
        assertNotNull(koin.get<ProfileSeeder>())
        assertNotNull(koin.get<ImportWriter>())
        assertNotNull(koin.get<GroupRepository>())
        assertNotNull(koin.get<ProviderRepository>())
        assertNotNull(koin.get<ApiKeyRepository>())
        assertNotNull(koin.get<SettingsRepository>())
        assertNotNull(koin.get<ProviderAccountRepository>())
        assertNotNull(koin.get<ModelRepository>())
        assertNotNull(koin.get<ClientProfileRepository>())
        assertNotNull(koin.get<AuditLogRepository>())
        assertNotNull(koin.get<ProbeRunRepository>())
        assertNotNull(koin.get<HttpEngine>())
        assertNotNull(koin.get<BackupStore>())
        assertNotNull(koin.get<BackupEngine>())
        assertNotNull(koin.get<BalanceEngine>())
        assertNotNull(koin.get<ProbeEngine>())
        assertNotNull(koin.get<UpdateEngine>())
        assertNotNull(koin.get<Redactor>())

        // ---- 启动路径的三个真实动作（initIosApp 的后台协程做的事） ----

        // 1. boot 存储读改写走一轮（默认值落盘：deviceId 稳定化）
        koin.get<BootStore>().update { it }

        // 2. 真开数据库 + 种内置预设（ProfileSeeder.seed 是启动即跑的）
        runBlocking { withTimeout(20.seconds) { koin.get<ProfileSeeder>().seed() } }

        // 3. 读一条设置（observeAutoLockTimeout 的第一个值；Room Flow 的首次查询）
        val timeout = runBlocking {
            withTimeout(20.seconds) { koin.get<SettingsRepository>().observeAutoLockTimeout().first() }
        }
        assertEquals(AutoLockPolicy.DEFAULT, timeout, "自动锁定时限应落在默认值上")
    }
}
