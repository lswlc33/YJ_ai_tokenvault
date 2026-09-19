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

    /**
     * 删掉远端目录里的**指定那一份**备份。
     *
     * 轮转（[upload] 里那一段）只会淘汰超出保留份数的最旧文件，用户想单独清掉某一份
     * ——比如那次是拿错口令做出来的——以前只能整个目录手工去清。这里删的是传进来的
     * 那一条，不做"顺手删旧的"这类扩展：界面上点了哪一份，就该少哪一份。
     */
    suspend fun delete(fileName: String) {
        withAudit("delete") {
            withConnection { config, credentials -> client.delete(config, credentials, fileName) }
        }
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
            restoreAt(config, credentials, latest, password, mode)
        }
    }

    /**
     * 恢复**列表里被选中的那一份**。
     *
     * 与 [restoreLatest] 分开是有意的：一次 PROPFIND 只用来列，选中哪一条由界面决定，
     * 这里不再"取最大者"——用户点的是 9 月 3 日那份，就不该悄悄给他恢复昨天的。
     */
    suspend fun restore(fileName: String, password: CharArray, mode: RestoreMode): RestoreResult =
        withAudit("restore") {
            withConnection { config, credentials -> restoreAt(config, credentials, fileName, password, mode) }
        }

    private suspend fun restoreAt(
        config: WebDavConfig,
        credentials: WebDavCredentials,
        fileName: String,
        password: CharArray,
        mode: RestoreMode,
    ): RestoreResult = backup.restore(client.get(config, credentials, fileName), password, mode)

    private suspend fun <T> withConnection(
        block: suspend (WebDavConfig, WebDavCredentials) -> T,
    ): T {
        val config = settings.observeConfig().first()
        if (!config.isReady) throw WebDavNotConfiguredException()
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

        /**
         * 文件名里那份备份的落盘时刻（epoch 毫秒）；不是本应用命名的返回 null。
         *
         * 上限取 2100 年的秒数：再往上的话 `* 1000` 就溢出成负数，界面会画出一个
         * 1970 年前的日期。远端目录里被人塞进来的怪文件宁可退回显示文件名。
         */
        fun backupEpochMillis(fileName: String): Long? =
            fileName.removePrefix(BACKUP_PREFIX)
                .removeSuffix(WebDavClient.BACKUP_EXTENSION)
                .toLongOrNull()
                ?.takeIf { it in 1..MAX_EPOCH_SECONDS }
                ?.times(1000)

        private const val MAX_EPOCH_SECONDS = 4_102_444_800L

        fun backupFileName(epochMillis: Long): String =
            BACKUP_PREFIX + "${epochMillis / 1000}.yjv"
    }
}

data class WebDavUploadResult(
    val fileName: String,
    val prunedCount: Int,
)

/**
 * 没配好就调用了 WebDAV。
 *
 * 不用 `IllegalStateException`：调用方（`SyncViewModel`）要靠异常类型判断这次失败是
 * "远端没连上"还是"库里写坏了"，而 `IllegalStateException` 是 Room 那边也会抛的通用类型，
 * 混在一起就会把"其实只差一份凭据"报成"恢复把库弄坏了"。消息与原来保持一致，
 * 日志里历史条目和新条目才认得出是同一件事。
 */
class WebDavNotConfiguredException : Exception("WebDAV is not configured")