package com.lc33.tokenvault.di

import com.lc33.tokenvault.backup.BackupStore
import com.lc33.tokenvault.crypto.Redactor
import com.lc33.tokenvault.data.VaultDatabase
import com.lc33.tokenvault.data.seed.ProfileSeeder
import com.lc33.tokenvault.domain.AutoLockPolicy
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
 * 逐个定义解析并打印结果：谁炸了、因为什么，直接进测试输出（Release 二进制
 * 没有行号，栈信息只有靠这里的 println 补）。
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

        val checks: List<Pair<String, () -> Any?>> = listOf(
            "now" to { koin.get<() -> Long>(named(Qualifiers.NOW)) },
            "placeholders" to { koin.get<Map<String, String>>(named(Qualifiers.PLACEHOLDERS)) },
            "appScope" to { koin.get<CoroutineScope>(named(Qualifiers.APP_SCOPE)) },
            "VaultDatabase" to { koin.get<VaultDatabase>() },
            "BootStore" to { koin.get<BootStore>() },
            "SecureClipboard" to { koin.get<SecureClipboard>() },
            "VaultSession" to { koin.get<VaultSession>() },
            "ProbeSession" to { koin.get<ProbeSession>() },
            "AutoLocker" to { koin.get<AutoLocker>() },
            "TransactionRunner" to { koin.get<TransactionRunner>() },
            "ProfileSeeder" to { koin.get<ProfileSeeder>() },
            "ImportWriter" to { koin.get<ImportWriter>() },
            "GroupRepository" to { koin.get<GroupRepository>() },
            "ProviderRepository" to { koin.get<ProviderRepository>() },
            "ApiKeyRepository" to { koin.get<ApiKeyRepository>() },
            "SettingsRepository" to { koin.get<SettingsRepository>() },
            "ProviderAccountRepository" to { koin.get<ProviderAccountRepository>() },
            "ModelRepository" to { koin.get<ModelRepository>() },
            "ClientProfileRepository" to { koin.get<ClientProfileRepository>() },
            "AuditLogRepository" to { koin.get<AuditLogRepository>() },
            "ProbeRunRepository" to { koin.get<ProbeRunRepository>() },
            "HttpEngine" to { koin.get<HttpEngine>() },
            "BackupStore" to { koin.get<BackupStore>() },
            "BackupEngine" to { koin.get<BackupEngine>() },
            "BalanceEngine" to { koin.get<BalanceEngine>() },
            "ProbeEngine" to { koin.get<ProbeEngine>() },
            "UpdateEngine" to { koin.get<UpdateEngine>() },
            "Redactor" to { koin.get<Redactor>() },
        )

        for ((name, resolve) in checks) {
            try {
                resolve()
                println("✓ $name")
            } catch (t: Throwable) {
                println("✗ $name")
                println(t.stackTraceToString())
                var c = t.cause
                while (c != null) {
                    println("由它引起：")
                    println(c.stackTraceToString())
                    c = c.cause
                }
                throw t
            }
        }

        // ---- 启动路径的三个真实动作（initIosApp 的后台协程做的事） ----

        // 1. boot 存储读改写走一轮（默认值落盘：deviceId 稳定化）
        koin.get<BootStore>().update { it }
        println("✓ boot 读改写")

        // 2. 真开数据库 + 种内置预设（ProfileSeeder.seed 是启动即跑的）
        runBlocking { withTimeout(20.seconds) { koin.get<ProfileSeeder>().seed() } }
        println("✓ 种内置预设（真开库）")

        // 3. 读一条设置（observeAutoLockTimeout 的第一个值；Room Flow 的首次查询）
        val timeout = runBlocking {
            withTimeout(20.seconds) { koin.get<SettingsRepository>().observeAutoLockTimeout().first() }
        }
        assertEquals(AutoLockPolicy.DEFAULT, timeout, "自动锁定时限应落在默认值上")
        println("✓ Room Flow 首查")
    }
}
