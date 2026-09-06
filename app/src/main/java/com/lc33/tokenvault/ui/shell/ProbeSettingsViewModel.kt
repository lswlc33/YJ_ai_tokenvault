package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.domain.repo.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 探测设置页（§13.4）里**已经接真**的那一项的状态源。
 *
 * 目前只有客户端嗅探开关一项：它是这页里唯一一个"消费方已写好、只差开关接线"的设置，
 * 权威是 `app_settings.sniffClientProfile`（红线 31）。其余几项（`defaultProbe*`）仍在
 * `SettingsDraft` 里，是待拍板的假开关，见 CLAUDE.md「SettingsDraft 剩余假开关清单」。
 */
@HiltViewModel
class ProbeSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    /** 客户端嗅探开关。默认开（[SettingsRepository.observeSniffClientProfile] 兜底 true）。 */
    val sniffClientProfile: StateFlow<Boolean> = settings.observeSniffClientProfile()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun onSniffClientProfileChange(enabled: Boolean) {
        viewModelScope.launch { settings.setSniffClientProfile(enabled) }
    }
}
