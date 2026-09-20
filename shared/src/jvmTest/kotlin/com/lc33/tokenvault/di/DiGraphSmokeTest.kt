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
import com.lc33.tokenvault.engine.AutoRefresher
import com.lc33.tokenvault.engine.BackupEngine
import com.lc33.tokenvault.engine.BalanceEngine
import com.lc33.tokenvault.engine.HttpConcurrencyApplier
import com.lc33.tokenvault.engine.ProbeEngine
import com.lc33.tokenvault.engine.ProbeSession
import com.lc33.tokenvault.engine.RefreshRound
import com.lc33.tokenvault.engine.UpdateEngine
import com.lc33.tokenvault.net.ConcurrencyGate
import com.lc33.tokenvault.net.HttpEngine
import com.lc33.tokenvault.platform.AutoLocker
import com.lc33.tokenvault.platform.BootStore
import com.lc33.tokenvault.platform.SecureClipboard
import com.lc33.tokenvault.platform.VaultSession
import com.lc33.tokenvault.ui.shell.AppearanceViewModel
import com.lc33.tokenvault.ui.shell.BalanceThresholdsViewModel
import com.lc33.tokenvault.ui.shell.ClientKeywordsViewModel
import com.lc33.tokenvault.ui.shell.DashboardViewModel
import com.lc33.tokenvault.ui.shell.DataViewModel
import com.lc33.tokenvault.ui.shell.ImportViewModel
import com.lc33.tokenvault.ui.shell.KeyDetailViewModel
import com.lc33.tokenvault.ui.shell.KeyEditorViewModel
import com.lc33.tokenvault.ui.shell.KeyModelsViewModel
import com.lc33.tokenvault.ui.shell.LockViewModel
import com.lc33.tokenvault.ui.shell.LogEntryViewModel
import com.lc33.tokenvault.ui.shell.LogViewModel
import com.lc33.tokenvault.ui.shell.ManageViewModel
import com.lc33.tokenvault.ui.shell.MemberViewModel
import com.lc33.tokenvault.ui.shell.ProfileEditorViewModel
import com.lc33.tokenvault.ui.shell.ProfileListViewModel
import com.lc33.tokenvault.ui.shell.ProbeRunViewModel
import com.lc33.tokenvault.ui.shell.ProbeSettingsViewModel
import com.lc33.tokenvault.ui.shell.ProviderDetailViewModel
import com.lc33.tokenvault.ui.shell.ProviderEditorViewModel
import com.lc33.tokenvault.ui.shell.SecurityViewModel
import com.lc33.tokenvault.ui.shell.SyncViewModel
import com.lc33.tokenvault.ui.shell.UpdateViewModel
import com.lc33.tokenvault.ui.shell.UsageReportViewModel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
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
        Dispatchers.resetMain()
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
        // 并发闸与它的订阅者：闸是 HttpEngine 的第 4 个依赖（少注册会让 HttpEngine 解析失败），
        // applier 只在启动入口里 get()，测试从不碰它——不在这条上点一次，注册写歪了要等到
        // 真机上"设置改了但并发数永远不变"才发现。
        assertNotNull(koin.get<ConcurrencyGate>())
        assertNotNull(koin.get<HttpConcurrencyApplier>())
        assertNotNull(koin.get<BackupStore>())
        assertNotNull(koin.get<BackupEngine>())
        assertNotNull(koin.get<BalanceEngine>())
        assertNotNull(koin.get<ProbeEngine>())
        // 自动刷新：`RefreshRound` 是按接口注册的，Koin 只按精确类型解析，
        // 少了那一行显式类型就会在这里而不是在设备上炸。
        assertNotNull(koin.get<RefreshRound>())
        assertNotNull(koin.get<AutoRefresher>())
        assertNotNull(koin.get<UpdateEngine>())
        assertNotNull(koin.get<Redactor>())
    }

    /**
     * 全部 ViewModel 能被 Koin 解析。
     *
     * 单独一条测试、单独一次 startKoin：`viewModelModule` 必须一起装上图，
     * 否则 23 个 `viewModelOf` 里任何一个构造参数对不上（少注册一个仓库、
     * 类型写歪、新增依赖忘了进 coreModule）都要等到真机上点开那一页才炸。
     * `:app:testDebugUnitTest` 抓不到这件事——那些测试全的手工 new ViewModel，
     * 从不走 Koin，而这一条是唯一的机器防线。
     *
     * **Main 调度器必须先立起来**：`ViewModel.viewModelScope` 用的是
     * `Dispatchers.Main.immediate`，JVM 上没有 Android 那个 Main，不 setMain 就是
     * "Module with the Main dispatcher had failed to initialize"，测不到任何真问题。
     *
     * 这里刻意用 [StandardTestDispatcher] 而且**从不推进它的调度器**：`stateIn(Eagerly)`
     * 与 `init { launch { … } }` 因此只是排队、不执行，构造完就停。这样既验证了
     * 类型解析（本测试唯一的目的），又不会真去开 Room、在 ~/.tokenvault 落下业务数据。
     */
    @Test
    fun `全部 ViewModel 能被 Koin 解析`() {
        Dispatchers.setMain(StandardTestDispatcher())
        koin = startKoin {
            modules(platformModule, coreModule, viewModelModule)
        }.koin

        // 无路由参数的：整张图只靠 single 就能起来
        assertNotNull(koin.get<AppearanceViewModel>())
        assertNotNull(koin.get<BalanceThresholdsViewModel>())
        assertNotNull(koin.get<ClientKeywordsViewModel>())
        assertNotNull(koin.get<DashboardViewModel>())
        assertNotNull(koin.get<DataViewModel>())
        assertNotNull(koin.get<LockViewModel>())
        assertNotNull(koin.get<LogViewModel>())
        assertNotNull(koin.get<ManageViewModel>())
        assertNotNull(koin.get<MemberViewModel>())
        assertNotNull(koin.get<ProbeRunViewModel>())
        assertNotNull(koin.get<ProbeSettingsViewModel>())
        assertNotNull(koin.get<ProfileListViewModel>())
        assertNotNull(koin.get<SecurityViewModel>())
        assertNotNull(koin.get<SyncViewModel>())
        assertNotNull(koin.get<UpdateViewModel>())
        assertNotNull(koin.get<UsageReportViewModel>())

        // 带路由参数的：id 与"默认名模板"由调用方以 parametersOf 传入，
        // 这里的值只用于把构造走通（不查库，所以 1L 这种假 id 无副作用）。
        val labelTemplate: (Int) -> String = { n -> "label-$n" }
        assertNotNull(koin.get<ImportViewModel> { parametersOf(1L) })
        assertNotNull(koin.get<KeyDetailViewModel> { parametersOf(1L, 2L) })
        assertNotNull(koin.get<KeyEditorViewModel> { parametersOf(1L, 2L, labelTemplate) })
        assertNotNull(koin.get<KeyModelsViewModel> { parametersOf(1L, 2L) })
        assertNotNull(koin.get<LogEntryViewModel> { parametersOf(1L) })
        assertNotNull(koin.get<ProfileEditorViewModel> { parametersOf(1L) })
        assertNotNull(koin.get<ProviderDetailViewModel> { parametersOf(1L) })
        assertNotNull(koin.get<ProviderEditorViewModel> { parametersOf(1L, labelTemplate) })
    }
}
