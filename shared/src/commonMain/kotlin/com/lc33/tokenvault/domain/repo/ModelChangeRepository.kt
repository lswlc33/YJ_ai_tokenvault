package com.lc33.tokenvault.domain.repo

import com.lc33.tokenvault.domain.model.ModelChange
import kotlinx.coroutines.flow.Flow

/**
 * 模型上下架流水。「模型变化」页的唯一数据源。
 *
 * 为什么需要一张专门的流水表：`models` 只有当前态，而上游下架一个模型时合并层是硬删
 * （红线 30）——删完就查不出"它曾经在"。余额趋势能从 `balance_history` 反推，这件事不能。
 *
 * 写入口只有一个 [record]，调用点是 `RoomModelRepository.applyDiscovered`（探测合并的
 * 那个事务里）。**用户手动增删模型不走这里**：那改变的是"我的清单"，不是"站点上了什么"。
 */
interface ModelChangeRepository {

    /** 整表按时间升序。窗口过滤、按站点分组、与当前态对账都在纯函数里做。 */
    fun observeAll(): Flow<List<ModelChange>>

    /**
     * 成批记事件。
     *
     * **必须在外层事务里调用**（[com.lc33.tokenvault.domain.repo.TransactionRunner] 的
     * `inTransaction` 块内）：这一批事件说的是"这一轮合并改了什么"，与 `models` 那些行的
     * 插入删除是同一件事的两面，分成两个事务就会出现"行删了而流水没记上"的中间态。
     * 这里不再自己开事务，也不改 [ModelChange.at]——时刻由调用方那一轮的 stamp 决定。
     */
    suspend fun record(changes: List<ModelChange>)

    /** 删掉 [cutoff] 之前的流水（`LogMaintenance` 调用）。 */
    suspend fun trimOlderThan(cutoff: Long)

    /** 条数上限（`LogMaintenance` 调用）：一张有几百个模型的 Key 反复上下架时兜住这张表。 */
    suspend fun trimToCount(max: Int)
}
