package com.lc33.tokenvault.domain.repo

import com.lc33.tokenvault.domain.LoginMethod
import com.lc33.tokenvault.domain.model.ProviderAccount
import kotlinx.coroutines.flow.Flow

/**
 * 平台账号。
 *
 * 明文只在 [add] 的参数上出现，且是 [CharArray]（红线 1）：用户名与密码与密钥同等对待，
 * 同一套加密、遮蔽、脱敏策略（红线 21）。
 *
 * 这一版有 [add]、[observeByProvider]、[revealUsername] 与 [revealPassword]——它在 M4 文本
 * 导入落地时建立（导入需要写账号），回遮（展开看明文）在 2026-09-06 补齐以对齐红线 21。
 * **编辑 / 删除还没做**：账号目前能看（遮蔽串 + 展开明文）、能复制，但改不了、删不掉，
 * 将来补的时候再按红线 21 与密钥同等对待（30 秒回遮、剪贴板清除同一条路径）。
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
        loginMethods: Set<LoginMethod> = emptySet(),
    ): Long

    /**
     * 解出这条账号的**用户名明文**（遮蔽串要现算，红线 21）。返回的 [CharArray] 归调用方擦。
     * null 表示这条账号没记用户名（只记了密码）。
     */
    suspend fun revealUsername(id: Long): CharArray?

    /**
     * 解出这条账号的**密码明文**（展开看明文时现算，红线 21 的「回遮」）。返回的
     * [CharArray] 归调用方擦。null 表示这条账号没记密码（只记了用户名）。
     */
    suspend fun revealPassword(id: Long): CharArray?

    /** 只改登录方式。这是明文元数据，不需要借 DEK。 */
    suspend fun setLoginMethods(id: Long, methods: Set<LoginMethod>)
}
