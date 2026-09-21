package com.lc33.tokenvault.domain.repo

import kotlinx.coroutines.flow.Flow

/**
 * 一轮探测的运行记录。
 *
 * 这是 `probe_runs` 表在纯 Kotlin 层的投影，字段与 `data/entity/ProbeRunEntity` 一一对应，
 * 供 [ProbeRunRepository] 读写。探测引擎（`engine/ProbeEngine`）只通过这个数据类落库，
 * 不直接碰 Room 实体。
 */
data class ProbeRun(
    val id: Long = 0,

    /** `all` | `provider:{id}` | `key:{id}` | `model:{id}`。 */
    val scope: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val total: Int = 0,
    val done: Int = 0,
    val okCount: Int = 0,
    val failCount: Int = 0,
    val providerTotal: Int = 0,
    val providerDone: Int = 0,
    val providerOk: Int = 0,
    val providerFail: Int = 0,
    val keyTotal: Int = 0,
    val keyDone: Int = 0,
    val keyOk: Int = 0,
    val keyFail: Int = 0,
    val cancelled: Boolean = false,
)

/**
 * 探测运行的写入仓库。
 *
 * 只暴露探测引擎需要的两条写路径（[insert] / [update]）与仪表盘/探测明细页需要的读路径
 * （[observeLatest]）。读路径返回纯 Kotlin 的 [ProbeRun]，不泄漏 Room 实体。
 */
interface ProbeRunRepository {

    /** 开一轮，返回新行 id。 */
    suspend fun insert(run: ProbeRun): Long

    /** 更新一轮（结束时回填计数与时间）。 */
    suspend fun update(run: ProbeRun)

    /** 最近一条**已结案**的轮次（"上次探测"摘要），一条都没有则为 null。半截的行不算。 */
    fun observeLatest(): Flow<ProbeRun?>

    /** 清空 `probe_runs`（数据页"清空探测结果"）。 */
    suspend fun clear()

    /**
     * 只保留最近 [keep] 轮。
     *
     * 轮次是探测一次就长一条的（一轮一批 Key，明细页只看最近一轮），所以这是唯一一处
     * "按条数淘汰"的清理：它跟时钟无关，用户把系统时间改到三年前也不会把历史全裁掉。
     */
    suspend fun trimToCount(keep: Int)

    companion object {
        /**
         * 轮次条数上限，[trimToCount] 的默认口径。
         *
         * 写在这里而不是各调用方自己抄一个数：`LogMaintenance`（启动时）与
         * `ProbeEngine.finishRun`（每轮收尾）裁的是同一张表，两处数字不一致就会
         * 互相"补裁"，看起来像偶发丢历史。
         */
        const val MAX_RUNS_KEPT = 50
    }
}
