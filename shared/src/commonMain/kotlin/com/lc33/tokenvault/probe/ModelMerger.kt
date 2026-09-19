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

    /** 库里是 `discovered` 且 `discoveredVia == 本协议`、但列表里没有，要删除。 */
    val toDelete: List<Long>,
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
 * （OpenAI 系与 Anthropic 系是两条路由、两套鉴权头），所以"消失即删除"只能作用于
 * `discoveredVia == 本轮协议` 的行。少了这个限定，只拉了 CHAT 列表就会把所有 ANTHROPIC
 * 发现项一起删掉（红线 30）。
 *
 * 四条规则：
 * - 列表里有、库里没有 → 插入，`source = 'discovered'`、`discoveredVia = 本协议`。
 * - 两边都有 → `touchLastSeen`。
 * - 库里有、列表里没有，且 `source = 'discovered'` 且 `discoveredVia = 本协议` → 删除。
 * - `source = 'manual'` 的行、以及 `discoveredVia` 是别的协议的行 → **完全不动**（红线 13）。
 *
 * @param existing 库里该供应商已有的模型（不限协议）。
 * @param fetched 本轮拉到的模型 id 列表（已经解析出协议，或由调用方先归到本协议）。
 * @param thisProtocol 本轮实际查询的协议。
 *
 * **关于 `fetched` 为空**：这里保留了"空列表 → 把本协议的发现项全删"的能力，但调用方
 * 不该再把"可疑空"传进来——`ModelListParser` 现在把响应分成 Confirmed / SuspiciousEmpty /
 * Unparseable 三种结局，只有 Confirmed（至少一条带 id）才会走到 `applyDiscovered`。
 * 判空守在那里而不是在这里，是因为只有解析层知道"这个空是上游真给了个空数组，还是响应
 * 压根不是模型列表"，合并层只看数据形态，两者一混就没法回头区分了。
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
        val toDelete = mutableListOf<Long>()

        // 已经处理过的 modelId（插入或 touch），用于后面判断"消失"。
        val seen = mutableSetOf<String>()

        for (existingModel in existing) {
            val fetchedModel = fetchedByProtocol[existingModel.modelId]
                ?.takeIf { existingModel.protocol == thisProtocol }
            if (fetchedModel != null) {
                // 两边都有 → touch lastSeenAt。
                toTouch += existingModel.id
                seen += existingModel.modelId
            } else {
                // 库里没有对应条目。只有 discovered + 本协议 的行才删除。
                if (existingModel.source == ModelSource.DISCOVERED &&
                    existingModel.discoveredVia == thisProtocol
                ) {
                    toDelete += existingModel.id
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
            if (existing.any { it.modelId == fetchedModel.modelId && it.protocol == thisProtocol }) continue
            toInsert += fetchedModel
        }

        return ModelMergePlan(
            toInsert = toInsert.distinctBy { it.modelId to it.protocol },
            toTouch = toTouch.distinct(),
            toDelete = toDelete.distinct(),
        )
    }

    /**
     * 一次解析的产物折成"每个模型只留一个协议"，并且只留这把 Key 声明过的协议。
     *
     * 为什么需要：[ModelListParser] 会把一条上游条目摊成多个协议——new-api 的
     * `supported_endpoint_types: ["openai"]` 同时覆盖 chat 与 responses（M0.5 实测，见
     * `Protocol.protocolsForEndpointType` 的注释）。摊开本身没错，错在往下走：
     * `models` 表按协议分行，而 [merge] 的"消失即删"又限定 `discoveredVia == 本轮协议`，
     * 两套行互不清理。于是同一个模型在库里躺两行，界面上就是一份重复的模型列表，
     * 而且**每刷新一次就重新长出来一次**。
     *
     * 这张表的语义是"这个模型发到哪条路径"（红线 18），一行只能答一个协议；真要留
     * "它还支持别的协议"得另开一列，不在这里塞。取哪个：优先这把 Key 声明序的第一个
     * （与 `ProbePlanBuilder.buildModelListTasks` 请求所用的协议一致，手动刷新与自动轮
     * 写出的 `discoveredVia` 才会是同一个），它不在候选里才退到候选的第一个——
     * 只标了 `["anthropic"]` 的模型不该被强行记成 chat。
     */
    fun oneProtocolPerModel(
        discovered: List<NewDiscoveredModel>,
        allowed: Set<Protocol>,
        preferred: Protocol?,
    ): List<NewDiscoveredModel> =
        discovered
            .filter { it.protocol in allowed }
            .groupBy { it.modelId }
            .map { (modelId, entries) ->
                NewDiscoveredModel(
                    modelId = modelId,
                    protocol = entries.firstOrNull { it.protocol == preferred }?.protocol
                        ?: entries.first().protocol,
                )
            }
}
