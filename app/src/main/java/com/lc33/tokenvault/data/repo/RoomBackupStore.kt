package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.backup.BackupAccount
import com.lc33.tokenvault.backup.BackupApiKey
import com.lc33.tokenvault.backup.BackupGroup
import com.lc33.tokenvault.backup.BackupModel
import com.lc33.tokenvault.backup.BackupProfile
import com.lc33.tokenvault.backup.BackupProvider
import com.lc33.tokenvault.backup.BackupSetting
import com.lc33.tokenvault.backup.BackupStore
import com.lc33.tokenvault.backup.VaultSnapshot
import com.lc33.tokenvault.crypto.FieldAad
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
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.platform.BootState
import com.lc33.tokenvault.platform.BootStore

/**
 * [BackupStore] 的 Room 实现。备份安全关键路径的全部数据访问 + 加解密收在这里。
 *
 * 承担了原来 `engine/BackupEngine` 里所有"直接碰 Room 实体、DAO、FieldCipher、AAD、两步写"
 * 的部分，让引擎迁到 commonMain 后只做编排。每一条红线都在实现里兑现：
 * - 红线 24（AAD 绑 id）：[insertKey] / [insertAccount] / [insertProvider] 的两步写。
 * - 红线 27（明文只在导出这一瞬）：[readSnapshot] reveal 后用 CharArray 中间态擦掉。
 * - §12.1（指纹导入端重算）：[keyExists] / [accountExists] 用本机 DEK 重算。
 */
class RoomBackupStore constructor(
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
    private val now: () -> Long,
) : BackupStore {

    override suspend fun <R> inTransaction(block: suspend () -> R): R =
        transactions.inTransaction(block)

    // ------------------------------------------------------------------ 导出

    override suspend fun readSnapshot(): VaultSnapshot {
        val deviceId = deviceId()

        // 自然键映射：本机 id → 分组名 / 预设键（导出时 provider 的外键列转自然键）
        val groupNameById = groupDao.findAll().associate { it.id to it.name }
        val profileKeyById = profileDao.findAll()
            .associate { it.id to (it.builtinKey ?: it.name) }
        val providerRefById = providerDao.findAll().associate { it.id to (it.name to it.apiRoot) }

        val groups = groupDao.findAll().map { BackupGroup(name = it.name, sortOrder = it.sortOrder) }

        val providers = providerDao.findAll().map { entity ->
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

        val apiKeys = keyDao.findAll().mapNotNull { entity ->
            val ref = providerRefById[entity.providerId] ?: return@mapNotNull null
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
        }

        val accounts = accountDao.findAll().mapNotNull { entity ->
            val ref = providerRefById[entity.providerId] ?: return@mapNotNull null
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
        }

        val models = modelDao.findAll().mapNotNull { entity ->
            val ref = providerRefById[entity.providerId] ?: return@mapNotNull null
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
        }

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

        val settings = SETTINGS_WHITELIST.mapNotNull { key ->
            appSettingDao.find(key)?.let { BackupSetting(key = it.key, value = it.value) }
        }

        return VaultSnapshot(
            deviceId = deviceId,
            revision = bootStore.revision.value,
            groups = groups,
            providers = providers,
            apiKeys = apiKeys,
            providerAccounts = accounts,
            models = models,
            clientProfiles = profiles,
            appSettings = settings,
        )
    }

    // ------------------------------------------------------------------ 覆盖模式清理

    override suspend fun clearAll() {
        // providers 靠外键 CASCADE 连带删 keys / accounts / models
        providerDao.clear()
        groupDao.clear()
        profileDao.clearCustom()
        probeRunDao.clear()
    }

    // ------------------------------------------------------------------ 自然键解析

    override suspend fun findOrInsertGroup(group: BackupGroup): Long {
        val existing = groupDao.findAll().firstOrNull { it.name == group.name }
        return existing?.id ?: groupDao.insert(GroupEntity(name = group.name, sortOrder = group.sortOrder))
    }

    override suspend fun findOrInsertProfile(profile: BackupProfile): Long {
        val builtinKey = profile.builtinKey
        val existing = if (builtinKey != null) {
            profileDao.findByBuiltinKey(builtinKey)
        } else {
            profileDao.findAll().firstOrNull { it.name == profile.name && it.builtinKey == null }
        }
        return existing?.id ?: profileDao.insert(profile.toEntity())
    }

    override suspend fun findProviderId(name: String, apiRoot: String): Long? =
        providerDao.findAll().firstOrNull { it.name == name && it.apiRoot == apiRoot }?.id

    // ------------------------------------------------------------------ 写入

    override suspend fun insertProvider(
        provider: BackupProvider,
        groupId: Long?,
        profileId: Long?,
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
                clientProfileId = profileId,
                groupId = groupId,
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

    override suspend fun keyExists(providerId: Long, secret: String): Boolean {
        val bytes = secret.toCharArray().toUtf8()
        return try {
            val fingerprint = cipher.fingerprint(bytes)
            keyDao.findByProvider(providerId).any { it.fingerprint == fingerprint }
        } finally {
            bytes.zeroize()
        }
    }

    override suspend fun insertKey(providerId: Long, key: BackupApiKey) {
        val secretChars = key.secret.toCharArray()
        val bytes = secretChars.toUtf8()
        try {
            val fingerprint = cipher.fingerprint(bytes)
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

    override suspend fun accountExists(providerId: Long, username: String?): Boolean {
        if (username == null) return false
        val bytes = username.toCharArray().toUtf8()
        return try {
            val fp = cipher.fingerprint(bytes)
            accountDao.findAll().any { it.providerId == providerId && it.usernameFp == fp }
        } finally {
            bytes.zeroize()
        }
    }

    override suspend fun insertAccount(providerId: Long, account: BackupAccount) {
        val usernameBytes = account.username?.toCharArray()?.toUtf8()
        val passwordBytes = account.password?.toCharArray()?.toUtf8()
        try {
            val usernameFp = usernameBytes?.let { cipher.fingerprint(it) }
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

    override suspend fun modelExists(providerId: Long, modelId: String): Boolean =
        modelDao.findByProvider(providerId).any { it.modelId == modelId }

    override suspend fun insertModel(providerId: Long, model: BackupModel) {
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

    override suspend fun findSetting(key: String): String? = appSettingDao.find(key)?.value

    override suspend fun putSetting(key: String, value: String?) =
        appSettingDao.put(AppSettingEntity(key = key, value = value))

    // ------------------------------------------------------------------ 私有

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
