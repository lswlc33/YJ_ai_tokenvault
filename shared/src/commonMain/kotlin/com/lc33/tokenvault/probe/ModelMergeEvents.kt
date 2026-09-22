package com.lc33.tokenvault.probe

import com.lc33.tokenvault.domain.ModelChangeKind
import com.lc33.tokenvault.domain.ModelSource
import com.lc33.tokenvault.domain.model.ModelChange

/**
 * 库里一行被这一轮删掉时要用的最小投影。
 *
 * 只带这四个量：[ModelMergeEvents] 判"这次删除算不算站点下架了它"用到这几样，多带整行
 * 就会让这个纯函数包依赖上 `data/entity`（那张表挂着 Room 注解），合并层的规则也就跟着
 * 和落库缠在一起了。
 */
data class DeletedModelRow(
    val modelId: String,
    val keyId: Long?,
    val protocol: String,
    val source: String,
)

/**
 * 一轮三路合并（[ModelMerger]）落地成"哪家站点上了什么、下了什么"。**纯函数、单独测**。
 *
 * 三条判据，每一条都对应一种"报错了比不报更糟"的情形：
 *
 * 1. **一轮列表里还在的不算下架**。[ModelMerger.merge] 的 `toDelete` 里混着两类行：真的
 *    从上游消失了，以及"模型还在、只是那条行躺错了协议，删旧行由本轮重建"（它的第四条
 *    规则）。后者每刷一次就删一次、插一次，照单记就是"这家天天下架同一个模型"。
 *    判据是本轮拉到的 id 集合，不是协议。
 * 2. **一张 Key 的第一轮整批抓取不算新增**。那一刻之前没有任何基线，"新增 445 个"说的
 *    其实是"我第一次看了这家的列表"——把首次接入念成上新，是把配置动作冒充站点动向。
 * 3. **只记发现行**。`source = manual` 的行合并层永不删（红线 13），这里再挡一道：
 *    万一将来合并规则放宽，用户手录的模型也不该变成"站点下架了它"。
 *
 * 新增按 [insertedModelIds] 记，[keyId] 取本轮作用域那把 Key；下架按各行自己的 `keyId`
 * 与协议记（那张行可能属于另一把 Key 被同协议清掉的情况，按本轮的 Key 记会指错地方）。
 */
object ModelMergeEvents {

    fun of(
        providerId: Long,
        keyId: Long?,
        protocol: String,
        insertedModelIds: List<String>,
        deletedRows: List<DeletedModelRow>,
        listedModelIds: Set<String>,
        firstRoundForKey: Boolean,
        at: Long,
    ): List<ModelChange> {
        val added = if (firstRoundForKey) {
            emptyList()
        } else {
            insertedModelIds.map { modelId ->
                ModelChange(
                    providerId = providerId,
                    keyId = keyId,
                    modelId = modelId,
                    protocol = protocol,
                    kind = ModelChangeKind.ADDED,
                    at = at,
                )
            }
        }
        val removed = deletedRows
            .filter { it.source == ModelSource.DISCOVERED.wireName && it.modelId !in listedModelIds }
            .map { row ->
                ModelChange(
                    providerId = providerId,
                    keyId = row.keyId,
                    modelId = row.modelId,
                    protocol = row.protocol,
                    kind = ModelChangeKind.REMOVED,
                    at = at,
                )
            }
        return added + removed
    }
}
