package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.domain.DefaultProbeSettings
import com.lc33.tokenvault.domain.repo.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 探测设置页（§13.4）里**已经接真**的状态源。
 *
 * 两项：客户端嗅探开关（权威 `app_settings.sniffClientProfile`）与「新建供应商默认探测值」
 * 四个开关（权威 `app_settings.defaultProbe`，红线 31）。后者不是总开关——只在新供应商
 * 落进编辑页草稿那一刻被读一次，之后每家独立（红线 36）。
 */
class ProbeSettingsViewModel constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    /** 客户端嗅探开关。默认开（[SettingsRepository.observeSniffClientProfile] 兜底 true）。 */
    val sniffClientProfile: StateFlow<Boolean> = settings.observeSniffClientProfile()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** 新建供应商时的默认探测值（4 个布尔）。 */
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

    fun onSniffClientProfileChange(enabled: Boolean) {
        viewModelScope.launch { settings.setSniffClientProfile(enabled) }
    }

    /** 改单个布尔，其余字段保持现状（`defaultProbe.value` 是当前快照）。 */
    fun onDefaultProbeReachabilityChange(enabled: Boolean) = patch { it.copy(reachability = enabled) }

    fun onDefaultProbeKeysChange(enabled: Boolean) = patch { it.copy(keys = enabled) }

    fun onDefaultProbeBalanceChange(enabled: Boolean) = patch { it.copy(balance = enabled) }

    fun onDefaultProbeModelsChange(enabled: Boolean) = patch { it.copy(models = enabled) }

    private fun patch(transform: (DefaultProbeSettings) -> DefaultProbeSettings) {
        val next = transform(defaultProbe.value)
        viewModelScope.launch { settings.setDefaultProbeSettings(next) }
    }
}
