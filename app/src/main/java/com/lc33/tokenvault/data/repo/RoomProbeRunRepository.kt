package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.data.dao.ProbeRunDao
import com.lc33.tokenvault.data.entity.ProbeRunEntity
import com.lc33.tokenvault.domain.repo.ProbeRun
import com.lc33.tokenvault.domain.repo.ProbeRunRepository

/**
 * 探测运行写入仓库的 Room 实现。
 *
 * 只包 [ProbeRunDao] 的两条写路径（[insert] / [update]），把纯 Kotlin 的 [ProbeRun] 转成
 * Room 实体。读路径（`observeLatest` 等）仍由 `ProbeRunDao` 直接服务，不在这个类里重复。
 */
class RoomProbeRunRepository constructor(
    private val dao: ProbeRunDao,
) : ProbeRunRepository {

    override suspend fun insert(run: ProbeRun): Long = dao.insert(run.toEntity())

    override suspend fun update(run: ProbeRun) = dao.update(run.toEntity())

    private fun ProbeRun.toEntity() = ProbeRunEntity(
        id = id,
        scope = scope,
        startedAt = startedAt,
        finishedAt = finishedAt,
        total = total,
        done = done,
        okCount = okCount,
        failCount = failCount,
        cancelled = cancelled,
    )
}
