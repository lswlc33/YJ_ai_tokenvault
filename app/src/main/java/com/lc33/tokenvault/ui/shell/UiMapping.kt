package com.lc33.tokenvault.ui.shell

import com.lc33.tokenvault.domain.KeyHealth
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.Group
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.domain.model.ProviderSummary
import com.lc33.tokenvault.screens.model.UiGroup
import com.lc33.tokenvault.screens.model.UiHealth
import com.lc33.tokenvault.screens.model.UiKeyRow
import com.lc33.tokenvault.screens.model.UiMoney
import com.lc33.tokenvault.screens.model.UiProviderRow
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 领域模型 → 页面展示模型。
 *
 * 放在 `ui/shell` 而不是 `screens/`：它是 ViewModel 的工具，而页面只该接已经算好的
 * UiState。放进 `screens/` 的表现是某个页面顺手 import 了 `domain.model.ApiKey`，
 * 于是列表页手上就有了密文与足以解开它的上下文。
 *
 * **这一层全是纯函数、不读资源、不读当前时间**：需要本地化的东西（相对时间、状态文案）
 * 留给页面去 `stringResource`，需要 `now` 的地方由调用方传。所以它可以在 JVM 单测里覆盖。
 */

/**
 * 持久结论 → 四档颜色。
 *
 * 分档理由：`UNAUTHORIZED` / `FORBIDDEN` 是"这把钥匙不对"，用户能改（换一把），是 Error；
 * `INSUFFICIENT` / `CLIENT_BLOCKED` / `CONFIG_ERROR` 是"钥匙可能没问题，配置或额度不对"，
 * 是 Warn——把它们也画成红的会让用户先去换密钥，而那解决不了问题。
 */
fun KeyHealth.toUi(): UiHealth = when (this) {
    KeyHealth.OK -> UiHealth.Ok
    KeyHealth.UNAUTHORIZED, KeyHealth.FORBIDDEN -> UiHealth.Error
    KeyHealth.INSUFFICIENT, KeyHealth.CLIENT_BLOCKED, KeyHealth.CONFIG_ERROR -> UiHealth.Warn
    KeyHealth.UNKNOWN -> UiHealth.Unknown
}

/**
 * 供应商的聚合状态（§5.3 末尾）。
 *
 * 规则：**有一把可用就算可用**（多把密钥的意义正在于此），一把都没有时看最坏的那一档，
 * 一把密钥都没录就是未探测。
 */
fun aggregateHealth(keyHealths: List<KeyHealth>): UiHealth = when {
    keyHealths.isEmpty() -> UiHealth.Unknown
    keyHealths.any { it.usable } -> UiHealth.Ok
    keyHealths.map { it.toUi() }.any { it == UiHealth.Error } -> UiHealth.Error
    keyHealths.map { it.toUi() }.any { it == UiHealth.Warn } -> UiHealth.Warn
    else -> UiHealth.Unknown
}

/**
 * 金额 → 展示串。
 *
 * **先定点舍入再展示**（§9.1）：`BigDecimal.valueOf` + `HALF_UP` 到两位。
 * 直接把 `Double` 拼进字符串的表现是首页出现 `42.099999999999994`。
 * 币种不在这里拼——它是 [UiMoney] 的独立字段，因为首页按币种分组且不做汇率换算（红线 15）。
 */
fun formatAmount(amount: Double): String =
    BigDecimal.valueOf(amount).setScale(MONEY_SCALE, RoundingMode.HALF_UP).toPlainString()

private const val MONEY_SCALE = 2

/**
 * 余额快照 → 展示金额。
 *
 * 查询失败与"没查过"都返回 null：这两种情况下**不能给一个数字**，否则用户会以为那是
 * 真的余额。失败这件事由别的地方表达（管理页的行不显示金额，仪表盘单独数"几家查询失败"）。
 */
fun BalanceSnapshot?.toUiMoney(): UiMoney? {
    val snapshot = this ?: return null
    if (snapshot.failed) return null
    val amount = snapshot.amount ?: return null
    return UiMoney(currency = snapshot.currency, amount = formatAmount(amount))
}

/**
 * 地址显示成 host。
 *
 * 不用 `java.net.URI`：用户输入的地址在保存前可能过不了 URI 解析，而这一层是**展示**，
 * 解析失败就原样显示——列表页因此永远画得出东西。真正的规范化在 `endpoint/`。
 */
fun hostOf(apiRoot: String): String = apiRoot
    .substringAfter("://")
    .substringBefore('/')
    .ifEmpty { apiRoot }

fun ProviderSummary.toRow(health: UiHealth, staleThisRound: Boolean = false): UiProviderRow =
    UiProviderRow(
        id = provider.id,
        name = provider.name,
        note = provider.note,
        host = hostOf(provider.apiRoot),
        // 协议 chips 用 wireName：它稳定，而且这一排不是给人读的散文
        protocols = provider.supportedProtocols.map { it.wireName },
        colorIndex = provider.color ?: 0,
        pinned = provider.pinned,
        groupId = provider.groupId,
        keyCount = keyCount,
        okKeyCount = okKeyCount,
        modelCount = modelCount,
        accountCount = accountCount,
        balance = provider.balance.toUiMoney(),
        health = health,
        staleThisRound = staleThisRound,
    )

/**
 * 分组筛选条。**第一枚是「全部」那个伪分组**（`id == null`，不入库）。
 *
 * 计数在这里算而不是在 SQL 里：分组筛选是纯 UI 行为（`ManageUiState.visibleProviders`
 * 也在内存里筛），两边用同一份数据算，数字才不会对不上。
 */
fun groupChips(allLabel: String, groups: List<Group>, providers: List<UiProviderRow>): List<UiGroup> =
    listOf(UiGroup(id = null, name = allLabel, providerCount = providers.size)) +
        groups.map { group ->
            UiGroup(
                id = group.id,
                name = group.name,
                providerCount = providers.count { it.groupId == group.id },
            )
        }

/**
 * 密钥行。[masked] 必须由调用方解密后现算（§6.1 推论 3），所以它是参数而不是从 [ApiKey] 取——
 * 那个类型上**没有任何能拿到明文的方法**，这一点是刻意的。
 */
fun ApiKey.toRow(masked: String): UiKeyRow = UiKeyRow(
    id = id,
    label = label,
    providerId = providerId,
    masked = masked,
    health = health.toUi(),
    latencyMs = latencyMs,
    checkedAt = checkedAt,
    isDefault = isDefault,
)

/** 供应商的协议集合供编辑页显示；顺序按枚举声明，所以每次进页面 chips 不会跳。 */
fun Provider.protocolWireNames(): List<String> = supportedProtocols.map { it.wireName }
