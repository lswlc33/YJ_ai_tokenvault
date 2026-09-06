package com.lc33.tokenvault.domain.repo

import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.AiModel
import kotlinx.coroutines.flow.Flow

/**
 * 模型。
 *
 * 这一版只有 [add] 与 [observeByProvider]——它是在 M4 文本导入落地时才建立的。
 * 三路合并与元数据匹配**不进这个接口**：前者是 `probe/ModelMerger` 纯函数（M5，写入侧尚未接，
 * 见 new_plan.md §4.1 步骤 6），后者是 `catalog/ModelCatalogMatcher` 纯函数（M8），
 * 落库都走各自的 DAO，不经过这里。
 */
interface ModelRepository {

    fun observeByProvider(providerId: Long): Flow<List<AiModel>>

    /**
     * 新增一条手动录入的模型（`source = MANUAL`，`discoveredVia = null`）。
     *
     * @param needsReview 疑似显示名而非真实 id（`DeepSeek V4 Pro` 这种，§11.2）。
     * @return 新行 id。
     */
    suspend fun add(
        providerId: Long,
        modelId: String,
        protocol: Protocol,
        needsReview: Boolean = false,
    ): Long
}
