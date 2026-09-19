package com.lc33.tokenvault.domain.repo

import com.lc33.tokenvault.domain.model.WebDavConfig
import com.lc33.tokenvault.domain.model.WebDavCredentials
import kotlinx.coroutines.flow.Flow

/**
 * WebDAV 配置。普通设置走 `app_settings.value`；用户名与密码走 `valueBlob`，
 * 由数据层用字段级 AES-GCM 封起来。
 */
interface WebDavSettingsRepository {

    fun observeConfig(): Flow<WebDavConfig>

    /**
     * 保存非秘密配置。凭据传 null 表示保留原值；传非空 CharArray 才会覆盖。
     * 这样改远程目录不需要重新输一遍密码。
     *
     * 设置弹层会把已存的凭据**回填**给你看（[credentials]），所以"这一格留空"现在的意思
     * 是"这一格不改"，不是"把它清空"——想真的删掉凭据得走清库/恢复，别指望留空。
     */
    suspend fun saveConfig(config: WebDavConfig, username: CharArray?, password: CharArray?)

    /** 解出一次完整凭据。调用方负责 zeroize。 */
    suspend fun credentials(): WebDavCredentials
}