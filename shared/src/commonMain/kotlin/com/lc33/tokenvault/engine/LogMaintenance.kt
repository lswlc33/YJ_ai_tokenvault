package com.lc33.tokenvault.engine

import com.lc33.tokenvault.domain.repo.AuditLogRepository
import com.lc33.tokenvault.domain.repo.ProbeRunRepository
import com.lc33.tokenvault.domain.repo.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * 日志与探测轮次的保留维护：两条上限各管各的。
 *
 * 1. **天数**（用户设置里的保留期，`LogRetention`）：只作用在日志上——"永久"就是真不按时间删。
 * 2. **条数**（写死的上限）：日志与 `probe_runs` 都要。条数上限存在的原因是那三列报文
 *    单条就能到 8KB，而"保留 90 天"在故障循环（每次都记一条完整报文）面前根本兜不住；
 *    轮次表更直接：探测一次就长一行，明细页却只看最近一轮。
 *
 * 条数上限按时间无关的口径裁（同毫秒并列时用主键兜底），所以本机时钟被改过也不会
 * 把历史一次清空。
 */
class LogMaintenance(
    private val settings: SettingsRepository,
    private val audit: AuditLogRepository,
    private val probeRuns: ProbeRunRepository,
    private val now: () -> Long,
) {
    suspend fun run() {
        val retention = settings.observeLogRetention().first()
        retention.days?.let { days -> audit.trimOlderThan(now() - days * DAY_MILLIS) }
        audit.trimToCount(MAX_AUDIT_ROWS)
        probeRuns.trimToCount(ProbeRunRepository.MAX_RUNS_KEPT)
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L

        /** 日志条数上限。够翻几天明细，又不至于让报文列把库撑大。 */
        const val MAX_AUDIT_ROWS = 2000
    }
}
