package com.lc33.tokenvault.ui.shell

import com.lc33.tokenvault.domain.BalanceState
import com.lc33.tokenvault.domain.KeyHealth
import com.lc33.tokenvault.domain.ProbeOutcome
import com.lc33.tokenvault.data.entity.ProbeRunEntity
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.ClientProfile
import com.lc33.tokenvault.domain.model.Group
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.domain.model.ProviderSummary
import com.lc33.tokenvault.probe.ProbeItemResult
import com.lc33.tokenvault.screens.model.AttentionItem
import com.lc33.tokenvault.screens.model.AttentionKind
import com.lc33.tokenvault.screens.model.BalanceSummary
import com.lc33.tokenvault.screens.model.ContentCounts
import com.lc33.tokenvault.screens.model.HealthBreakdown
import com.lc33.tokenvault.screens.model.ProbeRunSummary
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
 * 探测明细页的每一项 → 四档颜色。
 *
 * 有 [ProbeItemResult.health]（判定性结论）就用它；没有说明是瞬时失败（红线 11 不许
 * 改写持久结论），这时按 outcome 给个"本轮视角"的颜色：
 * - 网络 / 429 / 5xx → Warn（确实是异常，但没到"换钥匙"那一步）；
 * - 跳过 / 取消 → Unknown（这一轮根本没测，不是坏了）。
 */
fun ProbeItemResult.toUiHealth(): UiHealth = health?.toUi() ?: when (outcome) {
    ProbeOutcome.NETWORK_ERROR, ProbeOutcome.RATE_LIMITED, ProbeOutcome.UPSTREAM_ERROR -> UiHealth.Warn
    ProbeOutcome.SKIPPED, ProbeOutcome.CANCELLED -> UiHealth.Unknown
    ProbeOutcome.SUCCESS -> UiHealth.Ok
    // 判定性失败却没有 health 是异常态，兜底按"不可用"画红，宁可多告警也别漏报。
    ProbeOutcome.CONCLUSIVE_FAIL -> UiHealth.Error
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

/**
 * 客户端预设的显示名。
 *
 * 只有内置的 `default` 需要本地化（其余内置预设是品牌名、自定义预设是用户自己起的名字），
 * 而这一层是纯函数、读不到资源，所以本地化名由调用方用 `stringResource` 取好传进来。
 */
fun ClientProfile.displayName(defaultProfileName: String): String =
    if (builtinKey == "default") defaultProfileName else name

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
        // “试过但失败”与“压根没查过”必须分开（§9.3），而 toUiMoney 两者都给 null
        balanceFailed = provider.balance?.failed == true,
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

// ---------------------------------------------------------------- 仪表盘的四个聚合

/**
 * 内容计数。
 *
 * **数字全部来自同一条聚合查询**（`ProviderDao.observeSummaries`），而管理页每一行用的
 * 也是它。仪表盘自己再数一遗的代价很具体：首屏说 10 把、管理页加起来是 9 把，
 * 而两个数字都“看起来对”，没人能当场分出哪个错。
 *
 * 密钥与模型都只数**已启用**的（那条 SQL 带了 `enabled = 1`），所以
 * [healthBreakdownOf] 也必须只算已启用的——否则同一屏里“密钥 10”与“11 张全部可用”
 * 会同时出现。
 */
fun contentCountsOf(summaries: List<ProviderSummary>): ContentCounts = ContentCounts(
    providers = summaries.size,
    keys = summaries.sumOf { it.keyCount },
    models = summaries.sumOf { it.modelCount },
    accounts = summaries.sumOf { it.accountCount },
)

/**
 * 密钥健康分布。只算**已启用**的密钥，理由见 [contentCountsOf]。
 *
 * 只读 `health`（持久结论），不看 `lastOutcome`：一次限流不该把分布图染成红的（红线 11）。
 */
fun healthBreakdownOf(keys: List<ApiKey>): HealthBreakdown {
    val buckets = keys.filter { it.enabled }.groupingBy { it.health.toUi() }.eachCount()
    return HealthBreakdown(
        ok = buckets[UiHealth.Ok] ?: 0,
        warn = buckets[UiHealth.Warn] ?: 0,
        error = buckets[UiHealth.Error] ?: 0,
        unknown = buckets[UiHealth.Unknown] ?: 0,
    )
}

/**
 * 按币种的余额合计（§9.1、红线 15）。
 *
 * 三条规则：
 *
 * 1. **先定点舍入再相加**，否则首屏会出现 `42.099999999999994`。
 * 2. **查询失败的不参与合计**，它们只计入 [BalanceSummary.failedProviderCount]——
 *    把“不知道”当成 0 相加等于把一个猜测当成余额报给用户。
 * 3. **币种按字母序**。不按金额大小排：跜币种比大小没有意义（20 USD 比 100 CNY 多），
 *    而首屏那一行会被放大成“主币种”，按金额排就永远是数字大的那个冒充主角。
 *    字母序至少是**稳定**的：刷一次余额不会让两行对调。
 */
fun balanceSummaryOf(providers: List<Provider>): BalanceSummary {
    val snapshots = providers.mapNotNull { it.balance }
    val perCurrency = snapshots
        .filter { !it.failed && it.amount != null }
        .groupBy { it.currency }
        .toSortedMap()
        .map { (currency, group) ->
            val total = group.fold(BigDecimal.ZERO) { acc, snapshot ->
                acc + BigDecimal.valueOf(snapshot.amount!!).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
            }
            UiMoney(currency = currency, amount = total.setScale(MONEY_SCALE, RoundingMode.HALF_UP).toPlainString())
        }
    return BalanceSummary(
        perCurrency = perCurrency,
        // 最新那一次查询的时间。取最大值而不是最小：这一行回答的是“这堆数字有多新”
        updatedAt = snapshots.mapNotNull { it.checkedAt }.maxOrNull(),
        failedProviderCount = snapshots.count { it.failed },
    )
}

/**
 * 需要动手处理的那几项（§13.4 第四块卡）。
 *
 * **只看 `health`，永远不看 `lastOutcome`**（红线 11）：网络不可达与 429 不是要动手的事，
 * 混进来会让用户以为有三样东西坏了。
 *
 * 每家最多两项：密钥那一类只取**最严重的一档**（三把密钥各坏一种不该刷出三行，
 * 用户在详情页一眼就看得到全部），余额低是独立一项——它和密钥好不好无关，且解法不同。
 *
 * @param thresholds 按币种的提醒阈值。由调用方传而不是在这里写死（红线 15）；
 *   查不到该币种时不判低，因为猜一个阈值等于编一个结论。
 */
fun attentionItemsOf(
    summaries: List<ProviderSummary>,
    healthByProvider: Map<Long, List<KeyHealth>>,
    thresholds: Map<String, Double>,
): List<AttentionItem> {
    val items = mutableListOf<AttentionItem>()
    for (summary in summaries) {
        val provider = summary.provider
        val healths = healthByProvider[provider.id].orEmpty()
        val balanceState = provider.balance?.state(thresholds)
        val kinds = LinkedHashSet<AttentionKind>()
        keyAttentionKindOf(healths)?.let { kinds += it }
        // 两条路径指向同一件事（“这家没钱了”）：探密钥时上游直接说了额度不足，
        // 或者余额查询回来的数字低于阈值。用 Set 去重，否则同一个问题刷两行
        if (healths.any { it == KeyHealth.INSUFFICIENT } ||
            balanceState == BalanceState.LOW ||
            balanceState == BalanceState.NEGATIVE
        ) {
            kinds += AttentionKind.LowBalance
        }
        kinds.forEach { kind ->
            items += AttentionItem(
                providerId = provider.id,
                providerName = provider.name,
                // 只有“密钥被拒”是红的：其余三档换一把密钥解决不了，画成红的会把人往错路上引
                health = if (kind == AttentionKind.KeyRejected) UiHealth.Error else UiHealth.Warn,
                kind = kind,
            )
        }
    }
    // 按严重程度排（枚举声明顺序），同档里按名字——顺序必须稳定，否则刷一次数据
    // 这个列表就重排一次，而用户正要点其中一行
    return items.sortedWith(compareBy({ it.kind.ordinal }, { it.providerName }))
}

/**
 * 一家的密钥里最要紧的那一档。一把密钥都没录、或者全部未探测时返回 null——
 * “还没探测过”不是要处理的问题，把它列进去等于让新装的应用首屏就满屏告警。
 */
private fun keyAttentionKindOf(healths: List<KeyHealth>): AttentionKind? = when {
    healths.any { it == KeyHealth.UNAUTHORIZED || it == KeyHealth.FORBIDDEN } -> AttentionKind.KeyRejected
    // 客户端被拦排在配置错之前：它有一个很具体的一键修法（换预设）
    healths.any { it == KeyHealth.CLIENT_BLOCKED } -> AttentionKind.ClientBlocked
    healths.any { it == KeyHealth.CONFIG_ERROR } -> AttentionKind.ConfigError
    // INSUFFICIENT 不在这里：它与“余额低”是同一件事，在上面那一支跟余额一起去重
    else -> null
}

/**
 * `probe_runs` 最新一行 → "上次探测"摘要（时间戳，不碰资源，红线 19）。
 *
 * 仪表盘摘要卡与探测明细页共用这一个映射：同一个数字在两处各算一遍，迟早对不上
 * （CLAUDE.md 的原话）。相对时间与耗时文案由页面用 `relativeLabel` / `durationSeconds` 现算。
 */
fun ProbeRunEntity.toSummary(): ProbeRunSummary = ProbeRunSummary(
    finishedAtMs = finishedAt ?: startedAt,
    durationMs = ((finishedAt ?: startedAt) - startedAt).coerceAtLeast(0),
    total = total,
    succeeded = okCount,
    failed = failCount,
    // 未探测 = total - done（§8.5：超预算 / 撞 host 预算 / 锁定被标 SKIPPED 的不算 done）。
    skipped = total - done,
)
