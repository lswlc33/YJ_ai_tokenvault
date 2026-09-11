package com.lc33.tokenvault.backup

/**
 * 备份引擎的数据访问门面（§12.1）。
 *
 * 存在理由：备份是安全关键路径，导出要 reveal 全部明文、恢复要两步写 + 字段级加密，
 * 这些都依赖 [com.lc33.tokenvault.data.repo.FieldCipher]（借 [VaultSession] 的 DEK）与
 * Room 的 AUTOINCREMENT 语义（红线 24 的 AAD 绑定 id）。把这一整面封装成接口，让
 * `engine/BackupEngine` 迁到 commonMain 后只做编排（自然键映射、去重、序列化、gzip），
 * 不再直接碰 Room 实体、DAO、AAD、两步写。
 *
 * 接口方法接收/返回 [BackupXxx] 明文类型（[BackupProvider] 等），加解密完全在实现侧完成：
 * - [readSnapshot] 返回的 [VaultSnapshot] 里 secret / balanceToken / username / password
 *   都是**明文**（实现侧 reveal 后用 CharArray 中间态擦掉）。
 * - [insertKey] / [insertAccount] 内部做"插空密文 → 用真 AAD 加密回填"两步写（红线 24）。
 * - 指纹判重（[keyExists] / [accountExists]）在实现侧用本机 DEK 重算（§12.1）。
 */
interface BackupStore {

    /**
     * 在单个事务里执行 [block]（覆盖恢复的"清空 + 导入"必须原子：中途失败不能留半库）。
     *
     * 实现侧委托给 Room 的 withTransaction。独立暴露这一条而不是把 restore 整体收进
     * store，是因为编排逻辑（自然键匹配、去重）属于 [BackupEngine]，事务边界属于数据层。
     */
    suspend fun <R> inTransaction(block: suspend () -> R): R

    /** 导出时的一次性全量明文快照（跨表引用已解析成自然键）。 */
    suspend fun readSnapshot(): VaultSnapshot

    // ------------------------------------------------------------------ 恢复：覆盖模式清理

    /** 清空整库（覆盖恢复第一步）。providers 靠外键 CASCADE 连带删 keys/accounts/models。 */
    suspend fun clearAll()

    // ------------------------------------------------------------------ 恢复：自然键解析

    /** 分组按 name 去重：已存在返回其 id，否则插入并返回新 id。 */
    suspend fun findOrInsertGroup(group: BackupGroup): Long

    /** 预设按 builtinKey（内置）或 name（自定义）去重：已存在返回其 id，否则插入。 */
    suspend fun findOrInsertProfile(profile: BackupProfile): Long

    /** 供应商按 (name, apiRoot) 自然键查找已有 id，不存在返回 null。 */
    suspend fun findProviderId(name: String, apiRoot: String): Long?

    // ------------------------------------------------------------------ 恢复：写入（含两步写 + 加密）

    /** 插入供应商。balanceToken 明文（若有）在实现侧用本机 DEK 加密 + 两步写回填。 */
    suspend fun insertProvider(provider: BackupProvider, groupId: Long?, profileId: Long?): Long

    /** 该供应商下是否已有同指纹密钥（指纹用本机 DEK 重算，§12.1）。 */
    suspend fun keyExists(providerId: Long, secret: String): Boolean

    /** 插入密钥（内部两步写 + 加密，红线 24）。恢复默认/停用标记一并处理。 */
    suspend fun insertKey(providerId: Long, key: BackupApiKey)

    /** 按密钥明文找已恢复的 Key id；secret 为 null 时返回默认 Key。 */
    suspend fun findKeyId(providerId: Long, secret: String?): Long?

    /** 该供应商下是否已有同指纹账号（usernameFp 用本机 DEK 重算）。 */
    suspend fun accountExists(providerId: Long, username: String?): Boolean

    /** 插入账号（内部两步写 + 加密）。 */
    suspend fun insertAccount(providerId: Long, account: BackupAccount)

    /** 该供应商下是否已有同 modelId 的模型。 */
    suspend fun modelExists(providerId: Long, keyId: Long?, modelId: String, protocol: String): Boolean

    /** 插入模型。 */
    suspend fun insertModel(providerId: Long, keyId: Long?, model: BackupModel)

    // ------------------------------------------------------------------ 恢复：设置

    /** 读一个设置项（白名单 key）。 */
    suspend fun findSetting(key: String): String?

    /** 写一个设置项。 */
    suspend fun putSetting(key: String, value: String?)
}

/**
 * 导出时的一次性全量明文快照。
 *
 * 字段与 [BackupPayload] 对齐，但 [deviceId] / [revision] 是本地信息（进 header 而非 payload）。
 * 所有 secret / token / username / password 已是**明文**（实现侧 reveal 完成），生命周期由
 * 调用方 [BackupEngine] 负责（用完即被序列化进加密包，中间态零残留）。
 */
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
