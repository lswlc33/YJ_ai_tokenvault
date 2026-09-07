package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.domain.repo.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 手动 HTTP 代理编辑（§7.5、§13.4 探测设置页的二级页）。
 *
 * 单个 `host:port` 字符串，空 = 走系统代理。解析成 [com.lc33.tokenvault.net.ProxyConfig]
 * 的动作在 net 层（纯函数 [com.lc33.tokenvault.net.parseProxy]，net 层唯一一处），
 * 这里只做校验 + 写库。
 */
class ProxyViewModel constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    /** 已存的代理串。**null = 还没读到**（Room 首帧异步，见 BalanceThresholdsViewModel）。 */
    val proxy: StateFlow<String?> = settings.observeProxy()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * 保存。空串 = 走系统代理。返回 [SaveResult] 供页面判断。
     */
    fun save(hostPort: String): SaveResult {
        val trimmed = hostPort.trim()
        if (trimmed.isNotEmpty() && !isValidHostPort(trimmed)) {
            return SaveResult.Invalid
        }
        viewModelScope.launch {
            settings.setProxy(trimmed)
        }
        return SaveResult.Saved
    }

    /** 粗略校验：至少是 `host:port` 或 `[ipv6]:port` 的形态，端口是数字或省略。 */
    private fun isValidHostPort(s: String): Boolean {
        // 交给 net 层的 parseProxy 做权威解析，这里只做"明显不是"的拦截，
        // 避免把"http://..."这种整段 URL 塞进去。
        if (s.contains("://") || s.contains('/') || s.contains(' ')) return false
        return true
    }

    sealed interface SaveResult {
        data object Saved : SaveResult
        data object Invalid : SaveResult
    }
}
