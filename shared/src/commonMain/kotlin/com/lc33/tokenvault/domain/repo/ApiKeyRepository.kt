package com.lc33.tokenvault.domain.repo

import com.lc33.tokenvault.domain.model.ApiKey
import kotlinx.coroutines.flow.Flow

/**
 * API 密钥。**这个应用里唯一真正要紧的那张表。**
 *
 * 明文只在三个方法的参数与返回值上出现，而且一律是 [CharArray]（红线 1：可擦）：
 * [add]、[replaceSecret]、[reveal]。其余方法只搬密文与元数据。
 *
 * [reveal] 是唯一能拿到明文的出口，所以它是**红线 1 的收口处**：
 * 调用方拿到的数组用完必须自己 `zeroize()`，而任何把它转成 `String` 的写法都等于
 * 把明文留在堆上到进程结束（`String` 擦不掉）。
 */
interface ApiKeyRepository {

    fun observeByProvider(providerId: Long): Flow<List<ApiKey>>

    /**
     * 全库的密钥。
     *
     * 给“要把所有家一起算”的地方用（仪表盘的健康分布、管理页每一行的聚合状态）。
     * **一条订阅而不是每家一条**：按家订阅是 N+1，而且新增一家时那一整组 Flow 要重建，
     * 于是列表会闪一下。不解密，所以锁定态也能读（§6.1 推论 3）。
     */
    fun observeAll(): Flow<List<ApiKey>>

    suspend fun find(id: Long): ApiKey?

    /**
     * 新增一张密钥。返回新行 id。
     *
     * @param secret 明文。实现会算指纹、加密、落库，**不擦它**——生命周期归调用方
     *   （擦调用方的数组等于替它做决定，而它可能还要用同一份明文去做别的事）。
     * @throws com.lc33.tokenvault.crypto.VaultLockedException 锁定态。
     */
    suspend fun add(providerId: Long, label: String, secret: CharArray): Long

    /** 换掉某一张的明文（用户发现自己粘错了、或者上游轮换了密钥）。指纹跟着重算。 */
    suspend fun replaceSecret(id: Long, secret: CharArray)

    /** 只改元数据（标签、排序）。**不碰密文**，所以不需要 DEK，锁定态也能调。 */
    suspend fun updateMeta(key: ApiKey)

    /**
     * 解出明文。
     *
     * @return 新分配的 [CharArray]，**调用方用完必须擦**。
     * @throws com.lc33.tokenvault.crypto.VaultLockedException 锁定态。
     * @throws com.lc33.tokenvault.crypto.DecryptionFailedException 密文坏了或被搬过行
     *   （AAD 不匹配）。**绝不返回 null**（红线 8）。
     */
    suspend fun reveal(id: Long): CharArray

    /** 设为默认。同一事务里清掉这家其它的默认标记（红线 6.3）。 */
    suspend fun setDefault(providerId: Long, keyId: Long)

    suspend fun setEnabled(id: Long, enabled: Boolean)

    /** 删除。删掉默认那张之后自动把 `sortOrder` 最小的启用 Key 顶上（红线 6.3）。 */
    suspend fun delete(id: Long)
}
