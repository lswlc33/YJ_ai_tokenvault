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

    /**
     * 导出整库快照。
     *
     * 容错立场与 [com.lc33.tokenvault.data.mapper] 的列编解码一致：**读方向单向容错**。
     * 这里每 reveal 一次密文都可能抛（坏密文、AAD 不匹配、锁了屏），而"一行坏数据毁掉
     * 整份导出"恰恰是最不能接受的后果——用户正是在"库看着不对劲"的时候才去备份。
     * 所以坏行跳过，只把定位信息（`表名:id`，不含密文也不含明文）攒进
     * [VaultSnapshot.skippedRows]，由 `BackupEngine` 落成一条 WARN 日志：导出仍然成功，
     * 但少了哪几行用户查得到。
     */
    override suspend fun readSnapshot(): VaultSnapshot {
        val boot = bootStore.read()
        val deviceId = (boot as? BootState.Ok)?.record?.deviceId.orEmpty()
        val revision = bootStore.revision.value
        val skipped = mutableListOf<String>()
        val profileKeyById = profileDao.findAll()
            .associate { it.id to (it.builtinKey ?: it.name) }
        val providerEntities = providerDao.findAll()
        val providerById = providerEntities.associateBy { it.id }
        val keyRows = keyDao.findAll()
        // 这是**跨包去重**用的自然键（`findProviderId` 按同一算法从库里算），所以它的口径
        // 不能改——改了旧包里那些 `providerApiRoot` 就再也配不上任何一行了。
        // 包内的子条目归位不再依赖它，见 [providerRefs]。
        val providerApiRootById = providerEntities.associate { provider ->
            provider.id to keyRows.firstOrNull { it.key.providerId == provider.id }
                ?.settings?.apiRoot.orEmpty()
        }
        val providerRefById = providerRefs(providerEntities, providerApiRootById)

        val groups = groupDao.findAll().map { BackupGroup(it.name, it.sortOrder) }
        val providers = providerEntities.map { entity ->
            BackupProvider(
                name = entity.name,
                note = entity.note,
                websiteUrl = entity.websiteUrl,
                checkWebsite = entity.checkWebsite,
                groupName = entity.groupId?.let { id -> groupDao.findById(id)?.name },
                color = entity.color,
                pinned = entity.pinned,
                sortOrder = entity.sortOrder,
                apiRoot = providerApiRootById[entity.id].orEmpty(),
                ref = providerRefById[entity.id],
            )
        }

        val apiKeys = keyRows.mapNotNull { row ->
            val key = row.key
            val settings = row.settings
            val secret = revealOrSkip("$TABLE_KEYS:${key.id}", skipped) { revealSecret(key) }
                ?: return@mapNotNull null
            val balanceToken = settings?.balanceTokenEnc?.let { enc ->
                revealOrSkip("$TABLE_SETTINGS:${key.id}:$COL_BALANCE_TOKEN", skipped) {
                    revealBalanceToken(key.id, enc)
                }
            }
            try {
                BackupApiKey(
                    providerName = providerById[key.providerId]?.name.orEmpty(),
                    providerApiRoot = providerApiRootById[key.providerId].orEmpty(),
                    providerRef = providerRefById[key.providerId],
                    label = key.label,
                    note = key.note,
                    secret = secret.concatToString(),
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
                            probeQuickModel = entity.probeQuickModel,
                        )
                    },
                )
            } finally {
                secret.zeroize()
                balanceToken?.zeroize()
            }
        }

        val accounts = accountDao.findAll().mapNotNull { entity ->
            val where = "$TABLE_ACCOUNTS:${entity.id}"
            val username = entity.usernameEnc?.let { enc ->
                revealOrSkip(where, skipped) {
                    revealBytes(enc, FieldAad.of(TABLE_ACCOUNTS, entity.id, COL_USERNAME))
                }
            }
            // 用户名解不开 → 整条账号跳过：只带着密码的一半进包，恢复出来是一条
            // 看上去存在、其实登不进去的账号，比少一条更难发现。
            if (entity.usernameEnc != null && username == null) return@mapNotNull null
            val password = entity.passwordEnc?.let { enc ->
                revealOrSkip(where, skipped) {
                    revealBytes(enc, FieldAad.of(TABLE_ACCOUNTS, entity.id, COL_PASSWORD))
                }
            }
            if (entity.passwordEnc != null && password == null) return@mapNotNull null
            try {
                BackupAccount(
                    providerName = providerById[entity.providerId]?.name.orEmpty(),
                    providerApiRoot = providerApiRootById[entity.providerId].orEmpty(),
                    providerRef = providerRefById[entity.providerId],
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
            // 模型行本身全是明文；只有"绑到哪把 Key"需要 reveal。解不开就退回 keySecret=null，
            // 恢复时挂到这家第一把 Key 上——少一点定位信息，不把整行丢掉。
            val ownerRow = entity.keyId?.let { owner -> keyRows.firstOrNull { it.key.id == owner } }
            val keySecret = ownerRow?.let { row ->
                val chars = revealOrSkip("models:${entity.id}", skipped) { revealSecret(row.key) }
                chars?.concatToString()?.also { chars.zeroize() }
            }
            BackupModel(
                providerName = providerById[entity.providerId]?.name.orEmpty(),
                providerApiRoot = providerApiRootById[entity.providerId].orEmpty(),
                providerRef = providerRefById[entity.providerId],
                keySecret = keySecret,
                modelId = entity.modelId,
                protocol = entity.protocol,
                displayName = entity.displayName,
                source = entity.source,
                discoveredVia = entity.discoveredVia,
                favorite = entity.favorite,
                needsReview = entity.needsReview,
                catalogKey = entity.catalogKey,
                sortOrder = entity.sortOrder,
            )
        }

        // 红线 4：未改动的内置预设不进包——新设备由 `ProfileSeeder` 按当前版本的指纹种，
        // 搬过去反而会把用户设备上更新的版本盖回旧版。
        val profiles = profileDao.findAll()
            .filter { it.builtinKey == null || it.userEdited }
            .map { entity ->
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

        val settings = appSettingDao.observeAll().first().mapNotNull { row ->
            val secret = row.valueBlob?.let { blob ->
                revealOrSkip("app_settings:${row.key}", skipped) {
                    revealBytes(blob, FieldAad.ofSetting(row.key, COL_BLOB))
                }
            }
            // 解不开的密文不能降级成"没有这一项"再写回去：那正是原来的丢法。整条跳过。
            if (row.valueBlob != null && secret == null) return@mapNotNull null
            try {
                BackupSetting(
                    key = row.key,
                    value = row.value,
                    encryptedValue = secret?.concatToString(),
                )
            } finally {
                secret?.zeroize()
            }
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
            skippedRows = skipped,
        )
    }

    /**
     * 包内唯一的供应商归属令牌。
     *
     * `name + apiRoot` 相同（同名同 apiRoot 的两家）时后面那几家加 `#2`、`#3`：跨包去重
     * 仍然只看自然键，这一项只负责"包里的这条 Key 属于包里的那一家"。
     */
    private fun providerRefs(
        providers: List<ProviderEntity>,
        apiRootById: Map<Long, String>,
    ): Map<Long, String> {
        val seen = mutableMapOf<String, Int>()
        return providers.associate { provider ->
            val base = "${provider.name}|${apiRootById[provider.id].orEmpty()}"
            val nth = (seen[base] ?: 0) + 1
            seen[base] = nth
            provider.id to if (nth == 1) base else "$base#$nth"
        }
    }

    /** 解不开就记一行、返回 null，让调用方决定跳过还是降级。 */
    private inline fun <T : Any> revealOrSkip(
        where: String,
        skipped: MutableList<String>,
        block: () -> T,
    ): T? = try {
        block()
    } catch (_: Exception) {
        skipped += where
        null
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
                // 权威编码是 JSON **数组**（`ClientProfile.toEntity` 走 headersToJson），
                // 不是 `k:v,k:v`：头里带冒号或逗号时 CSV 那份解回来是错的，而且
                // `toHeaderList` 读不懂它，恢复出来的预设等于没有请求头。
                headers = profile.headers.mapNotNull { pair ->
                    pair.takeIf { it.size >= 2 }?.let { it[0] to it[1] }
                }.headersToJson(),
                bodyPatch = profile.bodyPatch,
                protocols = profile.protocols.toCsv(),
                verified = profile.verified,
                builtinRev = profile.builtinRev,
                userEdited = profile.userEdited,
                sortOrder = profile.sortOrder,
            ),
        )
    }

    override suspend fun findProfileId(referenceKey: String): Long? =
        profileDao.findByBuiltinKey(referenceKey)?.id
            ?: profileDao.findAll().firstOrNull { it.name == referenceKey }?.id

    override suspend fun findProviderId(name: String, apiRoot: String): Long? =
        providerDao.findAll().firstOrNull { provider ->
            provider.name == name &&
                keyDao.findByProvider(provider.id).firstOrNull()?.settings?.apiRoot.orEmpty() == apiRoot
        }?.id

    override suspend fun insertProvider(provider: BackupProvider, groupId: Long?): Long {
        val stamp = now()
        return providerDao.insert(
            ProviderEntity(
                name = provider.name,
                note = provider.note,
                websiteUrl = provider.websiteUrl,
                checkWebsite = provider.checkWebsite,
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
                    // v3 起没有默认 Key，排序本身就是优先级（§6.3）。旧包里的 `isDefault`
                    // 于是只有一个含义能落得下来：**排最前**——用的就是 `MIGRATION_2_3`
                    // 当年给老默认 Key 的那个 -1（比任何正常 sortOrder 都小，且不动别的 Key）。
                    sortOrder = if (key.isDefault) LEGACY_DEFAULT_FIRST else key.sortOrder,
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
                favorite = model.favorite,
                needsReview = model.needsReview,
                catalogKey = model.catalogKey,
                firstSeenAt = now(),
                sortOrder = model.sortOrder,
            ),
        )
    }

    override suspend fun findSetting(key: String): BackupSetting? =
        appSettingDao.find(key)?.let { row ->
            // 本地那行密文解不开时不能抛：抛了整次恢复就回滚了。返回"这一项存在、值读不出"，
            // 让合并恢复按"本地已有"处理——本地坏数据不该被包里的版本悄悄盖掉。
            // "存在"要靠 `hasStoredBlob` 说，不能只靠两列的值：解不开时两列都是 null，
            // 只看值就等于把这一行读成空的，盖掉的正是我们想留住的那一份。
            val secret = row.valueBlob?.let { blob ->
                runCatching {
                    revealBytes(blob, FieldAad.ofSetting(row.key, COL_BLOB)).concatToString()
                }.getOrNull()
            }
            BackupSetting(
                key = row.key,
                value = row.value,
                encryptedValue = secret,
                hasStoredBlob = row.valueBlob != null,
            )
        }

    /**
     * 整行写回。
     *
     * 加密项在包里是明文（红线 2），这里用**本机 DEK + 同一个 AAD** 重新密封后写
     * `valueBlob`；直接把包里的字节搬过来是错的——那是上一台设备的密文，换设备解不开。
     * `@Upsert` 是整行覆盖，所以两列必须一起给：漏了 blob 就等于把 WebDAV 凭据清掉。
     */
    override suspend fun putSetting(setting: BackupSetting) {
        val plain = setting.encryptedValue?.toCharArray()?.toUtf8()
        try {
            appSettingDao.put(
                AppSettingEntity(
                    key = setting.key,
                    value = setting.value,
                    valueBlob = plain?.let { cipher.seal(it, FieldAad.ofSetting(setting.key, COL_BLOB)) },
                ),
            )
        } finally {
            plain?.zeroize()
        }
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
            quickModelProbe = probeQuickModel,
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

        /** `app_settings.valueBlob` 的列名，同时是 AAD 的一段（与 `RoomWebDavSettingsRepository` 一致）。 */
        const val COL_BLOB = "valueBlob"

        /** 旧包 `isDefault` 的落点：v2→v3 迁移当年用的就是 -1。 */
        const val LEGACY_DEFAULT_FIRST = -1
    }
}
