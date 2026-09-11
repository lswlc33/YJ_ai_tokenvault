package com.lc33.tokenvault.engine

import com.lc33.tokenvault.backup.BackupCodec
import com.lc33.tokenvault.backup.BackupCorruptException
import com.lc33.tokenvault.backup.BackupHeader
import com.lc33.tokenvault.backup.BackupKeySettings
import com.lc33.tokenvault.backup.BackupItemCounts
import com.lc33.tokenvault.backup.BackupPayload
import com.lc33.tokenvault.backup.BackupProvider
import com.lc33.tokenvault.backup.BackupStore
import com.lc33.tokenvault.backup.gunzip
import com.lc33.tokenvault.backup.gzip
import com.lc33.tokenvault.crypto.KdfParams
import com.lc33.tokenvault.crypto.RandomBytes
import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import kotlinx.serialization.json.Json

/**
 * 备份与恢复的编排（§12.1）。
 *
 * 职责边界刻意划得很窄：**只有导出与恢复的编排**，SAF 文件读写（`CreateDocument` /
 * `OpenDocument`）发生在 ViewModel 层——那个要碰 `ActivityResultContracts`，属于平台。
 * 数据访问与加解密（reveal 明文、两步写、字段级加密、AAD 绑定）全部收在 [BackupStore]，
 * 这里只做自然键 ref 匹配、去重判断、序列化 / 反序列化、gzip。
 *
 * 三条硬规矩（红线 27、28 与 §12.1）：
 *
 * 1. **跨表引用用自然键**：`provider.groupName` → 分组名，`provider.clientProfileKey` →
 *    `builtinKey` / 预设名。新设备自增 ID 必然不同，直接搬 ID 会悬空。
 * 2. **明文只在导出这一瞬出现**：reveal 出的密钥 / 令牌 / 账号密码在 [BackupStore.readSnapshot]
 *    里装进快照，用完即擦（实现侧用 CharArray 中间态）。
 * 3. **不搬探测结果**：`health` / `lastOutcome` / `checkedAt` / `okAt` / `probeState` /
 *    `probedAt` 导出时就不写，恢复后一律未探测。
 *
 * 恢复三种模式（§12.1）：覆盖（清空后导入）/ 合并（按自然键去重）/ 仅新增。默认合并。
 */
class BackupEngine constructor(
    private val store: BackupStore,
    private val codec: BackupCodec,
    private val random: RandomBytes,
    private val audit: AuditLogRepository,
    private val autoLocker: IdleLockSuspender,
    private val now: () -> Long,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ------------------------------------------------------------------ 导出

    /**
     * 导出整库为一个加密备份包。
     *
     * @param password 备份口令（默认当前 PIN，也可单独设）。
     * @return 完整包字节（magic + header + 密文 payload），调用方负责 SAF 写出。
     */
    suspend fun export(password: CharArray): ByteArray {
        // 导出也挂起前台空闲锁定（§7.4）：导出大库要 reveal 全部密钥，中途被锁会丢进度。
        autoLocker.pauseIdleLock()
        try {
            return exportInner(password)
        } finally {
            autoLocker.resumeIdleLock()
        }
    }

    private suspend fun exportInner(password: CharArray): ByteArray {
        val snapshot = store.readSnapshot()

        val payload = BackupPayload(
            groups = snapshot.groups,
            providers = snapshot.providers,
            apiKeys = snapshot.apiKeys,
            providerAccounts = snapshot.providerAccounts,
            models = snapshot.models,
            clientProfiles = snapshot.clientProfiles,
            appSettings = snapshot.appSettings,
        )
        val payloadJson = json.encodeToString(BackupPayload.serializer(), payload).encodeToByteArray()
        val compressed = gzip(payloadJson)

        val header = BackupHeader(
            createdAt = now(),
            deviceId = snapshot.deviceId,
            revision = snapshot.revision,
            kdf = KdfParams(salt = random.nextBytes(KdfParams.SALT_BYTES)),
            // nonce 由 BackupCodec.encode 内部生成并写回，这里占位
            nonce = ByteArray(BackupHeader.NONCE_BYTES),
            itemCounts = BackupItemCounts(
                providers = payload.providers.size,
                keys = payload.apiKeys.size,
                accounts = payload.providerAccounts.size,
                models = payload.models.size,
                profiles = payload.clientProfiles.size,
            ),
        )
        return try {
            codec.encode(password, header, compressed).also {
                // 导出成功：记一条日志。数量不落明文密钥，只是条目数（可解释性，§13.4）。
                audit.record(
                    level = LogLevel.INFO,
                    category = LogCategory.BACKUP,
                    message = "exported vault backup",
                    detail = "providers=${payload.providers.size} keys=${payload.apiKeys.size} accounts=${payload.providerAccounts.size} models=${payload.models.size}",
                )
            }
        } catch (t: Throwable) {
            audit.record(
                level = LogLevel.ERROR,
                category = LogCategory.BACKUP,
                message = "export failed",
                detail = t.message,
            )
            throw t
        }
    }

    // ------------------------------------------------------------------ 恢复

    /**
     * 从备份包恢复。
     *
     * @param bytes 完整包字节。
     * @param password 备份口令。
     * @param mode 覆盖 / 合并 / 仅新增。
     * @return 恢复了多少条供应商（供 UI 提示）。
     */
    suspend fun restore(bytes: ByteArray, password: CharArray, mode: RestoreMode): RestoreResult {
        // 恢复同样挂起前台空闲锁定（§7.4），finally 保证任何退出路径（含重抛）都恢复。
        autoLocker.pauseIdleLock()
        try {
            return restoreInner(bytes, password, mode)
        } finally {
            autoLocker.resumeIdleLock()
        }
    }

    private suspend fun restoreInner(bytes: ByteArray, password: CharArray, mode: RestoreMode): RestoreResult {
        val decoded = try {
            codec.decode(bytes, password)
        } catch (t: Throwable) {
            // 解密失败（口令错 / 包损坏）：记 ERROR 再重抛，让 UI 继续走 Snackbar。
            audit.record(
                level = LogLevel.ERROR,
                category = LogCategory.BACKUP,
                message = "restore failed",
                detail = t.message,
            )
            throw t
        }
        val payloadJson = gunzip(decoded.payload)
        val payload = try {
            json.decodeFromString(BackupPayload.serializer(), payloadJson.decodeToString())
        } catch (t: Throwable) {
            audit.record(
                level = LogLevel.ERROR,
                category = LogCategory.BACKUP,
                message = "restore payload corrupted",
                detail = "bad payload json",
            )
            throw BackupCorruptException("bad payload json")
        }

        // 整个恢复（清空 + 导入）在单个事务里：中途失败不能留半库（覆盖模式尤其如此）。
        return store.inTransaction {
            restorePayload(payload, mode)
        }
    }

    private suspend fun restorePayload(payload: BackupPayload, mode: RestoreMode): RestoreResult {
        if (mode == RestoreMode.OVERWRITE) store.clearAll()

        val groupIdByName = payload.groups.associate { it.name to store.findOrInsertGroup(it) }
        val profileIdByKey = payload.clientProfiles.associate { profile ->
            (profile.builtinKey ?: profile.name) to store.findOrInsertProfile(profile)
        }

        var imported = 0
        for (provider in payload.providers) {
            val ref = provider.name to provider.apiRoot
            val providerLookup = provider.name to (provider.websiteUrl ?: provider.apiRoot)
            val existingId = if (mode != RestoreMode.OVERWRITE) {
                store.findProviderId(providerLookup.first, providerLookup.second)
            } else {
                null
            }
            val id = existingId ?: store.insertProvider(
                provider,
                groupId = provider.groupName?.let { groupIdByName[it] },
            ).also { imported++ }

            val keyIdsBySecret = restoreKeys(payload, ref, id, mode, provider, profileIdByKey)
            restoreAccounts(payload, ref, id, mode)
            restoreModels(payload, ref, id, mode, keyIdsBySecret)
        }

        restoreSettings(payload.appSettings, mode)
        return RestoreResult(importedProviders = imported).also {
            // 恢复成功：记一条日志。mode 与导入数进 detail，不带任何明文秘密。
            audit.record(
                level = LogLevel.INFO,
                category = LogCategory.BACKUP,
                message = "restored vault backup",
                detail = "mode=${mode.name.lowercase()} providers=$imported",
            )
        }
    }

    private suspend fun restoreKeys(
        payload: BackupPayload,
        ref: Pair<String, String>,
        providerId: Long,
        mode: RestoreMode,
        provider: BackupProvider,
        profileIdByKey: Map<String, Long>,
    ): Map<String, Long> {
        val keyIdsBySecret = mutableMapOf<String, Long>()
        for (key in payload.apiKeys.filter { it.providerName to it.providerApiRoot == ref }) {
            // 合并 / 仅新增：按重算后的指纹去重（§12.1 指纹在导入端重算）
            if (mode != RestoreMode.OVERWRITE && store.keyExists(providerId, key.secret)) {
                store.findKeyId(providerId, key.secret)?.let { keyIdsBySecret[key.secret] = it }
                continue
            }
            val profileKey = key.settings?.clientProfileKey ?: provider.clientProfileKey
            val profileId = profileKey?.let { profileIdByKey[it] }
            val restoredKey = if (key.settings == null) {
                key.copy(settings = legacySettings(provider))
            } else {
                key
            }
            store.insertKey(providerId, restoredKey, profileId).let { keyIdsBySecret[key.secret] = it }
        }
        return keyIdsBySecret
    }

    /** 旧备份没有 key_settings：把供应商上的旧配置复制到每一把 Key。 */
    private fun legacySettings(provider: BackupProvider): BackupKeySettings = BackupKeySettings(
        apiBaseUrl = provider.apiBaseUrl,
        apiRoot = provider.apiRoot,
        apiVersion = provider.apiVersion,
        supportedProtocols = provider.supportedProtocols,
        pathOverrides = provider.pathOverrides,
        authStyle = provider.authStyle,
        allowInsecure = provider.allowInsecure,
        clientProfileKey = provider.clientProfileKey,
        timeoutSeconds = provider.timeoutSeconds,
        balanceKind = provider.balanceKind,
        balanceBaseUrl = provider.balanceBaseUrl,
        balanceUserId = provider.balanceUserId,
        balanceToken = provider.balanceToken,
        balanceConfig = provider.balanceConfig,
        quotaPerUnit = provider.quotaPerUnit,
        quotaCalibrated = provider.quotaCalibrated,
        probeEnabled = provider.probeEnabled,
        probeReachability = provider.probeReachability,
        probeKeyValidity = provider.probeKeyValidity,
        probeBalance = provider.probeBalance,
        probeModels = provider.probeModels,
        probeModelReachability = provider.probeModelReachability,
    )
    private suspend fun restoreAccounts(
        payload: BackupPayload,
        ref: Pair<String, String>,
        providerId: Long,
        mode: RestoreMode,
    ) {
        for (account in payload.providerAccounts.filter { it.providerName to it.providerApiRoot == ref }) {
            if (mode != RestoreMode.OVERWRITE && store.accountExists(providerId, account.username)) {
                continue
            }
            store.insertAccount(providerId, account)
        }
    }

    private suspend fun restoreModels(
        payload: BackupPayload,
        ref: Pair<String, String>,
        providerId: Long,
        mode: RestoreMode,
        keyIdsBySecret: Map<String, Long>,
    ) {
        for (model in payload.models.filter { it.providerName to it.providerApiRoot == ref }) {
            val keyId = if (model.keySecret == null) {
                store.findKeyId(providerId, null) ?: continue
            } else {
                keyIdsBySecret[model.keySecret] ?: continue
            }
            if (mode != RestoreMode.OVERWRITE &&
                store.modelExists(providerId, keyId, model.modelId, model.protocol)
            ) {
                continue
            }
            store.insertModel(providerId, keyId, model)
        }
    }

    private suspend fun restoreSettings(settings: List<com.lc33.tokenvault.backup.BackupSetting>, mode: RestoreMode) {
        for (setting in settings) {
            if (mode != RestoreMode.OVERWRITE && store.findSetting(setting.key) != null) continue
            store.putSetting(setting.key, setting.value)
        }
    }
}

/** 恢复模式（§12.1）。默认合并。 */
enum class RestoreMode {
    /** 清空后导入。 */
    OVERWRITE,

    /** 按自然键去重合并。 */
    MERGE,

    /** 只导入库中不存在的。 */
    ADD_ONLY,
}

/** 恢复结果。 */
data class RestoreResult(
    val importedProviders: Int,
)
