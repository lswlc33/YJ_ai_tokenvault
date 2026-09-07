package com.lc33.tokenvault.net

import com.lc33.tokenvault.domain.repo.SettingsRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 手动 HTTP 代理的运行时提供者（§7.5、§13.4）。
 *
 * 订阅设置里的代理串、解析成 [ProxyConfig]，并在变化时用 [HttpEngine.buildClient]
 * 重建 client——Ktor 的 proxy 是引擎级配置（不像 OkHttp 能每请求 `newBuilder().proxy`），
 * 所以"改了就立刻生效"从"每请求换 client"变成"变化时换 client"。
 *
 * 代理是全局网络设置（探测、余额、更新检查、元数据拉取都走它），所以这个 provider
 * 是应用级单例。这里只依赖 `SettingsRepository`（domain/repo，纯接口）与协程，可进
 * commonMain；持有方（DI）在代理变化后把新 client 交给 [HttpEngine]。
 */
class ProxyProvider(
    settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前 client；无代理时是默认 client。 */
    @Volatile
    var client: HttpClient = buildClient(null)
        private set

    init {
        scope.launch {
            settings.observeProxy().collect { hostPort ->
                client = buildClient(parseProxy(hostPort))
            }
        }
    }
}
