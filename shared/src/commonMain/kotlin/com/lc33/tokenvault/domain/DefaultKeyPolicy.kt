package com.lc33.tokenvault.domain

/**
 * "每个供应商恰好一张默认 Key"这条不变量的**决策部分**（§6.3、红线 6.3）。
 *
 * 抽成纯函数是因为这条不变量的代价很隐蔽：少了"删除默认那张后自动顶上"这一条，
 * `deepseek` / `openrouter` / `siliconflow` / `moonshot` 四个余额适配器会在"删了一张 Key"
 * 之后**静默查不出余额**——它们复用供应商的默认 API 密钥，而默认那张没了之后
 * 谁都不会想到去看这里。数据库层面的部分唯一索引只能保证"不超过一张"，
 * 保证不了"至少一张"，所以这一段必须有代码 + 有单测。
 *
 * 只做决策不做写入：写入是 DAO 在 `@Transaction` 里的事，而决策规则值得被单独测。
 */
object DefaultKeyPolicy {

    /** 一张 Key 在这条规则里需要被看到的那几个字段。 */
    data class Candidate(
        val id: Long,
        val enabled: Boolean,
        val sortOrder: Int,
        val isDefault: Boolean,
    )

    /**
     * 新增一张之后，谁该是默认。
     *
     * 规则：**已经有默认的就不动**（不要因为用户加了一张就悄悄换掉他选的那张），
     * 一张都没有默认时选新加的那张。
     */
    fun pickAfterInsert(existing: List<Candidate>, insertedId: Long): Long? {
        val current = existing.firstOrNull { it.isDefault && it.enabled }
        if (current != null) return current.id
        return insertedId
    }

    /**
     * 删掉 [removedId] 之后，谁该顶上。
     *
     * 规则：**`sortOrder` 最小的启用 Key**（同 `sortOrder` 时取 id 小的，保证结果稳定）。
     * 删的不是默认那张时不换人。
     *
     * @return 新的默认 Key id；null 表示一张启用的都没剩下——那时供应商就是"没有可用密钥"，
     *   这是合法状态，不该硬塞一张停用的进去。
     */
    fun pickAfterRemoval(remaining: List<Candidate>, removedWasDefault: Boolean): Long? {
        if (!removedWasDefault) return remaining.firstOrNull { it.isDefault && it.enabled }?.id
        return remaining
            .filter { it.enabled }
            .minWithOrNull(compareBy({ it.sortOrder }, { it.id }))
            ?.id
    }

    /**
     * 停用一张之后，谁该顶上。
     *
     * 停用与删除在这条规则上**必须一致**：否则"停用默认那张"会留下一个默认但不启用的 Key，
     * 而余额适配器拿到它照样查不出东西——比删掉更难发现，因为那一行还在列表里。
     */
    fun pickAfterDisable(all: List<Candidate>, disabledId: Long): Long? {
        val disabled = all.firstOrNull { it.id == disabledId } ?: return null
        val remaining = all.filter { it.id != disabledId }
        return pickAfterRemoval(remaining, removedWasDefault = disabled.isDefault)
    }
}
