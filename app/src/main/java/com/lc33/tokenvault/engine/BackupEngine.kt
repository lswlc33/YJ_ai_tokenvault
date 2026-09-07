package com.lc33.tokenvault.engine

import com.lc33.tokenvault.backup.BackupAccount
import com.lc33.tokenvault.backup.BackupApiKey
import com.lc33.tokenvault.backup.BackupCodec
import com.lc33.tokenvault.backup.BackupCorruptException
import com.lc33.tokenvault.backup.BackupGroup
import com.lc33.tokenvault.backup.BackupHeader
import com.lc33.tokenvault.backup.BackupItemCounts
import com.lc33.tokenvault.backup.BackupModel
import com.lc33.tokenvault.backup.BackupPayload
import com.lc33.tokenvault.backup.BackupProfile
import com.lc33.tokenvault.backup.BackupProvider
import com.lc33.tokenvault.backup.BackupSetting
import com.lc33.tokenvault.backup.gunzip
import com.lc33.tokenvault.backup.gzip
import com.lc33.tokenvault.crypto.FieldAad
import com.lc33.tokenvault.crypto.KdfParams
import com.lc33.tokenvault.crypto.RandomBytes
import com.lc33.tokenvault.crypto.toUtf8
import com.lc33.tokenvault.crypto.utf8Chars
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.data.dao.ApiKeyDao
import com.lc33.tokenvault.data.dao.AppSettingDao
import com.lc33.tokenvault.data.dao.ClientProfileDao
import com.lc33.tokenvault.data.dao.GroupDao
import com.lc33.tokenvault.data.dao.ModelDao
import com.lc33.tokenvault.data.dao.ProbeRunDao
import com.lc33.tokenvault.data.dao.ProviderAccountDao
import com.lc33.tokenvault.data.dao.ProviderDao
import com.lc33.tokenvault.data.entity.ApiKeyEntity
import com.lc33.tokenvault.data.entity.AppSettingEntity
import com.lc33.tokenvault.data.entity.ClientProfileEntity
import com.lc33.tokenvault.data.entity.GroupEntity
import com.lc33.tokenvault.data.entity.ModelEntity
import com.lc33.tokenvault.data.entity.ProviderAccountEntity
import com.lc33.tokenvault.data.entity.ProviderEntity
import com.lc33.tokenvault.data.mapper.headersToJson
import com.lc33.tokenvault.data.mapper.pathOverridesToJson
import com.lc33.tokenvault.data.mapper.toCsv
import com.lc33.tokenvault.data.mapper.toHeaderList
import com.lc33.tokenvault.data.mapper.toPathOverrides
import com.lc33.tokenvault.data.mapper.toProtocolSet
import com.lc33.tokenvault.data.repo.FieldCipher
import com.lc33.tokenvault.data.repo.TransactionRunner
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import com.lc33.tokenvault.platform.AutoLocker
import com.lc33.tokenvault.platform.BootState
import com.lc33.tokenvault.platform.BootStore
import kotlinx.serialization.json.Json

/**
 * 备份与恢复的编排（§12.1）。
 *
 * 职责边界刻意划得很窄：**只有导出与恢复的编排**，SAF 文件读写（`CreateDocument` /
 * `OpenDocument`）发生在 ViewModel 层——那个要碰 `ActivityResultContracts`，属于平台。
 *
 * 三条硬规矩（红线 27、28 与 §12.1）：
 *
 * 1. **跨表引用用自然键**：`provider.groupId` → 分组名，`provider.clientProfileId` →
 *    `builtinKey` / 预设名。新设备自增 ID 必然不同，直接搬 ID 会悬空。
 * 2. **明文只在导出这一瞬出现**：reveal 出的密钥 / 令牌 / 账号密码立刻装进 payload、
 *    用完即擦。恢复端用自己的 DEK 重新加密（指纹也重算）。
 * 3. **不搬探测结果**：`health` / `lastOutcome` / `checkedAt` / `okAt` / `probeState` /
 *    `probedAt` 导出时就不写，恢复后一律未探测。
 *
 * 恢复三种模式（§12.1）：覆盖（清空后导入）/ 合并（按自然键去重）/ 仅新增。默认合并。
 */
class BackupEngine constructor(
    private val groupDao: GroupDao,
    private val providerDao: ProviderDao,
    private val keyDao: ApiKeyDao,
    private val accountDao: ProviderAccountDao,
    private val profileDao: ClientProfileDao,
    private val modelDao: ModelDao,
    private val probeRunDao: ProbeRunDao,
    private val appSettingDao: AppSettingDao,
    private val cipher: FieldCipher,
    private val transactions: TransactionRunner,
    private val bootStore: BootStore,
    private val codec: BackupCodec,
    private val random: RandomBytes,
    private val audit: AuditLogRepository,
    private val autoLocker: AutoLocker,
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
        val deviceId = deviceId()

        // 自然键映射：本机 id → 分组名 / 预设键（导出时 provider 的外键列转自然键）
        val groupNameById = groupDao.findAll().associate { it.id to it.name }
        val profileKeyById = profileDao.findAll()
            .associate { it.id to (it.builtinKey ?: it.name) }
        val providerRefById = providerDao.findAll().associate { it.id to (it.name to it.apiRoot) }

        val groups = groupDao.findAll().map { BackupGroup(name = it.name, sortOrder = it.sortOrder) }

        val providers = providerDao.findAll().map { entity ->
            // 余额令牌是密文，导出时解出来（payload 里是明文）
            val balanceToken = entity.balanceTokenEnc?.let { enc ->
                val bytes = cipher.open(enc, FieldAad.of(TABLE_PROVIDERS, entity.id, COL_BALANCE_TOKEN))
                try { bytes.utf8Chars() } finally { bytes.zeroize() }
            }
            try {
                BackupProvider(
                    name = entity.name,
                    note = entity.note,
                    websiteUrl = entity.websiteUrl,
                    apiBaseUrl = entity.apiBaseUrl,
                    apiRoot = entity.apiRoot,
                    apiVersion = entity.apiVersion,
                    supportedProtocols = entity.supportedProtocols.toProtocolSet().map { it.wireName },
                    pathOverrides = entity.pathOverrides.toPathOverrides().mapKeys { it.key.wireName },
                    authStyle = entity.authStyle,
                    allowInsecure = entity.allowInsecure,
                    clientProfileKey = entity.clientProfileId?.let { profileKeyById[it] },
                    groupName = entity.groupId?.let { groupNameById[it] },
                    color = entity.color,
                    pinned = entity.pinned,
                    sortOrder = entity.sortOrder,
                    balanceKind = entity.balanceKind,
                    balanceBaseUrl = entity.balanceBaseUrl,
                    balanceUserId = entity.balanceUserId,
                    balanceToken = balanceToken?.concatToString(),
                    balanceConfig = entity.balanceConfig,
                    quotaPerUnit = entity.quotaPerUnit,
                    quotaCalibrated = entity.quotaCalibrated,
                    timeoutSeconds = entity.timeoutSeconds,
                    probeEnabled = entity.probeEnabled,
                    probeReachability = entity.probeReachability,
                    probeKeyValidity = entity.probeKeyValidity,
                    probeBalance = entity.probeBalance,
                    probeModels = entity.probeModels,
                )
            } finally {
                balanceToken?.zeroize()
            }
        }

        val keys = keyDao.findAll().map { entity ->
            val ref = providerRefById[entity.providerId] ?: return@map null
            val secret = revealSecret(entity)
            try {
                BackupApiKey(
                    providerName = ref.first,
                    providerApiRoot = ref.second,
                    label = entity.label,
                    secret = secret.concatToString(),
                    isDefault = entity.isDefault,
                    enabled = entity.enabled,
                    sortOrder = entity.sortOrder,
                )
            } finally {
                secret.zeroize()
            }
        }.filterNotNull()

        val accounts = accountDao.findAll().map { entity ->
            val ref = providerRefById[entity.providerId] ?: return@map null
            val username = entity.usernameEnc?.let { enc ->
                val bytes = cipher.open(enc, FieldAad.of(TABLE_ACCOUNTS, entity.id, COL_USERNAME))
                try { bytes.utf8Chars() } finally { bytes.zeroize() }
            }
            val password = entity.passwordEnc?.let { enc ->
                val bytes = cipher.open(enc, FieldAad.of(TABLE_ACCOUNTS, entity.id, COL_PASSWORD))
                try { bytes.utf8Chars() } finally { bytes.zeroize() }
            }
            try {
                BackupAccount(
                    providerName = ref.first,
                    providerApiRoot = ref.second,
                    label = entity.label,
                    username = username?.concatToString(),
                    password = password?.concatToString(),
                    loginUrl = entity.loginUrl,
                    note = entity.note,
                    sortOrder = entity.sortOrder,
                )
            } finally {
                username?.zeroize()
                password?.zeroize()
            }
        }.filterNotNull()

        val models = modelDao.findAll().map { entity ->
            val ref = providerRefById[entity.providerId] ?: return@map null
            BackupModel(
                providerName = ref.first,
                providerApiRoot = ref.second,
                modelId = entity.modelId,
                protocol = entity.protocol,
                displayName = entity.displayName,
                source = entity.source,
                discoveredVia = entity.discoveredVia,
                enabled = entity.enabled,
                favorite = entity.favorite,
                needsReview = entity.needsReview,
                catalogKey = entity.catalogKey,
                sortOrder = entity.sortOrder,
            )
        }.filterNotNull()

        // 只备份 userEdited 或自定义的预设；内置未改动的由新设备 ProfileSeeder 生成
        val profiles = profileDao.findAll()
            .filter { it.userEdited || it.builtinKey == null }
            .map { entity ->
                BackupProfile(
                    name = entity.name,
                    builtinKey = entity.builtinKey,
                    userAgent = entity.userAgent,
                    headers = entity.headers.toHeaderList().map { listOf(it.first, it.second) },
                    bodyPatch = entity.bodyPatch,
                    protocols = entity.protocols.toProtocolSet().map { it.wireName },
                    verified = entity.verified,
                    builtinRev = entity.builtinRev,
                    userEdited = entity.userEdited,
                    sortOrder = entity.sortOrder,
                )
            }

        // 设置白名单（§12.1 显式包含 themeMode / localeTag，权威在 boot）
        val settings = SETTINGS_WHITELIST.mapNotNull { key ->
            appSettingDao.find(key)?.let { BackupSetting(key = it.key, value = it.value) }
        }

        val payload = BackupPayload(
            groups = groups,
            providers = providers,
            apiKeys = keys,
            providerAccounts = accounts,
            models = models,
            clientProfiles = profiles,
            appSettings = settings,
        )
        val payloadJson = json.encodeToString(BackupPayload.serializer(), payload).encodeToByteArray()
        val compressed = gzip(payloadJson)

        val header = BackupHeader(
            createdAt = now(),
            deviceId = deviceId,
            revision = bootStore.revision.value,
            kdf = KdfParams(salt = random.nextBytes(KdfParams.SALT_BYTES)),
            // nonce 由 BackupCodec.encode 内部生成并写回，这里占位
            nonce = ByteArray(BackupHeader.NONCE_BYTES),
            itemCounts = BackupItemCounts(
                providers = providers.size,
                keys = keys.size,
                accounts = accounts.size,
                models = models.size,
                profiles = profiles.size,
            ),
        )
        return try {
            codec.encode(password, header, compressed).also {
                // 导出成功：记一条日志。数量不落明文密钥，只是条目数（可解释性，§13.4）。
                audit.record(
                    level = LogLevel.INFO,
                    category = LogCategory.BACKUP,
                    message = "exported vault backup",
                    detail = "providers=${providers.size} keys=${keys.size} accounts=${accounts.size} models=${models.size}",
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

        return transactions.inTransaction {
            if (mode == RestoreMode.OVERWRITE) clearAll()

            val groupIdByName = resolveGroups(payload.groups)
            val profileIdByKey = resolveProfiles(payload.clientProfiles)
            val providerIdByRef = mutableMapOf<Pair<String, String>, Long>()

            var imported = 0
            for (provider in payload.providers) {
                val ref = provider.name to provider.apiRoot
                val existingId = if (mode != RestoreMode.OVERWRITE) findProviderId(provider.name, provider.apiRoot) else null
                val id = existingId ?: insertProvider(provider, groupIdByName, profileIdByKey).also {
                    imported++
                }
                providerIdByRef[ref] = id

                restoreKeys(payload, ref, id, mode)
                restoreAccounts(payload, ref, id, mode)
                restoreModels(payload, ref, id, mode)
            }

            restoreSettings(payload.appSettings, mode)
            RestoreResult(importedProviders = imported).also {
                // 恢复成功：记一条日志。mode 与导入数进 detail，不带任何明文秘密。
                audit.record(
                    level = LogLevel.INFO,
                    category = LogCategory.BACKUP,
                    message = "restored vault backup",
                    detail = "mode=${mode.name.lowercase()} providers=$imported",
                )
            }
        }
    }

    // ------------------------------------------------------------------ 覆盖恢复的清理

    private suspend fun clearAll() {
        // providers 靠外键 CASCADE 连带删 keys / accounts / models
        providerDao.clear()
        groupDao.clear()
        profileDao.clearCustom()
        probeRunDao.clear()
    }

    // ------------------------------------------------------------------ 分组 / 预设的自然键解析

    private suspend fun resolveGroups(groups: List<BackupGroup>): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        for (group in groups) {
            val existing = groupDao.findAll().firstOrNull { it.name == group.name }
            val id = existing?.id ?: groupDao.insert(GroupEntity(name = group.name, sortOrder = group.sortOrder))
            result[group.name] = id
        }
        return result
    }

    private suspend fun resolveProfiles(profiles: List<BackupProfile>): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        for (profile in profiles) {
            val builtinKey = profile.builtinKey
            val key = builtinKey ?: profile.name
            val existing = if (builtinKey != null) {
                profileDao.findByBuiltinKey(builtinKey)
            } else {
                profileDao.findAll().firstOrNull { it.name == profile.name && it.builtinKey == null }
            }
            val id = existing?.id ?: profileDao.insert(profile.toEntity())
            result[key] = id
        }
        return result
    }

    private suspend fun insertProvider(
        provider: BackupProvider,
        groupIdByName: Map<String, Long>,
        profileIdByKey: Map<String, Long>,
    ): Long {
        val stamp = now()
        val id = providerDao.insert(
            ProviderEntity(
                name = provider.name,
                note = provider.note,
                websiteUrl = provider.websiteUrl,
                apiBaseUrl = provider.apiBaseUrl,
                apiRoot = provider.apiRoot,
                apiVersion = provider.apiVersion,
                supportedProtocols = provider.supportedProtocols.joinToString(",").toProtocolSet().toCsv(),
                pathOverrides = provider.pathOverrides
                    .mapNotNull { (wire, path) -> Protocol.fromWireName(wire)?.let { it to path } }
                    .toMap()
                    .pathOverridesToJson(),
                authStyle = provider.authStyle,
                allowInsecure = provider.allowInsecure,
                clientProfileId = provider.clientProfileKey?.let { profileIdByKey[it] },
                groupId = provider.groupName?.let { groupIdByName[it] },
                color = provider.color,
                pinned = provider.pinned,
                sortOrder = provider.sortOrder,
                balanceKind = provider.balanceKind,
                balanceBaseUrl = provider.balanceBaseUrl,
                balanceUserId = provider.balanceUserId,
                balanceConfig = provider.balanceConfig,
                quotaPerUnit = provider.quotaPerUnit,
                quotaCalibrated = provider.quotaCalibrated,
                timeoutSeconds = provider.timeoutSeconds,
                probeEnabled = provider.probeEnabled,
                probeReachability = provider.probeReachability,
                probeKeyValidity = provider.probeKeyValidity,
                probeBalance = provider.probeBalance,
                probeModels = provider.probeModels,
                createdAt = stamp,
                updatedAt = stamp,
            ),
        )
        // 余额令牌是明文，恢复时用本机 DEK 重新加密（两步写，红线 24）
        provider.balanceToken?.let { token ->
            val bytes = token.toCharArray().toUtf8()
            try {
                providerDao.setBalanceToken(
                    id,
                    cipher.seal(bytes, FieldAad.of(TABLE_PROVIDERS, id, COL_BALANCE_TOKEN)),
                    stamp,
                )
            } finally {
                bytes.zeroize()
            }
        }
        return id
    }

    private suspend fun restoreKeys(
        payload: BackupPayload,
        ref: Pair<String, String>,
        providerId: Long,
        mode: RestoreMode,
    ) {
        for (key in payload.apiKeys.filter { it.providerName to it.providerApiRoot == ref }) {
            val secretChars = key.secret.toCharArray()
            val bytes = secretChars.toUtf8()
            try {
                val fingerprint = cipher.fingerprint(bytes)
                // 合并 / 仅新增：按重算后的指纹去重（§12.1 指纹在导入端重算）
                if (mode != RestoreMode.OVERWRITE &&
                    keyDao.findByProvider(providerId).any { it.fingerprint == fingerprint }
                ) {
                    continue
                }
                val stamp = now()
                val id = keyDao.insert(
                    ApiKeyEntity(
                        providerId = providerId,
                        label = key.label,
                        secretEnc = ByteArray(0),
                        fingerprint = fingerprint,
                        sortOrder = key.sortOrder,
                        createdAt = stamp,
                        updatedAt = stamp,
                    ),
                    stamp,
                )
                keyDao.setSecret(
                    id,
                    cipher.seal(bytes, FieldAad.of(TABLE_KEYS, id, COL_SECRET)),
                    fingerprint,
                    stamp,
                )
                // 恢复默认 / 停用标记（直接落库，避免走 add() 的"第一张自动默认"逻辑冲突）
                if (key.isDefault) keyDao.setDefault(providerId, id, stamp)
                if (!key.enabled) keyDao.setEnabledRaw(id, false, stamp)
            } finally {
                bytes.zeroize()
                secretChars.zeroize()
            }
        }
    }

    private suspend fun restoreAccounts(
        payload: BackupPayload,
        ref: Pair<String, String>,
        providerId: Long,
        mode: RestoreMode,
    ) {
        for (account in payload.providerAccounts.filter { it.providerName to it.providerApiRoot == ref }) {
            val usernameBytes = account.username?.toCharArray()?.toUtf8()
            val passwordBytes = account.password?.toCharArray()?.toUtf8()
            try {
                val usernameFp = usernameBytes?.let { cipher.fingerprint(it) }
                if (mode != RestoreMode.OVERWRITE && usernameFp != null &&
                    accountDao.findAll().any { it.providerId == providerId && it.usernameFp == usernameFp }
                ) {
                    continue
                }
                val stamp = now()
                val id = accountDao.insert(
                    ProviderAccountEntity(
                        providerId = providerId,
                        label = account.label,
                        usernameEnc = null,
                        usernameFp = usernameFp,
                        passwordEnc = null,
                        loginUrl = account.loginUrl,
                        note = account.note,
                        sortOrder = account.sortOrder,
                        createdAt = stamp,
                        updatedAt = stamp,
                    ),
                )
                usernameBytes?.let {
                    accountDao.setUsername(id, cipher.seal(it, FieldAad.of(TABLE_ACCOUNTS, id, COL_USERNAME)), usernameFp!!, stamp)
                }
                passwordBytes?.let {
                    accountDao.setPassword(id, cipher.seal(it, FieldAad.of(TABLE_ACCOUNTS, id, COL_PASSWORD)), stamp)
                }
            } finally {
                usernameBytes?.zeroize()
                passwordBytes?.zeroize()
            }
        }
    }

    private suspend fun restoreModels(
        payload: BackupPayload,
        ref: Pair<String, String>,
        providerId: Long,
        mode: RestoreMode,
    ) {
        for (model in payload.models.filter { it.providerName to it.providerApiRoot == ref }) {
            if (mode != RestoreMode.OVERWRITE &&
                modelDao.findByProvider(providerId).any { it.modelId == model.modelId }
            ) {
                continue
            }
            val stamp = now()
            modelDao.insertIgnoring(
                ModelEntity(
                    providerId = providerId,
                    modelId = model.modelId,
                    protocol = model.protocol,
                    displayName = model.displayName,
                    source = model.source,
                    discoveredVia = model.discoveredVia,
                    enabled = model.enabled,
                    favorite = model.favorite,
                    needsReview = model.needsReview,
                    catalogKey = model.catalogKey,
                    firstSeenAt = stamp,
                    sortOrder = model.sortOrder,
                ),
            )
        }
    }

    private suspend fun restoreSettings(settings: List<BackupSetting>, mode: RestoreMode) {
        for (setting in settings) {
            if (mode != RestoreMode.OVERWRITE && appSettingDao.find(setting.key) != null) continue
            appSettingDao.put(AppSettingEntity(key = setting.key, value = setting.value))
        }
    }

    private suspend fun findProviderId(name: String, apiRoot: String): Long? =
        providerDao.findAll().firstOrNull { it.name == name && it.apiRoot == apiRoot }?.id

    private suspend fun revealSecret(entity: ApiKeyEntity): CharArray {
        val plain = cipher.open(entity.secretEnc, FieldAad.of(TABLE_KEYS, entity.id, COL_SECRET))
        return try {
            plain.utf8Chars()
        } finally {
            plain.zeroize()
        }
    }

    private fun deviceId(): String = when (val state = bootStore.read()) {
        is BootState.Ok -> state.record.deviceId
        else -> "unknown"
    }

    private fun BackupProfile.toEntity(): ClientProfileEntity = ClientProfileEntity(
        name = name,
        builtinKey = builtinKey,
        userAgent = userAgent,
        headers = headers.mapNotNull { if (it.size >= 2) it[0] to it[1] else null }.headersToJson(),
        bodyPatch = bodyPatch,
        protocols = protocols.joinToString(",").toProtocolSet().toCsv(),
        verified = verified,
        builtinRev = builtinRev,
        userEdited = userEdited,
        sortOrder = sortOrder,
    )

    private companion object {
        const val TABLE_PROVIDERS = "providers"
        const val COL_BALANCE_TOKEN = "balanceTokenEnc"
        const val TABLE_KEYS = "api_keys"
        const val COL_SECRET = "secretEnc"
        const val TABLE_ACCOUNTS = "provider_accounts"
        const val COL_USERNAME = "usernameEnc"
        const val COL_PASSWORD = "passwordEnc"

        /** 设置白名单（§12.1：themeMode / localeTag 权威在 boot，显式包含）。 */
        val SETTINGS_WHITELIST = setOf("themeMode", "localeTag")
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
