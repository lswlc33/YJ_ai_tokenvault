package com.lc33.tokenvault.catalog

import com.lc33.tokenvault.domain.ModelChangeKind
import com.lc33.tokenvault.domain.model.ModelChange
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** 一条净变化：某个模型在某家站点的一次上新 / 下架，落在哪一刻。 */
data class ModelChangeEntry(
    val modelId: String,
    val at: Long,
)

/**
 * 一家站点在这个窗口内的净变化。
 *
 * [added] 与 [removed] 各自按时间倒序（最近在先）。两个列表**互斥**：同一个 modelId 只会在
 * 其中一个里出现，见 [ModelChangeSummary] 的第 2 条规则。
 */
data class ProviderModelChanges(
    val providerId: Long,
    val added: List<ModelChangeEntry>,
    val removed: List<ModelChangeEntry>,
) {
    /** 这家一共动了多少个模型。排次序用它。 */
    val total: Int get() = added.size + removed.size
}

/**
 * 把上下架流水折成"每家站点新增了什么、下架了什么"。**纯函数、平台无关、单独测**。
 *
 * 三条规则，每条都在挡一类"页面上说了假话"：
 *
 * 1. **只看窗口内的**事件。窗口外的变化这一轮不念（流水本身另有保留期兜底）。
 * 2. **同一个模型在同一家站点只算一次，按最后一次事件定性**。上游今天上、明天下、后天又上，
 *    流水里是三行；界面上列三次同一种子模型，读起来像三家站点各上了一个新模型。
 *    同毫秒并列时按主键比先后（[ModelChange.id] 自增，写序即发生序）。
 * 3. **与现状对账**，对不上的整条不念。判据是 `models` 这张当前态表：
 *    - 最后一次是"上新"而库里已经没有这行 → 不念。多半是用户自己把那条模型删了，或者撤销
 *      恢复把它带回了旧状态；站点那边并没有"又不给了"。
 *    - 最后一次是"下架"而库里还有这行 → 不念。它又回来了（换了协议重建、或另一把 Key 的
 *      列表里还在），念出来就是"这家不给了"，而用户点开模型列表看得见它。
 *    两个方向都宁可少说，不要断言一件现在不成立的事。
 *
 * 规则 3 依赖现表，所以这个函数的输入比余额趋势那条多一份 `liveModelIdsByProvider`；
 * 这份数据本来就在界面上（模型页要读），这里只是接进来而不是自己去查库。
 */
object ModelChangeSummary {

    /**
     * @param changes 全部上下架流水，顺序不限（内部按 [ModelChange.at] 与 id 排）。
     * @param liveModelIdsByProvider providerId → 这家此刻还认的 modelId 集合（`models` 表投影）。
     * @param rangeDays 窗口天数（7 / 30 / 90），今天算 1 天。
     * @param now 当前墙上时间（注入而非读时钟，与余额趋势那条聚合函数同一条约定）。
     * @param zone 分桶按本地日历日，与余额趋势的"近 N 天"同一个口径。
     */
    fun summarize(
        changes: List<ModelChange>,
        liveModelIdsByProvider: Map<Long, Set<String>>,
        rangeDays: Int,
        now: Long,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): List<ProviderModelChanges> {
        if (rangeDays <= 0 || changes.isEmpty()) return emptyList()

        val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
        val windowStartDay = today.plus(-(rangeDays - 1).toLong(), DateTimeUnit.DAY)
        val windowStart = windowStartDay.atStartOfDayIn(zone).toEpochMilliseconds()

        // 按 (站点, 模型) 取窗口内的最后一次事件：`changes` 已按 (at, id) 升序遍历，
        // 后写的覆盖先写的，所以留下的就是最后一次。
        val lastByModel = LinkedHashMap<Pair<Long, String>, ModelChange>()
        for (change in changes.asSequence().sortedWith(compareBy({ it.at }, { it.id }))) {
            if (change.at < windowStart) continue
            lastByModel[change.providerId to change.modelId] = change
        }

        val added = LinkedHashMap<Long, MutableList<ModelChangeEntry>>()
        val removed = LinkedHashMap<Long, MutableList<ModelChangeEntry>>()
        for (change in lastByModel.values) {
            val providerId = change.providerId
            val stillListed = change.modelId in liveModelIdsByProvider[providerId].orEmpty()
            // 与现状对账（规则 3）：说反了的整条不念，宁可这一家今天少列一行。
            val keep = when (change.kind) {
                ModelChangeKind.ADDED -> stillListed
                ModelChangeKind.REMOVED -> !stillListed
            }
            if (!keep) continue
            val bucket = if (change.kind == ModelChangeKind.ADDED) added else removed
            bucket.getOrPut(providerId) { mutableListOf() } += ModelChangeEntry(change.modelId, change.at)
        }

        val providerIds = (added.keys + removed.keys).toSet()
        return providerIds
            .map { providerId ->
                ProviderModelChanges(
                    providerId = providerId,
                    added = added[providerId].orEmpty().sortedWith(compareByDescending<ModelChangeEntry> { it.at }),
                    removed = removed[providerId].orEmpty().sortedWith(compareByDescending<ModelChangeEntry> { it.at }),
                )
            }
            // 动得多的站点排在前面；一样多时按 id 稳定排，免得每次刷新换个次序。
            .sortedWith(compareByDescending<ProviderModelChanges> { it.total }.thenBy { it.providerId })
    }
}
