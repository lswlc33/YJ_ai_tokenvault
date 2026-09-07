package com.lc33.tokenvault.domain.repo

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
    val cancelled: Boolean = false,
)

/**
 * 探测运行的写入仓库。
 *
 * 只暴露探测引擎需要的两条写路径（[insert] / [update]）。读路径（如"上次探测摘要"用的
 * `observeLatest`）仍由 data 层的 `ProbeRunDao` 直接服务，不在这里重复抽象。
 */
interface ProbeRunRepository {

    /** 开一轮，返回新行 id。 */
    suspend fun insert(run: ProbeRun): Long

    /** 更新一轮（结束时回填计数与时间）。 */
    suspend fun update(run: ProbeRun)
}
