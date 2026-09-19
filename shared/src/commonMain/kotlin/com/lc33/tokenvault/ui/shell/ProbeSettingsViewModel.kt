package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.domain.AutoRefreshPolicy
import com.lc33.tokenvault.domain.DefaultProbeSettings
import com.lc33.tokenvault.domain.repo.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 探测设置页（§13.4）的状态源。
 *
 * 客户端嗅探开关的权威是 `app_settings.sniffClientProfile`；新建密钥的五个默认探测开关
 * 存在 `app_settings.defaultProbe`（红线 31）。后者不是总开关——只在**新建 Key 落进
 * 编辑页草稿那一刻**被读一次（`KeyEditorViewModel.defaultDraft`），之后每把独立（红线 36）。
 */
class ProbeSettingsViewModel constructor(
    private val settings: SettingsRepository,
    private val failures: SettingsFailures,
) : ViewModel() {

    /** 客户端嗅探开关。默认开（[SettingsRepository.observeSniffClientProfile] 兜底 true）。 */
    val sniffClientProfile: StateFlow<Boolean> = settings.observeSniffClientProfile()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** 新建供应商时的默认探测值（5 个布尔）。 */
    val defaultProbe: StateFlow<DefaultProbeSettings> = settings.observeDefaultProbeSettings()
        .stateIn(viewModelScope, SharingStarted.Eagerly, DefaultProbeSettings())

    val defaultProbeReachability: StateFlow<Boolean> = defaultProbe.map { it.reachability }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val defaultProbeKeys: StateFlow<Boolean> = defaultProbe.map { it.keys }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val defaultProbeBalance: StateFlow<Boolean> = defaultProbe.map { it.balance }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val defaultProbeModels: StateFlow<Boolean> = defaultProbe.map { it.models }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val defaultProbeModelReachability: StateFlow<Boolean> = defaultProbe.map { it.modelReachability }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 开关写不进库时界面已经翻过去了：不报一条提示，用户看到的就是一个会自己回弹的假开关。
    fun onSniffClientProfileChange(enabled: Boolean) {
        viewModelScope.launch { failures.guard { settings.setSniffClientProfile(enabled) } }
    }

    // 自动刷新的**执行者**不是这个 ViewModel：它是应用单例 `AutoRefresher`，由两端入口
    // 起一条进程级订阅（与 AutoLocker 同理——这个 ViewModel 只在用户站在那一页时活着）。
    // 这里只负责把同一条流画成开关与下拉，并写回用户的选择。

    /** 自动刷新开关。默认开（[SettingsRepository.observeAutoRefresh] 兜底 true）。 */
    val autoRefresh: StateFlow<Boolean> = settings.observeAutoRefresh()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * 间隔档位在下拉里的下标。**从仓库派生而不自己记一份**：真正生效的分钟数由
     * `AutoRefresher` 从同一条流读，自己记一份就会出现"页面上写 15 分钟、实际等 1 小时"。
     */
    val autoRefreshIntervalIndex: StateFlow<Int> = settings.observeAutoRefreshIntervalMinutes()
        .map { AutoRefreshPolicy.indexOf(it) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            AutoRefreshPolicy.indexOf(AutoRefreshPolicy.DEFAULT_MINUTES),
        )

    fun onAutoRefreshChange(enabled: Boolean) {
        viewModelScope.launch { failures.guard { settings.setAutoRefresh(enabled) } }
    }

    fun onAutoRefreshIntervalIndexChange(index: Int) {
        viewModelScope.launch {
            failures.guard { settings.setAutoRefreshIntervalMinutes(AutoRefreshPolicy.at(index)) }
        }
    }

    /** 改单个布尔，其余字段保持现状（`defaultProbe.value` 是当前快照）。 */
    fun onDefaultProbeReachabilityChange(enabled: Boolean) = patch { it.copy(reachability = enabled) }

    fun onDefaultProbeKeysChange(enabled: Boolean) = patch { it.copy(keys = enabled) }

    fun onDefaultProbeBalanceChange(enabled: Boolean) = patch { it.copy(balance = enabled) }

    fun onDefaultProbeModelsChange(enabled: Boolean) = patch { it.copy(models = enabled) }

    fun onDefaultProbeModelReachabilityChange(enabled: Boolean) =
        patch { it.copy(modelReachability = enabled) }

    private fun patch(transform: (DefaultProbeSettings) -> DefaultProbeSettings) {
        val next = transform(defaultProbe.value)
        viewModelScope.launch { failures.guard { settings.setDefaultProbeSettings(next) } }
    }
}
