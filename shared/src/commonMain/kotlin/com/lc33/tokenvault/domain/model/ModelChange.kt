package com.lc33.tokenvault.domain.model

import com.lc33.tokenvault.domain.ModelChangeKind

/**
 * 一条模型上下架事件（`model_changes` 的纯 Kotlin 投影）。
 *
 * 与 [AiModel] 分工不同：[AiModel] 是「这家站点此刻认哪些模型」的当前态一行，会被下一轮
 * 合并改掉甚至删掉；[ModelChange] 是「哪一轮发生了什么变化」的流水，写了就不再改。
 * 「模型变化」页读的是这条，当前态只用来对账（见 `catalog/ModelChangeSummary`）。
 *
 * @param id 库内主键。同一毫秒内的先后按它排（[at] 相同的两条事件比大小要用得到）。
 * @param keyId 哪把 Key 的列表里看到的，可为空且不挂外键（理由见实体注释）。
 * @param protocol 这一轮拉的哪个协议的列表。展示用不到，但排查"为什么这家反复上下架"要用。
 */
data class ModelChange(
    val id: Long = 0,
    val providerId: Long,
    val keyId: Long? = null,
    val modelId: String,
    val protocol: String,
    val kind: ModelChangeKind,
    val at: Long,
)
