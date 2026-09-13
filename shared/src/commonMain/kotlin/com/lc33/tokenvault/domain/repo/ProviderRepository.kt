package com.lc33.tokenvault.domain.repo

import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.domain.model.ProviderSummary
import kotlinx.coroutines.flow.Flow

/** 供应商合集仓库。v3 起这里不再保存任何请求配置；那些都在 ApiKeyRepository / KeySettings 上。 */
interface ProviderRepository {
    fun observeSummaries(): Flow<List<ProviderSummary>>
    fun observeProvider(id: Long): Flow<Provider?>
    suspend fun find(id: Long): Provider?

    /** 新增或保存合集信息。 */
    suspend fun save(provider: Provider): Long

    /**
     * 删除供应商会级联删除其 Key、KeySettings、账号与模型。
     *
     * 返回的句柄持有删除前的完整快照，可在提示消失前把这一供应商连同它下面的一切
     * 按原主键写回（见 [UndoableDeletion]）。
     */
    suspend fun delete(id: Long): UndoableDeletion?

    suspend fun setGroup(ids: List<Long>, groupId: Long?)

    /** 手动排序落库。只写 `sortOrder`，不改变置顶标记。 */
    suspend fun reorder(idsInOrder: List<Long>)

    /** 保存官网连通性检测结果。只说明网站可达，不推断 API Key 是否有效。 */
    suspend fun updateWebsiteStatus(
        id: Long,
        latencyMs: Long?,
        checkedAt: Long,
        error: String?,
    )
}
