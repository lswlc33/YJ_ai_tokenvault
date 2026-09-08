package com.lc33.tokenvault.di

import com.lc33.tokenvault.backup.BackupStore
import com.lc33.tokenvault.crypto.Redactor
import com.lc33.tokenvault.data.VaultDatabase
import com.lc33.tokenvault.data.seed.ProfileSeeder
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
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named

/**
 * DI 图冒烟（JVM）：把 coreModule + platformModule 真正启动一遍，逐个解析。
 *
 * 为什么要有它：Koin 的定义按**精确类型**注册，类型推断差一点（比如 `::nowMillis`
 * 推成 `KFunction0<Long>` 而不是 `() -> Long`）在编译期、单测里都看不出来，
 * 只有运行时第一次 `get` 才炸——2026-09-08 设备上「输完 PIN 闪退」就是这么来的
 * （DashboardViewModel → ProviderRepository → get(named("now")) 找不到定义）。
 * 单测都是手工构造依赖、从不启动 Koin，所以防线只能建在这里。
 *
 * 只解析（构造）不查询：Room 的 build() 不开库，引擎构造函数无副作用，
 * 不会真在 ~/.tokenvault 落任何业务数据。
 */
class DiGraphSmokeTest {

    private lateinit var koin: Koin

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `整张 DI 图能解析出全部单例`() {
        koin = startKoin {
            modules(platformModule, coreModule)
        }.koin

        // 三个 named 限定符——历史上最容易踩的坑就在这
        assertNotNull(koin.get<() -> Long>(named(Qualifiers.NOW)))
        assertNotNull(koin.get<Map<String, String>>(named(Qualifiers.PLACEHOLDERS)))
        assertNotNull(koin.get<CoroutineScope>(named(Qualifiers.APP_SCOPE)))

        // 平台件
        assertNotNull(koin.get<VaultDatabase>())
        assertNotNull(koin.get<BootStore>())
        assertNotNull(koin.get<SecureClipboard>())

        // 会话与自动锁定
        assertNotNull(koin.get<VaultSession>())
        assertNotNull(koin.get<ProbeSession>())
        assertNotNull(koin.get<AutoLocker>())

        // 仓库与事务
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

        // 网络与引擎
        assertNotNull(koin.get<HttpEngine>())
        assertNotNull(koin.get<BackupStore>())
        assertNotNull(koin.get<BackupEngine>())
        assertNotNull(koin.get<BalanceEngine>())
        assertNotNull(koin.get<ProbeEngine>())
        assertNotNull(koin.get<UpdateEngine>())
        assertNotNull(koin.get<Redactor>())
    }
}
