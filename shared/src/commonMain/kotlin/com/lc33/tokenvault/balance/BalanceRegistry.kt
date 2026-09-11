package com.lc33.tokenvault.balance

import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.model.KeySettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * 余额适配器注册表（§9.2）。
 *
 * 按 [Provider.balanceKind] 挑适配器。除了 `customJson`（带 config，每次现造）与
 * 需要减法的 openrouter 之外，其余都是无状态单例。
 *
 * [NONE] 没有适配器——[forProvider] 返回 null，调用方据此跳过（不是错误）。
 */
object BalanceRegistry {

    private val newApi = NewApiAdapter()
    private val deepSeek = DeepSeekAdapter()
    private val openRouter = OpenRouterAdapter()
    private val siliconFlow = BuiltinBalanceAdapters.siliconflow()
    private val moonshot = BuiltinBalanceAdapters.moonshot()

    private val json = Json { ignoreUnknownKeys = true }

    /** 按供应商的 [BalanceKind] 选适配器。`none` / 认不出来的返回 null（跳过）。 */
    fun forSettings(settings: KeySettings): BalanceAdapter? = when (settings.balanceKind) {
        BalanceKind.NEWAPI -> newApi
        BalanceKind.DEEPSEEK -> deepSeek
        BalanceKind.OPENROUTER -> openRouter
        BalanceKind.SILICONFLOW -> siliconFlow
        BalanceKind.MOONSHOT -> moonshot
        BalanceKind.CUSTOM_JSON -> CustomJsonAdapter(
            runCatching { json.parseToJsonElement(settings.balanceConfig).jsonObject }
                .getOrElse { kotlinx.serialization.json.JsonObject(emptyMap()) },
        )
        BalanceKind.NONE -> null
    }

    /** 测试 / 展示用：某个 kind 有没有已注册的适配器。 */
    fun hasAdapter(kind: BalanceKind): Boolean = kind != BalanceKind.NONE
}
