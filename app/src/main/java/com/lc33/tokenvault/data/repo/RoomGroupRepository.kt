package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.data.dao.GroupDao
import com.lc33.tokenvault.data.mapper.toDomain
import com.lc33.tokenvault.data.mapper.toEntity
import com.lc33.tokenvault.domain.model.Group
import com.lc33.tokenvault.domain.repo.GroupRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 分组。这一张表里没有任何秘密，所以整个类不碰 DEK，锁定态也能读（§6.1 推论 2）。
 */
class RoomGroupRepository constructor(
    private val dao: GroupDao,
) : GroupRepository {

    override fun observeGroups(): Flow<List<Group>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /**
     * 新建。`sortOrder` 放在末尾（用当前条数），而不是都用 0——都用 0 时排序退化成按 id，
     * 于是"拖动排序"做出来之后老数据的顺序会突然全变。
     */
    override suspend fun add(name: String): Long {
        val next = dao.findAll().size
        return dao.insert(Group(name = name.trim(), sortOrder = next).toEntity())
    }

    override suspend fun rename(id: Long, name: String) {
        val existing = dao.findById(id) ?: return
        dao.update(existing.copy(name = name.trim()))
    }

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun reorder(idsInOrder: List<Long>) = dao.reorder(idsInOrder)
}
