package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.domain.repo.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 会员（娱乐功能，§设置页顶部那张卡）。
 *
 * **它不承载任何权限**，这一点在实现上必须看得很清楚：整个类只读写 `app_settings.member`
 * 一个布尔键，没有任何别的模块订阅它、没有任何行为因它而变。购买页上那些"特权"
 * （流畅度、安全性…）全是文案，用户点了"限时免费"只是把这一个布尔翻开。
 *
 * 之所以仍然做成 ViewModel + 仓库而不是设置页里一个 `remember { mutableStateOf }`：
 * 那样切页就丢、杀进程就回原样，用户会觉得"我买的会员没了"。写进库里才符合"我买了"的预期，
 * 而这是这个玩笑里唯一需要认真对待的部分。
 */
class MemberViewModel constructor(
    private val settings: SettingsRepository,
    private val failures: SettingsFailures,
) : ViewModel() {

    /** 当前是不是会员。权威在 `app_settings.member`，这里从同一条流派生（红线 31）。 */
    val isMember: StateFlow<Boolean> = settings.observeMember()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 「限时免费」按钮：翻成会员。 */
    fun onPurchase() {
        viewModelScope.launch { failures.guard { settings.setMember(true) } }
    }

    /** 「取消」按钮：退回普通用户。 */
    fun onCancel() {
        viewModelScope.launch { failures.guard { settings.setMember(false) } }
    }
}
