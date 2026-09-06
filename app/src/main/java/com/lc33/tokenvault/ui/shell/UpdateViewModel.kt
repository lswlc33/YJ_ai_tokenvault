package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.engine.UpdateEngine
import com.lc33.tokenvault.engine.UpdateErrorKind
import com.lc33.tokenvault.engine.UpdateResult
import com.lc33.tokenvault.update.ReleaseInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 更新页（§13.4，`UpdateRoute`）。
 *
 * 状态只有「检查中 / 有更新 / 无更新 / 失败」四态。检查是用户手动点「立即检查」触发的
 * 一次性动作（自动检查默认关），所以这里是 MutableStateFlow 而非仓库订阅——结果只在
 * 这一次检查里有效，杀掉进程就丢，与更新页的临时 draft 语义一致。
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updateEngine: UpdateEngine,
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

    /**
     * 立即检查更新。
     *
     * @param channel 渠道下标：0=正式版、1=nightly（来自更新页的渠道下拉）。
     */
    fun checkNow(channel: Int) {
        // 已在检查中就不重复触发（防连点）。
        if (_state.value.phase == Phase.CHECKING) return
        _state.value = UiState(phase = Phase.CHECKING)
        viewModelScope.launch {
            val result = updateEngine.check(channel)
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
