package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.domain.repo.SettingsRepository
import com.lc33.tokenvault.engine.UpdateEngine
import com.lc33.tokenvault.engine.UpdateErrorKind
import com.lc33.tokenvault.engine.UpdateResult
import com.lc33.tokenvault.update.ReleaseInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 更新页（§13.4，`UpdateRoute`）。
 *
 * 状态只有「检查中 / 有更新 / 无更新 / 失败」四态。检查是用户手动点「立即检查」触发的
 * 一次性动作（自动检查默认关），所以结果是 MutableStateFlow 而非仓库订阅——结果只在
 * 这一次检查里有效，杀掉进程就丢。**更新渠道是例外**：它是个持久化偏好（用户选了
 * nightly 就该一直跟进 nightly，杀进程不该悄悄回到正式版），权威是
 * `app_settings.updateChannel`（红线 31），从同一条流派生。
 */
class UpdateViewModel constructor(
    private val updateEngine: UpdateEngine,
    private val settings: SettingsRepository,
) : ViewModel() {

    /** 更新检查的状态。初始为 [Phase.IDLE]，用户点「立即检查」前不主动联网。 */
    data class UiState(
        val phase: Phase = Phase.IDLE,
        val latest: ReleaseInfo? = null,
        val error: UpdateErrorKind? = null,
    )

    enum class Phase {
        /** 还没检查过。 */
        IDLE,
        /** 正在检查。 */
        CHECKING,
        /** 检查完成，无更新（latest 为空或版本不新）。 */
        UP_TO_DATE,
        /** 检查完成，有更新（latest 非空）。 */
        UPDATE_AVAILABLE,
        /** 检查失败（error 非空，可区分没网 / 打不开）。 */
        ERROR,
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 更新渠道（0=正式版、1=nightly）。权威是 `app_settings.updateChannel`，默认正式版。 */
    val updateChannel: StateFlow<Int> = settings.observeUpdateChannel()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun onUpdateChannelChange(channel: Int) {
        viewModelScope.launch { settings.setUpdateChannel(channel) }
    }

    /**
     * 立即检查更新。渠道从 [updateChannel] 的当前值取（权威存储，不是 UI 传参）。
     */
    fun checkNow() {
        // 已在检查中就不重复触发（防连点）。
        if (_state.value.phase == Phase.CHECKING) return
        _state.value = UiState(phase = Phase.CHECKING)
        viewModelScope.launch {
            val result = updateEngine.check(updateChannel.value)
            _state.value = result.toUiState()
        }
    }

    private fun UpdateResult.toUiState(): UiState = when {
        error != null -> UiState(phase = Phase.ERROR, error = error)
        latest == null -> UiState(phase = Phase.UP_TO_DATE)
        newer -> UiState(phase = Phase.UPDATE_AVAILABLE, latest = latest)
        else -> UiState(phase = Phase.UP_TO_DATE, latest = latest)
    }
}
