package com.lc33.tokenvault.backup

interface BackupStore {
    suspend fun <R> inTransaction(block: suspend () -> R): R
    suspend fun readSnapshot(): VaultSnapshot
    suspend fun clearAll()
    suspend fun findOrInsertGroup(group: BackupGroup): Long
    suspend fun findOrInsertProfile(profile: BackupProfile): Long

    /**
     * 按"预设引用键"（内置的 `builtinKey`，自定义的 name）找**已存在**的预设行。
     *
     * 只读、不插：红线 4 起内置预设不再进包，所以引用它们的 Key 在包里找不到对应条目，
     * 归位只能查库（`ProfileSeeder` 每次启动都会种好）。这里绝不能顺手插入——种出一条
     * 空的内置预设会把真指纹永久盖掉。
     */
    suspend fun findProfileId(referenceKey: String): Long?
    suspend fun findProviderId(name: String, apiRoot: String): Long?
    suspend fun insertProvider(provider: BackupProvider, groupId: Long?): Long
    suspend fun keyExists(providerId: Long, secret: String): Boolean
    suspend fun insertKey(providerId: Long, key: BackupApiKey, profileId: Long?): Long
    suspend fun findKeyId(providerId: Long, secret: String?): Long?
    suspend fun accountExists(providerId: Long, username: String?): Boolean
    suspend fun insertAccount(providerId: Long, account: BackupAccount)
    suspend fun modelExists(providerId: Long, keyId: Long?, modelId: String, protocol: String): Boolean
    suspend fun insertModel(providerId: Long, keyId: Long?, model: BackupModel)

    /**
     * 整行读回一条设置。
     *
     * 返回值而不是 `String?`：加密项落在 `valueBlob` 上、`value` 是 null，只回 String
     * 会让合并恢复把"已经有密文"误判成"这一项还没有"，于是把凭据覆盖掉。
     */
    suspend fun findSetting(key: String): BackupSetting?

    /** 整行写回（两列都要给），理由同 [findSetting]。 */
    suspend fun putSetting(setting: BackupSetting)
}

data class VaultSnapshot(
    val deviceId: String,
    val revision: Long,
    val groups: List<BackupGroup>,
    val providers: List<BackupProvider>,
    val apiKeys: List<BackupApiKey>,
    val providerAccounts: List<BackupAccount>,
    val models: List<BackupModel>,
    val clientProfiles: List<BackupProfile>,
    val appSettings: List<BackupSetting>,

    /**
     * 因为**解不开密文**而被跳过的行（`表名:id`）。
     *
     * 一行坏密文不该毁掉整份导出（`ColumnCodecs` 的单向容错立场），但也不能无声吞掉——
     * 由 `BackupEngine` 写成一条 WARN 日志。这里只放定位信息，绝不放密文或明文。
     */
    val skippedRows: List<String> = emptyList(),
)
