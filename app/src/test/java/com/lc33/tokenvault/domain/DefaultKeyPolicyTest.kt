package com.lc33.tokenvault.domain

import com.lc33.tokenvault.domain.DefaultKeyPolicy.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 默认 Key 不变量（§14.3 测试 15）。
 *
 * 这条规则坏了不会报错、测试也可能通过，但 `deepseek` / `openrouter` / `siliconflow` /
 * `moonshot` 四个余额适配器会在"删了一张 Key"之后**静默查不出余额**——它们复用供应商的
 * 默认 API 密钥，而默认那张没了之后谁都不会想到去看这里。
 */
class DefaultKeyPolicyTest {

    private fun key(id: Long, sortOrder: Int, isDefault: Boolean = false, enabled: Boolean = true) =
        Candidate(id = id, enabled = enabled, sortOrder = sortOrder, isDefault = isDefault)

    // ---------------------------------------------------------------- 新增

    @Test
    fun `第一张自动成为默认`() {
        assertEquals(7L, DefaultKeyPolicy.pickAfterInsert(emptyList(), insertedId = 7L))
    }

    @Test
    fun `已经有默认时新增不换人`() {
        val existing = listOf(key(1, 0, isDefault = true))
        // 用户加了一张备用 Key，不该悄悄把默认换成新的那张
        assertEquals(1L, DefaultKeyPolicy.pickAfterInsert(existing, insertedId = 2L))
    }

    @Test
    fun `原来的默认已停用时新增的那张顶上`() {
        val existing = listOf(key(1, 0, isDefault = true, enabled = false))
        assertEquals(2L, DefaultKeyPolicy.pickAfterInsert(existing, insertedId = 2L))
    }

    // ---------------------------------------------------------------- 删除

    @Test
    fun `删掉默认那张后 sortOrder 最小的启用 Key 顶上`() {
        val remaining = listOf(key(3, 5), key(2, 1), key(4, 9))
        assertEquals(2L, DefaultKeyPolicy.pickAfterRemoval(remaining, removedWasDefault = true))
    }

    @Test
    fun `同 sortOrder 时取 id 小的、保证结果稳定`() {
        val remaining = listOf(key(9, 0), key(4, 0), key(6, 0))
        assertEquals(4L, DefaultKeyPolicy.pickAfterRemoval(remaining, removedWasDefault = true))
    }

    @Test
    fun `顶上时跳过停用的`() {
        val remaining = listOf(key(2, 0, enabled = false), key(3, 5))
        assertEquals(3L, DefaultKeyPolicy.pickAfterRemoval(remaining, removedWasDefault = true))
    }

    @Test
    fun `一张启用的都不剩时返回 null 而不是硬塞一张停用的`() {
        val remaining = listOf(key(2, 0, enabled = false))
        // "没有可用密钥"是合法状态，塞一张停用的进去会让余额适配器拿着它去查
        assertNull(DefaultKeyPolicy.pickAfterRemoval(remaining, removedWasDefault = true))
    }

    @Test
    fun `全空时返回 null`() {
        assertNull(DefaultKeyPolicy.pickAfterRemoval(emptyList(), removedWasDefault = true))
    }

    @Test
    fun `删的不是默认那张时不换人`() {
        val remaining = listOf(key(1, 9, isDefault = true), key(2, 0))
        // sortOrder 更小的那张不该趁机上位
        assertEquals(1L, DefaultKeyPolicy.pickAfterRemoval(remaining, removedWasDefault = false))
    }

    // ---------------------------------------------------------------- 停用

    @Test
    fun `停用默认那张与删除它的行为一致`() {
        val all = listOf(key(1, 0, isDefault = true), key(2, 3), key(3, 1))
        // 不一致的话会留下一个"默认但不启用"的 Key，比删掉更难发现——那一行还在列表里
        assertEquals(3L, DefaultKeyPolicy.pickAfterDisable(all, disabledId = 1L))
    }

    @Test
    fun `停用非默认那张不换人`() {
        val all = listOf(key(1, 0, isDefault = true), key(2, 3))
        assertEquals(1L, DefaultKeyPolicy.pickAfterDisable(all, disabledId = 2L))
    }

    @Test
    fun `停用一个不存在的 id 时返回 null`() {
        assertNull(DefaultKeyPolicy.pickAfterDisable(listOf(key(1, 0, isDefault = true)), 99L))
    }
}
