package com.lc33.tokenvault.domain.repo

import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.AiModel
import kotlinx.coroutines.flow.Flow

/**
 * 模型。
 *
 * 这一版只有 [add] 与 [observeByProvider]——它是在 M4 文本导入落地时才建立的。
 * 三路合并（M5）、元数据匹配（M8）那些方法到对应里程碑再加。
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
