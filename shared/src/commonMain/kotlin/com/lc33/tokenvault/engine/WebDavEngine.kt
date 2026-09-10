package com.lc33.tokenvault.engine

import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.model.WebDavConfig
import com.lc33.tokenvault.domain.model.WebDavCredentials
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import com.lc33.tokenvault.domain.repo.WebDavSettingsRepository
import com.lc33.tokenvault.net.WebDavClient
import kotlinx.coroutines.flow.first

/**
 * WebDAV 备份编排。
 *
 * 客户端只管四个 HTTP 动词；这里负责取配置、解凭据、调用备份引擎、轮转远端文件，
 * 并把失败写进 audit_log。自动备份刻意不在这里实现：没有 Worker 消费方时画一个
 * “每天备份”的开关就是撑谎；当前先提供手动上传与恢复。
 */
class WebDavEngine constructor(
    private val settings: WebDavSettingsRepository,
    private val backup: BackupEngine,
    private val client: WebDavClient,
    private val audit: AuditLogRepository,
    private val now: () -> Long,
) {

    suspend fun listRemoteBackups(): List<String> = withAudit("list") {
        withConnection { config, credentials -> client.listBackups(config, credentials) }
    }

    suspend fun upload(password: CharArray, keepCount: Int = DEFAULT_KEEP_COUNT): WebDavUploadResult =
        withAudit("upload") {
            val fileName = backupFileName(now())
            withConnection { config, credentials ->
                val bytes = backup.export(password)
                client.put(config, credentials, fileName, bytes)

                val names = runCatching { client.listBackups(config, credentials) }
                    .onFailure {
                        audit.record(
                            level = LogLevel.WARN,
                            category = LogCategory.BACKUP,
                            message = "webdav rotate failed",
                            detail = it.message,
                        )
                    }
                    .getOrDefault(emptyList())
                val all = (names + fileName).distinct().filter { it.startsWith(BACKUP_PREFIX) }.sorted()
                val obsolete = all.dropLast(keepCount.coerceAtLeast(1))
                obsolete.forEach { old -> client.delete(config, credentials, old) }
                WebDavUploadResult(fileName = fileName, prunedCount = obsolete.size)
            }
        }

    suspend fun restoreLatest(password: CharArray, mode: RestoreMode): RestoreResult = withAudit("restore") {
        withConnection { config, credentials ->
            val latest = client.listBackups(config, credentials).maxOrNull()
                ?: throw com.lc33.tokenvault.net.WebDavNotFoundException("GET")
            val bytes = client.get(config, credentials, latest)
            backup.restore(bytes, password, mode)
        }
    }

    private suspend fun <T> withConnection(
        block: suspend (WebDavConfig, WebDavCredentials) -> T,
    ): T {
        val config = settings.observeConfig().first()
        if (!config.isReady) throw IllegalStateException("WebDAV is not configured")
        val credentials = settings.credentials()
        try {
            return block(config, credentials)
        } finally {
            credentials.zeroize()
        }
    }

    private suspend fun <T> withAudit(action: String, block: suspend () -> T): T = try {
        block().also { result ->
            audit.record(
                level = LogLevel.INFO,
                category = LogCategory.BACKUP,
                message = "webdav $action succeeded",
                detail = when (result) {
                    is WebDavUploadResult -> "file=${result.fileName} pruned=${result.prunedCount}"
                    is RestoreResult -> "providers=${result.importedProviders}"
                    is List<*> -> "count=${result.size}"
                    else -> null
                },
            )
        }
    } catch (t: Throwable) {
        audit.record(
            level = LogLevel.ERROR,
            category = LogCategory.BACKUP,
            message = "webdav $action failed",
            detail = t.message,
        )
        throw t
    }

    companion object {
        const val DEFAULT_KEEP_COUNT = 10

        private const val BACKUP_PREFIX = "yuanji-backup-"

        fun backupFileName(epochMillis: Long): String =
            BACKUP_PREFIX + "${epochMillis / 1000}.yjv"
    }
}

data class WebDavUploadResult(
    val fileName: String,
    val prunedCount: Int,
)