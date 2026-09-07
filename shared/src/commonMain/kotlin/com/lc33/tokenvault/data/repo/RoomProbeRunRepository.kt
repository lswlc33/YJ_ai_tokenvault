package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.data.dao.ProbeRunDao
import com.lc33.tokenvault.data.entity.ProbeRunEntity
import com.lc33.tokenvault.domain.repo.ProbeRun
import com.lc33.tokenvault.domain.repo.ProbeRunRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 探测运行仓库的 Room 实现。
 *
 * 写路径（[insert] / [update]）把纯 Kotlin 的 [ProbeRun] 转成 Room 实体；读路径
 * （[observeLatest]）把 Room 实体转回 [ProbeRun]，不向外泄漏 Room 类型。
 */
class RoomProbeRunRepository constructor(
    private val dao: ProbeRunDao,
) : ProbeRunRepository {

    override suspend fun insert(run: ProbeRun): Long = dao.insert(run.toEntity())

    override suspend fun update(run: ProbeRun) = dao.update(run.toEntity())

    override fun observeLatest(): Flow<ProbeRun?> = dao.observeLatest().map { it?.toDomain() }

    override suspend fun clear() = dao.clear()

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

    private fun ProbeRunEntity.toDomain() = ProbeRun(
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
