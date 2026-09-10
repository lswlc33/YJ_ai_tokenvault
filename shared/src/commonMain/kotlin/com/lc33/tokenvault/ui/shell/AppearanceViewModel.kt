package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.domain.model.PredictiveBackExitDirection
import com.lc33.tokenvault.domain.model.PredictiveBackStyle
import com.lc33.tokenvault.domain.repo.SettingsRepository
import com.lc33.tokenvault.platform.BootState
import com.lc33.tokenvault.platform.BootStore
import com.lc33.tokenvault.ui.theme.AppColorSchemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 配色模式（红线 31、§6.1 推论 5）。
 *
 * **权威存储是 boot 而不是 `app_settings`**：锁屏页也要按用户选的配色画，而那一刻数据库里的
 * 设置还读不到（库开着，但 `app_settings` 里的值要解密的那些读不了，而且锁屏本身要在
 * 任何数据库访问之前就画对）。所以它和 `localeTag` / `onboarded` 一样存在 boot 里。
 *
 * **状态从 [BootStore.revision] 派生，不自己记一份。** 这一点是必须的：`AppRoot` 与
 * 「外观」页各自 `hiltViewModel()`，而后者的宿主是导航栈里的一个 `NavBackStackEntry`，
 * 于是**两处拿到的是两个实例**。如果各记一份内存状态，在外观页里改配色就只有那一页会变，
 * 整棵树的主题不动——而这正是这个类唯一要做的事。派生自单例 store 之后，实例有几个都一样。
 */
class AppearanceViewModel constructor(
    private val bootStore: BootStore,
    private val settings: SettingsRepository,
) : ViewModel() {

    val colorScheme: StateFlow<AppColorSchemeMode> = bootStore.revision
        .map { readColorScheme() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, readColorScheme())

    /** 底栏模糊。权威 `app_settings.blurNavBar`（红线 31），默认开。 */
    val blurNavBar: StateFlow<Boolean> = settings.observeBlurNavBar()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** 预见式返回样式。默认 MIUIX：与当前应用整体动效一致，也是迁移前的行为。 */
    val predictiveBackStyle: StateFlow<PredictiveBackStyle> = settings.observePredictiveBackStyle()
        .stateIn(viewModelScope, SharingStarted.Eagerly, PredictiveBackStyle.Miuix)

    /** 只对 Scale 生效；其他样式没有独立退出方向。 */
    val predictiveBackExitDirection: StateFlow<PredictiveBackExitDirection> =
        settings.observePredictiveBackExitDirection()
            .stateIn(viewModelScope, SharingStarted.Eagerly, PredictiveBackExitDirection.AlwaysRight)

    fun onBlurNavBarChange(enabled: Boolean) {
        viewModelScope.launch { settings.setBlurNavBar(enabled) }
    }

    fun onPredictiveBackStyleChange(style: PredictiveBackStyle) {
        viewModelScope.launch { settings.setPredictiveBackStyle(style) }
    }

    fun onPredictiveBackExitDirectionChange(direction: PredictiveBackExitDirection) {
        viewModelScope.launch { settings.setPredictiveBackExitDirection(direction) }
    }

    private fun readColorScheme(): AppColorSchemeMode {
        // 读不出 boot（全新安装 Missing、或者已经坏了 Corrupt）时跟随系统。
        // 这里刻意不因为配色读不到就把用户拦在 BootCorrupt 页上——那一档由锁闸自己判，
        // 而在它判出来之前这棵树总得有个配色。
        val record = (bootStore.read() as? BootState.Ok)?.record ?: return AppColorSchemeMode.System
        return AppColorSchemeMode.fromStorage(record.themeMode)
    }

    /**
     * 改配色。同步写 boot（一次小文件的原子写，几毫秒），不放到后台：
     * 放后台的话这一帧还画着旧配色，而 revision 推上来时用户已经看到过一次闪烁。
     */
    fun onColorSchemeChange(mode: AppColorSchemeMode) {
        // Corrupt 状态下 update 会抛（FileBootStore 刻意拒绝在损坏文件上做增量修改）。
        // 那一刻用户本该在 BootCorrupt 页上，进不到外观页；真进来了也不该为了改配色
        // 把损坏文件覆盖掉，所以这里只是不写。
        if (bootStore.read() !is BootState.Ok) return
        bootStore.update { it.copy(themeMode = mode.name) }
    }
}
