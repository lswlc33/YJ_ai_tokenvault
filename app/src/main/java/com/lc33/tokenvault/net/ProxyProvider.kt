package com.lc33.tokenvault.net

import com.lc33.tokenvault.domain.repo.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.Proxy
import java.util.concurrent.atomic.AtomicReference

/**
 * 手动 HTTP 代理的运行时提供者（§7.5、§13.4）。
 *
 * 为什么单独一个类而不是让 [OkHttpEngine] 直接读 [SettingsRepository]：
 * `net` 层是纯 Kotlin（分层规则，见 `old_plan.md` §4.3），不能 import Room 仓库，
 * 而 `SettingsRepository` 的 Flow 需要在某个协程里订阅才能拿到最新值。
 *
 * 这里订阅 Flow、把解析后的 [Proxy] 缓存进 [AtomicReference]，[OkHttpEngine] 通过
 * `current` 拿值——读是同步的、无锁的，不会拖慢每个请求。
 *
 * 代理是全局网络设置（探测、余额、将来的更新检查与元数据拉取都该走它），所以这个
 * provider 是 `@Singleton`。
 */
class ProxyProvider(
    settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前代理；null = 走系统代理。 */
    private val currentProxy = AtomicReference<Proxy?>(null)

    init {
        scope.launch {
            settings.observeProxy().collect { hostPort ->
                currentProxy.set(OkHttpEngine.parseProxy(hostPort))
            }
        }
    }

    fun current(): Proxy? = currentProxy.get()
}
