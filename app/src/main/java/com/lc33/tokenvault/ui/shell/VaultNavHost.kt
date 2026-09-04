package com.lc33.tokenvault.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.lc33.tokenvault.R
import com.lc33.tokenvault.screens.common.PlaceholderScreen
import com.lc33.tokenvault.screens.dashboard.DashboardScreen
import com.lc33.tokenvault.screens.manage.ManageScreen
import com.lc33.tokenvault.screens.model.ManageTab
import com.lc33.tokenvault.screens.sample.SampleContent
import com.lc33.tokenvault.screens.settings.AboutScreen
import com.lc33.tokenvault.screens.settings.SettingsScreen

/**
 * 导航图。
 *
 * M0.8 阶段数据来自 [SampleContent]，而"当前分段"这类**纯 UI 状态**先由 Shell 记着；
 * M3 接真数据时这两样都换成 ViewModel + `SavedStateHandle`，页面签名不用动
 * （页面只接 UiState 与回调，本来就不知道数据从哪来）。
 */
@Composable
fun VaultNavHost(
    nav: NavHostController,
    modifier: Modifier = Modifier,
) {
    // 分段选择跨导航保留：从仪表盘点"密钥"进管理页，回来再进还应该停在密钥分段。
    var manageTab by remember { mutableStateOf(ManageTab.Providers) }

    fun openManage(tab: ManageTab) {
        manageTab = tab
        nav.navigate(ManageRoute) {
            popUpTo<DashboardRoute> { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    NavHost(
        navController = nav,
        startDestination = DashboardRoute,
        modifier = modifier,
    ) {
        composable<DashboardRoute> {
            DashboardScreen(
                state = SampleContent.dashboard(),
                onOpenProvider = { id -> nav.navigate(ProviderDetailRoute(id)) },
                onOpenManageTab = ::openManage,
                onOpenProbeRun = { nav.navigate(ProbeRunRoute) },
                onOpenSync = { nav.navigate(SyncRoute) },
                onStartProbe = {},
                onCancelProbe = {},
                onRefreshBalance = {},
            )
        }

        composable<ManageRoute> {
            ManageScreen(
                state = SampleContent.manage().copy(tab = manageTab),
                onSelectTab = { tab -> manageTab = tab },
                onOpenProvider = { id -> nav.navigate(ProviderDetailRoute(id)) },
                onNewProvider = { nav.navigate(ProviderEditorRoute()) },
                onImport = { nav.navigate(ImportRoute) },
            )
        }

        composable<SettingsRoute> {
            SettingsScreen(
                onOpenAppearance = { nav.navigate(AppearanceRoute) },
                onOpenSecurity = { nav.navigate(SecurityRoute) },
                onOpenProbeSettings = { nav.navigate(ProbeSettingsRoute) },
                onOpenProfiles = { nav.navigate(ProfileListRoute) },
                onOpenData = { nav.navigate(DataRoute) },
                onOpenSync = { nav.navigate(SyncRoute) },
                onOpenAbout = { nav.navigate(AboutRoute) },
                onOpenUpdate = { nav.navigate(UpdateRoute) },
            )
        }

        composable<AboutRoute> { AboutScreen(onBack = { nav.popBackStack() }) }

        // 以下都是 M0.8 立起来的空壳，内容各归各的里程碑（见 §16）
        val back: () -> Unit = { nav.popBackStack() }
        composable<ProviderDetailRoute> { entry ->
            // 取一下参数，确认类型安全路由真的把 id 传进来了
            entry.toRoute<ProviderDetailRoute>()
            PlaceholderScreen(titleRes = R.string.provider_detail_title, onBack = back)
        }
        composable<ProviderEditorRoute> { PlaceholderScreen(R.string.provider_detail_title, back) }
        composable<ImportRoute> { PlaceholderScreen(R.string.dashboard_empty_import, back) }
        composable<GroupsRoute> { PlaceholderScreen(R.string.settings_data, back) }
        composable<ProbeRunRoute> { PlaceholderScreen(R.string.probe_run_title, back) }
        composable<BalanceBreakdownRoute> { PlaceholderScreen(R.string.dashboard_balance_title, back) }
        composable<AppearanceRoute> { PlaceholderScreen(R.string.appearance_title, back) }
        composable<SecurityRoute> { PlaceholderScreen(R.string.security_title, back) }
        composable<ProbeSettingsRoute> { PlaceholderScreen(R.string.probe_settings_title, back) }
        composable<ProfileListRoute> { PlaceholderScreen(R.string.settings_profiles, back) }
        composable<DataRoute> { PlaceholderScreen(R.string.data_title, back) }
        composable<SyncRoute> { PlaceholderScreen(R.string.sync_title, back) }
        composable<UpdateRoute> { PlaceholderScreen(R.string.update_title, back) }
    }
}
