package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.crypto.FieldAad
import com.lc33.tokenvault.crypto.toUtf8
import com.lc33.tokenvault.crypto.utf8Chars
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.data.dao.AppSettingDao
import com.lc33.tokenvault.data.entity.AppSettingEntity
import com.lc33.tokenvault.domain.model.WebDavConfig
import com.lc33.tokenvault.domain.model.WebDavCredentials
import com.lc33.tokenvault.domain.repo.WebDavSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * WebDAV 配置的 Room 实现。
 *
 * 地址与远程目录不是秘密，走 TEXT；用户名与密码分别走 `valueBlob`，并用
 * `FieldAad.ofSetting` 绑定键与列，防止两段密文互换。
 */
class RoomWebDavSettingsRepository constructor(
    private val dao: AppSettingDao,
    private val cipher: FieldCipher,
) : WebDavSettingsRepository {

    override fun observeConfig(): Flow<WebDavConfig> = dao.observeAll()
        .map { rows ->
            WebDavConfig(
                url = rows.firstOrNull { it.key == KEY_URL }?.value.orEmpty(),
                remoteDirectory = rows.firstOrNull { it.key == KEY_DIRECTORY }?.value
                    ?: WebDavConfig.DEFAULT_REMOTE_DIRECTORY,
                allowInsecure = rows.firstOrNull { it.key == KEY_ALLOW_INSECURE }?.value == "true",
                hasCredentials = rows.any { it.key == KEY_USERNAME && it.valueBlob != null } &&
                    rows.any { it.key == KEY_PASSWORD && it.valueBlob != null },
            )
        }
        .distinctUntilChanged()

    override suspend fun saveConfig(
        config: WebDavConfig,
        username: CharArray?,
        password: CharArray?,
    ) {
        dao.put(AppSettingEntity(key = KEY_URL, value = config.url.trim()))
        dao.put(AppSettingEntity(key = KEY_DIRECTORY, value = normalizeDirectory(config.remoteDirectory)))
        dao.put(AppSettingEntity(key = KEY_ALLOW_INSECURE, value = config.allowInsecure.toString()))

        username?.let { saveCredential(KEY_USERNAME, it) }
        password?.let { saveCredential(KEY_PASSWORD, it) }
    }

    override suspend fun credentials(): WebDavCredentials {
        val usernameBlob = dao.find(KEY_USERNAME)?.valueBlob
            ?: throw IllegalStateException("WebDAV username is not configured")
        val passwordBlob = dao.find(KEY_PASSWORD)?.valueBlob
            ?: throw IllegalStateException("WebDAV password is not configured")

        var usernamePlain: ByteArray? = null
        var passwordPlain: ByteArray? = null
        try {
            usernamePlain = cipher.open(usernameBlob, FieldAad.ofSetting(KEY_USERNAME, COLUMN_BLOB))
            passwordPlain = cipher.open(passwordBlob, FieldAad.ofSetting(KEY_PASSWORD, COLUMN_BLOB))
            return WebDavCredentials(
                username = usernamePlain.utf8Chars(),
                password = passwordPlain.utf8Chars(),
            )
        } finally {
            usernamePlain?.zeroize()
            passwordPlain?.zeroize()
        }
    }

    private suspend fun saveCredential(key: String, value: CharArray) {
        if (value.isEmpty()) {
            dao.remove(key)
            return
        }
        val plain = value.toUtf8()
        try {
            dao.put(
                AppSettingEntity(
                    key = key,
                    valueBlob = cipher.seal(plain, FieldAad.ofSetting(key, COLUMN_BLOB)),
                ),
            )
        } finally {
            plain.zeroize()
        }
    }

    private fun normalizeDirectory(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return WebDavConfig.DEFAULT_REMOTE_DIRECTORY
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }

    private companion object {
        const val KEY_URL = "webdav.url"
        const val KEY_DIRECTORY = "webdav.directory"
        const val KEY_ALLOW_INSECURE = "webdav.allowInsecure"
        const val KEY_USERNAME = "webdav.username"
        const val KEY_PASSWORD = "webdav.password"
        const val COLUMN_BLOB = "valueBlob"
    }
}