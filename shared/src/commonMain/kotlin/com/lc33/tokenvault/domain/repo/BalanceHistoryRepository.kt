package com.lc33.tokenvault.domain.repo

import com.lc33.tokenvault.domain.model.BalanceSample
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * 余额历史仓库。余额趋势报告的数据源，与 `api_keys` 上那份「最新快照」分开。
 *
 * 只有一条写路径 [record]：由 [com.lc33.tokenvault.engine.BalanceEngine] 在每次成功刷到
 * 余额后调用，内部按「与上一条相同就不写」去重（见 [com.lc33.tokenvault.balance.shouldRecordSample]），
 * 所以表按变化点增长，而不是每探测一次长一行。
 */
interface BalanceHistoryRepository {

    /** 整表按时间升序。报告 ViewModel 订阅它，聚合交给纯函数。 */
    fun observeAll(): Flow<List<BalanceSample>>

    /**
     * 记一条余额历史（去重后可能不落库）。
     *
     * @param providerId 这把 Key 属于哪家（报告按供应商分线求和）。
     * @param keyId 哪把 Key。
     * @param snapshot 这次查询的结果；`amount == null`（失败）或与上一条相同都不写。
     */
    suspend fun record(providerId: Long, keyId: Long, snapshot: BalanceSnapshot)

    /** 删掉 [cutoff] 之前的样本（天数上限，`LogMaintenance` 调用）。 */
    suspend fun trimOlderThan(cutoff: Long)
}
