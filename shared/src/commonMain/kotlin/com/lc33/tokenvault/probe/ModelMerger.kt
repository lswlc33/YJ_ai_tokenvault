package com.lc33.tokenvault.probe

import com.lc33.tokenvault.domain.ModelSource
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.AiModel

/**
 * 一轮模型列表合并要执行的动作（§8.3 的三路合并，红线 13、30）。
 *
 * 刻意做成"动作描述"而不是直接落库：合并是纯逻辑，放 `probe/` 包用 JVM 单测覆盖，
 * 落库是 `data/` 层的事（`@Transaction` + DAO）。这样测试 12 不依赖 Room。
 */
data class ModelMergePlan(
    /** 库里没有、列表里有的，要插入。`source = discovered`、`discoveredVia = 本协议`。 */
    val toInsert: List<NewDiscoveredModel>,

    /** 两边都有，要 `touchLastSeen`。 */
    val toTouch: List<Long>,

    /** 库里是 `discovered` 且 `discoveredVia == 本协议`、但列表里没有，要停用。 */
    val toDisable: List<Long>,
)

/** 要插入的新发现模型。 */
data class NewDiscoveredModel(
    val modelId: String,
    val protocol: Protocol,
)

/**
 * 模型列表三路合并（计划.md §8.3，红线 13、30）。
 *
 * **合并的作用域是"本供应商 + 本轮实际查询过的那个协议"**——模型列表是按协议分别拉的
 * （OpenAI 系与 Anthropic 系是两条路由、两套鉴权头），所以"消失即停用"只能作用于
 * `discoveredVia == 本轮协议` 的行。少了这个限定，只拉了 CHAT 列表就会把所有 ANTHROPIC
 * 发现项一起停用（红线 30）。
 *
 * 四条规则：
 * - 列表里有、库里没有 → 插入，`source = 'discovered'`、`discoveredVia = 本协议`。
 * - 两边都有 → `touchLastSeen`。
 * - 库里有、列表里没有，且 `source = 'discovered'` 且 `discoveredVia = 本协议` → 停用。
 * - `source = 'manual'` 的行、以及 `discoveredVia` 是别的协议的行 → **完全不动**（红线 13）。
 *
 * @param existing 库里该供应商已有的模型（不限协议）。
 * @param fetched 本轮拉到的模型 id 列表（已经解析出协议，或由调用方先归到本协议）。
 * @param thisProtocol 本轮实际查询的协议。
 */
object ModelMerger {

    fun merge(
        existing: List<AiModel>,
        fetched: List<NewDiscoveredModel>,
        thisProtocol: Protocol,
    ): ModelMergePlan {
        val fetchedByProtocol = fetched
            .filter { it.protocol == thisProtocol }
            .associateBy { it.modelId }

        val toInsert = mutableListOf<NewDiscoveredModel>()
        val toTouch = mutableListOf<Long>()
        val toDisable = mutableListOf<Long>()

        // 已经处理过的 modelId（插入或 touch），用于后面判断"消失"。
        val seen = mutableSetOf<String>()

        for (existingModel in existing) {
            val fetchedModel = fetchedByProtocol[existingModel.modelId]
            if (fetchedModel != null) {
                // 两边都有 → touch lastSeenAt。
                toTouch += existingModel.id
                seen += existingModel.modelId
            } else {
                // 库里没有对应条目。只有 discovered + 本协议 的行才停用。
                if (existingModel.source == ModelSource.DISCOVERED &&
                    existingModel.discoveredVia == thisProtocol
                ) {
                    toDisable += existingModel.id
                }
                // manual 或别的协议 → 完全不动。
            }
        }

        // 列表里有、库里没有 → 插入。
        for (fetchedModel in fetched) {
            if (fetchedModel.protocol != thisProtocol) continue
            if (fetchedModel.modelId in seen) continue
            // 库里可能已有同 modelId 但不同协议的行——那种情况 above 已经 touch 了，
            // 这里只处理"库里完全没有这个 modelId"的。
            if (existing.any { it.modelId == fetchedModel.modelId }) continue
            toInsert += fetchedModel
        }

        return ModelMergePlan(
            toInsert = toInsert.distinctBy { it.modelId },
            toTouch = toTouch.distinct(),
            toDisable = toDisable.distinct(),
        )
    }
}
