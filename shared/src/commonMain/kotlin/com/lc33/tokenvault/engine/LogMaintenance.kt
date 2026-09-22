package com.lc33.tokenvault.engine

import com.lc33.tokenvault.domain.repo.AuditLogRepository
import com.lc33.tokenvault.domain.repo.BalanceHistoryRepository
import com.lc33.tokenvault.domain.repo.ModelChangeRepository
import com.lc33.tokenvault.domain.repo.ProbeRunRepository
import com.lc33.tokenvault.domain.repo.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * 日志、探测轮次、余额历史与模型上下架流水的保留维护：几条上限各管各的。
 *
 * 1. **天数**（用户设置里的保留期，`LogRetention`）：只作用在日志上——"永久"就是真不按时间删。
 * 2. **条数**（写死的上限）：日志与 `probe_runs` 都要。条数上限存在的原因是那三列报文
 *    单条就能到 8KB，而"保留 90 天"在故障循环（每次都记一条完整报文）面前根本兜不住；
 *    轮次表更直接：探测一次就长一行，明细页却只看最近一轮。
 * 3. **余额历史按天数裁**（[BALANCE_HISTORY_RETENTION_DAYS]）：报告只看近段时间，更早的点
 *    画不进任何时间范围。它不像日志有用户可配的保留期，也不像报文那样单条巨大，所以用一个
 *    固定天数即可——去重之后本就按变化点增长，量级远小于日志。
 *
 * 条数上限按时间无关的口径裁（同毫秒并列时用主键兜底），所以本机时钟被改过也不会
 * 把历史一次清空。余额历史那条是按时间裁的：时钟被改到远未来会多删几天历史，
 * 但那是用户自己动的时钟，且只影响趋势图的起点，不涉及任何凭据。
 */
class LogMaintenance(
    private val settings: SettingsRepository,
    private val audit: AuditLogRepository,
    private val probeRuns: ProbeRunRepository,
    private val balanceHistory: BalanceHistoryRepository,
    private val modelChanges: ModelChangeRepository,
    private val now: () -> Long,
) {
    suspend fun run() {
        val retention = settings.observeLogRetention().first()
        retention.days?.let { days -> audit.trimOlderThan(now() - days * DAY_MILLIS) }
        audit.trimToCount(MAX_AUDIT_ROWS)
        probeRuns.trimToCount(ProbeRunRepository.MAX_RUNS_KEPT)
        balanceHistory.trimOlderThan(now() - BALANCE_HISTORY_RETENTION_DAYS * DAY_MILLIS)
        // 流水两道都上：按天裁是因为页面只看近 90 天，按条裁是因为一张有几百个模型的 Key
        // 反复上下架时，365 天这个天花板兜不住量（v11 回填本身就是几千行的量级）。
        modelChanges.trimOlderThan(now() - MODEL_CHANGE_RETENTION_DAYS * DAY_MILLIS)
        modelChanges.trimToCount(MAX_MODEL_CHANGE_ROWS)
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L

        /** 日志条数上限。够翻几天明细，又不至于让报文列把库撑大。 */
        const val MAX_AUDIT_ROWS = 2000

        /** 余额历史保留天数。约一年，足够看季度/年度趋势，又不会让表无限长。 */
        const val BALANCE_HISTORY_RETENTION_DAYS = 365L

        /** 模型上下架流水保留天数。页面最长看 90 天，留一年是为了"去年这时候上的新"还能查到。 */
        const val MODEL_CHANGE_RETENTION_DAYS = 365L

        /**
         * 上下架流水条数上限。
         *
         * 按最坏情况算：一把 Key 有 445 个模型，上游一天上下架两次就是近 900 行。5000 行
         * 读回来分组排序仍是毫秒级（页面只画不算），同时够装下 v11 那次回填 + 之后几十天。
         */
        const val MAX_MODEL_CHANGE_ROWS = 5000
    }
}
