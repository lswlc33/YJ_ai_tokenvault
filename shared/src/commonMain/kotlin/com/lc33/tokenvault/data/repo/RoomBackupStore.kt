package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.backup.BackupAccount
import com.lc33.tokenvault.backup.BackupApiKey
import com.lc33.tokenvault.backup.BackupGroup
import com.lc33.tokenvault.backup.BackupKeySettings
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
import com.lc33.tokenvault.data.dao.KeySettingsDao
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
import com.lc33.tokenvault.data.mapper.toDomain
import com.lc33.tokenvault.data.mapper.toEntity
import com.lc33.tokenvault.data.mapper.toHeaderList
import com.lc33.tokenvault.data.mapper.toPathOverrides
import com.lc33.tokenvault.data.mapper.toProtocolSet
import com.lc33.tokenvault.domain.AuthStyle
import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.KeyProbeSettings
import com.lc33.tokenvault.domain.model.KeySettings
import com.lc33.tokenvault.domain.repo.TransactionRunner
import kotlinx.coroutines.flow.first
import com.lc33.tokenvault.platform.BootState
import com.lc33.tokenvault.platform.BootStore

class RoomBackupStore constructor(
    private val groupDao: GroupDao,
    private val providerDao: ProviderDao,
    private val keyDao: ApiKeyDao,
    private val settingsDao: KeySettingsDao,
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

    override suspend fun readSnapshot(): VaultSnapshot {
        val boot = bootStore.read()
        val deviceId = (boot as? BootState.Ok)?.record?.deviceId.orEmpty()
        val revision = bootStore.revision.value
        val profileKeyById = profileDao.findAll()
            .associate { it.id to (it.builtinKey ?: it.name) }
        val providerEntities = providerDao.findAll()
        val providerById = providerEntities.associateBy { it.id }
        val keyRows = keyDao.findAll()

        val groups = groupDao.findAll().map { BackupGroup(it.name, it.sortOrder) }
        val providers = providerEntities.map { entity ->
            val firstKeyRoot = keyRows.firstOrNull { it.key.providerId == entity.id }
                ?.settings?.apiRoot.orEmpty()
            BackupProvider(
                name = entity.name,
                note = entity.note,
                websiteUrl = entity.websiteUrl,
                groupName = entity.groupId?.let { id -> groupDao.findById(id)?.name },
                color = entity.color,
                pinned = entity.pinned,
                sortOrder = entity.sortOrder,
                apiRoot = entity.websiteUrl.orEmpty(),
            )
        }

        val apiKeys = keyRows.map { row ->
            val key = row.key
            val settings = row.settings
            val secret = revealSecret(key)
            val balanceToken = settings?.balanceTokenEnc?.let { enc ->
                revealBalanceToken(key.id, enc)
            }
            try {
                BackupApiKey(
                    providerName = providerById[key.providerId]?.name.orEmpty(),
                    providerApiRoot = providerById[key.providerId]?.websiteUrl.orEmpty(),
                    label = key.label,
                    note = key.note,
                    secret = secret.concatToString(),
                    enabled = key.enabled,
                    sortOrder = key.sortOrder,
                    settings = settings?.let { entity ->
                        BackupKeySettings(
                            apiBaseUrl = entity.apiBaseUrl,
                            apiRoot = entity.apiRoot,
                            apiVersion = entity.apiVersion,
                            supportedProtocols = entity.supportedProtocols.split(',').filter { it.isNotBlank() },
                            pathOverrides = entity.pathOverrides.toPathOverrides()
                                .mapKeys { it.key.wireName },
                            authStyle = entity.authStyle,
                            allowInsecure = entity.allowInsecure,
                            clientProfileKey = entity.clientProfileId?.let { profileKeyById[it] },
                            timeoutSeconds = entity.timeoutSeconds,
                            balanceKind = entity.balanceKind,
                            balanceBaseUrl = entity.balanceBaseUrl,
                            balanceUserId = entity.balanceUserId,
                            balanceToken = balanceToken?.concatToString(),
                            balanceConfig = entity.balanceConfig,
                            quotaPerUnit = entity.quotaPerUnit,
                            quotaCalibrated = entity.quotaCalibrated,
                            probeEnabled = entity.probeEnabled,
                            probeReachability = entity.probeReachability,
                            probeKeyValidity = entity.probeKeyValidity,
                            probeBalance = entity.probeBalance,
                            probeModels = entity.probeModels,
                            probeModelReachability = entity.probeModelReachability,
                        )
                    },
                )
            } finally {
                secret.zeroize()
                balanceToken?.zeroize()
            }
        }

        val accounts = accountDao.findAll().map { entity ->
            val username = entity.usernameEnc?.let { revealBytes(it, FieldAad.of(TABLE_ACCOUNTS, entity.id, COL_USERNAME)) }
            val password = entity.passwordEnc?.let { revealBytes(it, FieldAad.of(TABLE_ACCOUNTS, entity.id, COL_PASSWORD)) }
            try {
                BackupAccount(
                    providerName = providerById[entity.providerId]?.name.orEmpty(),
                    providerApiRoot = providerById[entity.providerId]?.websiteUrl.orEmpty(),
                    label = entity.label,
                    username = username?.concatToString(),
                    password = password?.concatToString(),
                    loginUrl = entity.loginUrl,
                    loginMethods = entity.loginMethods.split(',').filter { it.isNotBlank() },
                    note = entity.note,
                    sortOrder = entity.sortOrder,
                )
            } finally {
                username?.zeroize()
                password?.zeroize()
            }
        }

        val models = modelDao.findAll().map { entity ->
            BackupModel(
                providerName = providerById[entity.providerId]?.name.orEmpty(),
                providerApiRoot = providerById[entity.providerId]?.websiteUrl.orEmpty(),
                keySecret = keyRows.firstOrNull { it.key.id == entity.keyId }?.let { row ->
                    val chars = revealSecret(row.key)
                    try {
                        chars.concatToString()
                    } finally {
                        chars.zeroize()
                    }
                },
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

        val profiles = profileDao.findAll().map { entity ->
            BackupProfile(
                name = entity.name,
                builtinKey = entity.builtinKey,
                userAgent = entity.userAgent,
                headers = entity.headers.toHeaderList().map { listOf(it.first, it.second) },
                bodyPatch = entity.bodyPatch,
                protocols = entity.protocols.split(',').filter { it.isNotBlank() },
                verified = entity.verified,
                builtinRev = entity.builtinRev,
                userEdited = entity.userEdited,
                sortOrder = entity.sortOrder,
            )
        }

        val settings = appSettingDao.observeAll().first().map {
            BackupSetting(it.key, it.value)
        }

        return VaultSnapshot(
            deviceId = deviceId,
            revision = revision,
            groups = groups,
            providers = providers,
            apiKeys = apiKeys,
            providerAccounts = accounts,
            models = models,
            clientProfiles = profiles,
            appSettings = settings,
        )
    }

    override suspend fun clearAll() {
        providerDao.clear()
        profileDao.clearCustom()
        groupDao.clear()
        probeRunDao.clear()
    }

    override suspend fun findOrInsertGroup(group: BackupGroup): Long {
        groupDao.findAll().firstOrNull { it.name == group.name }?.let { return it.id }
        return groupDao.insert(GroupEntity(name = group.name, sortOrder = group.sortOrder))
    }

    override suspend fun findOrInsertProfile(profile: BackupProfile): Long {
        profile.builtinKey?.let { key ->
            profileDao.findByBuiltinKey(key)?.let { return it.id }
        }
        profileDao.findAll().firstOrNull { it.name == profile.name }?.let { return it.id }
        return profileDao.insert(
            ClientProfileEntity(
                name = profile.name,
                builtinKey = profile.builtinKey,
                userAgent = profile.userAgent,
                headers = profile.headers.joinToString(",") { it.joinToString(":") },
                bodyPatch = profile.bodyPatch,
                protocols = profile.protocols.joinToString(","),
                verified = profile.verified,
                builtinRev = profile.builtinRev,
                userEdited = profile.userEdited,
                sortOrder = profile.sortOrder,
            ),
        )
    }

    override suspend fun findProviderId(name: String, websiteUrl: String?): Long? =
        providerDao.findAll().firstOrNull { it.name == name && it.websiteUrl == websiteUrl }?.id

    override suspend fun insertProvider(provider: BackupProvider, groupId: Long?): Long {
        val stamp = now()
        return providerDao.insert(
            ProviderEntity(
                name = provider.name,
                note = provider.note,
                websiteUrl = provider.websiteUrl,
                groupId = groupId,
                color = provider.color,
                pinned = provider.pinned,
                sortOrder = provider.sortOrder,
                createdAt = stamp,
                updatedAt = stamp,
            ),
        )
    }

    override suspend fun keyExists(providerId: Long, secret: String): Boolean {
        val bytes = secret.toCharArray().toUtf8()
        return try {
            val fp = cipher.fingerprint(bytes)
            keyDao.findByProvider(providerId).any { it.key.fingerprint == fp }
        } finally {
            bytes.zeroize()
        }
    }

    override suspend fun insertKey(
        providerId: Long,
        key: BackupApiKey,
        profileId: Long?,
    ): Long {
        val secretChars = key.secret.toCharArray()
        val bytes = secretChars.toUtf8()
        return try {
            val fingerprint = cipher.fingerprint(bytes)
            val stamp = now()
            val id = keyDao.insertRaw(
                ApiKeyEntity(
                    providerId = providerId,
                    label = key.label,
                    note = key.note,
                    secretEnc = ByteArray(0),
                    fingerprint = fingerprint,
                    enabled = key.enabled,
                    sortOrder = key.sortOrder,
                    createdAt = stamp,
                    updatedAt = stamp,
                ),
            )
            keyDao.setSecret(
                id,
                cipher.seal(bytes, FieldAad.of(TABLE_KEYS, id, COL_SECRET)),
                fingerprint,
                stamp,
            )

            val settings = key.settings?.toDomain(profileId)
                ?: KeySettings(apiBaseUrl = "", apiRoot = "")
            val tokenBytes = key.settings?.balanceToken?.toCharArray()?.toUtf8()
            try {
                val sealed = tokenBytes?.let {
                    cipher.seal(it, FieldAad.of(TABLE_SETTINGS, id, COL_BALANCE_TOKEN))
                }
                settingsDao.insert(settings.toEntity(id, stamp).copy(balanceTokenEnc = sealed))
            } finally {
                tokenBytes?.zeroize()
            }
            id
        } finally {
            bytes.zeroize()
            secretChars.zeroize()
        }
    }

    override suspend fun findKeyId(providerId: Long, secret: String?): Long? {
        if (secret == null) return keyDao.findByProvider(providerId).firstOrNull()?.key?.id
        val bytes = secret.toCharArray().toUtf8()
        return try {
            val fp = cipher.fingerprint(bytes)
            keyDao.findByProvider(providerId).firstOrNull { it.key.fingerprint == fp }?.key?.id
        } finally {
            bytes.zeroize()
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
        val stamp = now()
        val username = account.username?.toCharArray()
        val password = account.password?.toCharArray()
        val usernameBytes = username?.toUtf8()
        val passwordBytes = password?.toUtf8()
        try {
            val usernameFp = usernameBytes?.let { cipher.fingerprint(it) }
            val id = accountDao.insert(
                ProviderAccountEntity(
                    providerId = providerId,
                    label = account.label,
                    usernameEnc = null,
                    usernameFp = usernameFp,
                    passwordEnc = null,
                    loginUrl = account.loginUrl,
                    loginMethods = account.loginMethods.joinToString(","),
                    note = account.note,
                    sortOrder = account.sortOrder,
                    createdAt = stamp,
                    updatedAt = stamp,
                ),
            )
            usernameBytes?.let {
                accountDao.setUsername(
                    id,
                    cipher.seal(it, FieldAad.of(TABLE_ACCOUNTS, id, COL_USERNAME)),
                    usernameFp!!,
                    stamp,
                )
            }
            passwordBytes?.let {
                accountDao.setPassword(
                    id,
                    cipher.seal(it, FieldAad.of(TABLE_ACCOUNTS, id, COL_PASSWORD)),
                    stamp,
                )
            }
        } finally {
            usernameBytes?.zeroize()
            passwordBytes?.zeroize()
            username?.zeroize()
            password?.zeroize()
        }
    }

    override suspend fun modelExists(
        providerId: Long,
        keyId: Long?,
        modelId: String,
        protocol: String,
    ): Boolean = modelDao.findByProvider(providerId).any {
        it.keyId == keyId && it.modelId == modelId && it.protocol == protocol
    }

    override suspend fun insertModel(providerId: Long, keyId: Long?, model: BackupModel) {
        modelDao.insertIgnoring(
            ModelEntity(
                providerId = providerId,
                keyId = keyId,
                modelId = model.modelId,
                protocol = model.protocol,
                displayName = model.displayName,
                source = model.source,
                discoveredVia = model.discoveredVia,
                enabled = model.enabled,
                favorite = model.favorite,
                needsReview = model.needsReview,
                catalogKey = model.catalogKey,
                firstSeenAt = now(),
                sortOrder = model.sortOrder,
            ),
        )
    }

    override suspend fun findSetting(key: String): String? = appSettingDao.find(key)?.value

    override suspend fun putSetting(key: String, value: String?) {
        appSettingDao.put(AppSettingEntity(key = key, value = value))
    }

    private fun revealSecret(key: ApiKeyEntity): CharArray {
        val plain = cipher.open(key.secretEnc, FieldAad.of(TABLE_KEYS, key.id, COL_SECRET))
        return try {
            plain.utf8Chars()
        } finally {
            plain.zeroize()
        }
    }

    private fun revealBalanceToken(keyId: Long, enc: ByteArray): CharArray {
        val plain = cipher.open(enc, FieldAad.of(TABLE_SETTINGS, keyId, COL_BALANCE_TOKEN))
        return try {
            plain.utf8Chars()
        } finally {
            plain.zeroize()
        }
    }

    private fun revealBytes(enc: ByteArray, aad: FieldAad): CharArray {
        val plain = cipher.open(enc, aad)
        return try {
            plain.utf8Chars()
        } finally {
            plain.zeroize()
        }
    }

    private fun BackupKeySettings.toDomain(profileId: Long?): KeySettings = KeySettings(
        apiBaseUrl = apiBaseUrl,
        apiRoot = apiRoot,
        apiVersion = apiVersion,
        supportedProtocols = supportedProtocols.mapNotNull { Protocol.fromWireName(it) }.toSet(),
        pathOverrides = pathOverrides.mapNotNull { (wire, path) ->
            Protocol.fromWireName(wire)?.let { it to path }
        }.toMap(),
        authStyle = AuthStyle.fromWireName(authStyle),
        allowInsecure = allowInsecure,
        clientProfileId = profileId,
        timeoutSeconds = timeoutSeconds,
        balanceKind = BalanceKind.fromWireName(balanceKind),
        balanceBaseUrl = balanceBaseUrl,
        balanceUserId = balanceUserId,
        balanceConfig = balanceConfig,
        quotaPerUnit = quotaPerUnit,
        quotaCalibrated = quotaCalibrated,
        probe = KeyProbeSettings(
            enabled = probeEnabled,
            reachability = probeReachability,
            keyValidity = probeKeyValidity,
            balance = probeBalance,
            models = probeModels,
            modelReachability = probeModelReachability,
        ),
    )

    private companion object {
        const val TABLE_KEYS = "api_keys"
        const val TABLE_SETTINGS = "key_settings"
        const val TABLE_ACCOUNTS = "provider_accounts"
        const val COL_SECRET = "secretEnc"
        const val COL_BALANCE_TOKEN = "balanceTokenEnc"
        const val COL_USERNAME = "usernameEnc"
        const val COL_PASSWORD = "passwordEnc"
    }
}
