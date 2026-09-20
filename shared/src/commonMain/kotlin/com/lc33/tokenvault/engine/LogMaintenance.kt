package com.lc33.tokenvault.engine

import com.lc33.tokenvault.domain.repo.AuditLogRepository
import com.lc33.tokenvault.domain.repo.BalanceHistoryRepository
import com.lc33.tokenvault.domain.repo.ProbeRunRepository
import com.lc33.tokenvault.domain.repo.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * 日志、探测轮次与余额历史的保留维护：几条上限各管各的。
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
    private val now: () -> Long,
) {
    suspend fun run() {
        val retention = settings.observeLogRetention().first()
        retention.days?.let { days -> audit.trimOlderThan(now() - days * DAY_MILLIS) }
        audit.trimToCount(MAX_AUDIT_ROWS)
        probeRuns.trimToCount(ProbeRunRepository.MAX_RUNS_KEPT)
        balanceHistory.trimOlderThan(now() - BALANCE_HISTORY_RETENTION_DAYS * DAY_MILLIS)
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L

        /** 日志条数上限。够翻几天明细，又不至于让报文列把库撑大。 */
        const val MAX_AUDIT_ROWS = 2000

        /** 余额历史保留天数。约一年，足够看季度/年度趋势，又不会让表无限长。 */
        const val BALANCE_HISTORY_RETENTION_DAYS = 365L
    }
}
