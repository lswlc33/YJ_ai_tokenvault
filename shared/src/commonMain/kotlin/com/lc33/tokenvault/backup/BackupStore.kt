package com.lc33.tokenvault.backup

interface BackupStore {
    suspend fun <R> inTransaction(block: suspend () -> R): R
    suspend fun readSnapshot(): VaultSnapshot
    suspend fun clearAll()
    suspend fun findOrInsertGroup(group: BackupGroup): Long
    suspend fun findOrInsertProfile(profile: BackupProfile): Long
    suspend fun findProviderId(name: String, websiteUrl: String?): Long?
    suspend fun insertProvider(provider: BackupProvider, groupId: Long?): Long
    suspend fun keyExists(providerId: Long, secret: String): Boolean
    suspend fun insertKey(providerId: Long, key: BackupApiKey, profileId: Long?): Long
    suspend fun findKeyId(providerId: Long, secret: String?): Long?
    suspend fun accountExists(providerId: Long, username: String?): Boolean
    suspend fun insertAccount(providerId: Long, account: BackupAccount)
    suspend fun modelExists(providerId: Long, keyId: Long?, modelId: String, protocol: String): Boolean
    suspend fun insertModel(providerId: Long, keyId: Long?, model: BackupModel)
    suspend fun findSetting(key: String): String?
    suspend fun putSetting(key: String, value: String?)
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
)
