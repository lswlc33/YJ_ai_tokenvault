package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.domain.repo.SettingsRepository
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 客户端拦截关键词编辑（§8.2、§13.4 探测设置页的二级页）。
 *
 * 关键词匹配上游响应的**协议内容**，不是 UI 文案，所以不进 strings.xml（i18n-exempt）。
 * 增删都在内存里改一份副本，点「保存」才写库——这样中途退出不落半截改动，
 * 也避免了每删一个词就写一次库（红线 16 要求每个持久化字段有明确的产生与消费路径，
 * 而「逐字写库」等于把用户还没确认的中间态持久化了）。
 */
class ClientKeywordsViewModel constructor(
    private val settings: SettingsRepository,
    private val failures: SettingsFailures,
) : ViewModel() {

    /** 已存的关键词。**null = 还没读到**（Room 首帧异步，见 BalanceThresholdsViewModel）。 */
    val keywords: StateFlow<List<String>?> = settings.observeClientKeywords()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * 写库成功。**由这里发、页面收到才退回**：以前是先退页再异步写，写失败时用户
     * 已经在上一页了，而关键词表还是旧的——那句"已经保存"就成了假话。
     */
    private val _saved = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

    /**
     * 保存。把 [items] 整份写库。空列表表示"回到默认"（与没写过等价），
     * 因为 [com.lc33.tokenvault.data.repo.RoomSettingsRepository] 对空/缺省都回退默认表。
     */
    fun save(items: List<String>) {
        viewModelScope.launch {
            runCatching { settings.setClientKeywords(items) }
                .onSuccess { _saved.tryEmit(Unit) }
                // 写不进去时这一页留在原地，失败提示由 SettingsFailures 统一投一条。
                .onFailure { failures.report() }
        }
    }
}
