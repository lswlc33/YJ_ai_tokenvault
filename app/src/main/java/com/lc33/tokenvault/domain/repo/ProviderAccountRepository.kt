package com.lc33.tokenvault.domain.repo

import com.lc33.tokenvault.domain.model.ProviderAccount
import kotlinx.coroutines.flow.Flow

/**
 * 平台账号。
 *
 * 明文只在 [add] 的参数上出现，且是 [CharArray]（红线 1）：用户名与密码与密钥同等对待，
 * 同一套加密、遮蔽、脱敏策略（红线 21）。
 *
 * 这一版只有 [add] 与 [observeByProvider]——它是在 M4 文本导入落地时才建立的，
 * 因为导入需要写账号。编辑 / 删除 / 回遮在 M6 补齐。
 */
interface ProviderAccountRepository {

    fun observeByProvider(providerId: Long): Flow<List<ProviderAccount>>

    /**
     * 新增一条平台账号。
     *
     * @param username 明文用户名；null 表示"只记了密码没记用户名"（§11.2：只记一半合法）。
     * @param password 明文密码；null 同理。
     * @param loginUrl 空或 null 则落库时用供应商的 `websiteUrl`。
     * @return 新行 id。
     */
    suspend fun add(
        providerId: Long,
        label: String,
        username: CharArray?,
        password: CharArray?,
        loginUrl: String?,
    ): Long
}
