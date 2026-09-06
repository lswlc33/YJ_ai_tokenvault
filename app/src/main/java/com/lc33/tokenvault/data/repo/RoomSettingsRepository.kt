package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.data.dao.AppSettingDao
import com.lc33.tokenvault.data.entity.AppSettingEntity
import com.lc33.tokenvault.domain.AutoLockPolicy
import com.lc33.tokenvault.domain.AutoLockTimeout
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.repo.SettingsRepository
import com.lc33.tokenvault.probe.ProbeClassifier
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 设置项。这张表里没有秘密，所以整个类不碰 DEK，锁定态也能读写（§6.1 推论 2）。
 *
 * 两个实现选择：
 *
 * - **订阅整张表再挑那一个键**，而不是给每个键加一条 `WHERE key = :key` 的查询。
 *   这张表只有几十行，多读几行的代价可以忽略；而按键分查会让"加一个设置项"变成
 *   "加一条 SQL"，DAO 会随着设置项一起膨胀。代价是任何一项设置写入都会让这条流再发
 *   一次，所以后面必须跟 [distinctUntilChanged]——不跟的表现是每改一次别的开关，
 *   `AutoLocker` 就被重设一次时限（同一个值，但那是巧合而不是保证）。
 * - **值存 `value`（TEXT）而不是 `valueBlob`**：秒数不是秘密，而 blob 是给加密项留的。
 */
@Singleton
class RoomSettingsRepository @Inject constructor(
    private val dao: AppSettingDao,
) : SettingsRepository {

    override fun observeAutoLockTimeout(): Flow<AutoLockTimeout> = dao.observeAll()
        .map { rows -> AutoLockPolicy.decode(rows.firstOrNull { it.key == KEY_AUTO_LOCK }?.value) }
        .distinctUntilChanged()

    override suspend fun setAutoLockTimeout(timeout: AutoLockTimeout) {
        dao.put(AppSettingEntity(key = KEY_AUTO_LOCK, value = AutoLockPolicy.encode(timeout)))
    }

    override fun observeIdleLock(): Flow<Boolean> = dao.observeAll()
        .map { rows -> rows.firstOrNull { it.key == KEY_IDLE_LOCK }?.value.toBooleanSafe() }
        .distinctUntilChanged()

    override suspend fun setIdleLock(enabled: Boolean) {
        dao.put(AppSettingEntity(key = KEY_IDLE_LOCK, value = enabled.toString()))
    }

    override fun observeLockOnScreenOff(): Flow<Boolean> = dao.observeAll()
        .map { rows -> rows.firstOrNull { it.key == KEY_LOCK_ON_SCREEN_OFF }?.value.toBooleanSafe() }
        .distinctUntilChanged()

    override suspend fun setLockOnScreenOff(enabled: Boolean) {
        dao.put(AppSettingEntity(key = KEY_LOCK_ON_SCREEN_OFF, value = enabled.toString()))
    }

    override fun observeBalanceThresholds(): Flow<Map<String, Double>> = dao.observeAll()
        .map { rows -> decodeThresholds(rows.firstOrNull { it.key == KEY_BALANCE_THRESHOLDS }?.value) }
        .distinctUntilChanged()

    override suspend fun setBalanceThresholds(thresholds: Map<String, Double>) {
        dao.put(AppSettingEntity(key = KEY_BALANCE_THRESHOLDS, value = encodeThresholds(thresholds)))
    }

    override fun observeClientKeywords(): Flow<List<String>> = dao.observeAll()
        .map { rows -> decodeKeywords(rows.firstOrNull { it.key == KEY_CLIENT_KEYWORDS }?.value) }
        .distinctUntilChanged()

    override suspend fun setClientKeywords(keywords: List<String>) {
        dao.put(AppSettingEntity(key = KEY_CLIENT_KEYWORDS, value = encodeKeywords(keywords)))
    }

    override fun observeProxy(): Flow<String> = dao.observeAll()
        .map { rows -> rows.firstOrNull { it.key == KEY_PROXY }?.value.orEmpty() }
        .distinctUntilChanged()

    override suspend fun setProxy(hostPort: String) {
        dao.put(AppSettingEntity(key = KEY_PROXY, value = hostPort.trim()))
    }

    private companion object {
        /**
         * 键名照 §7.4 里的写法。
         *
         * **存的是秒数，不是下拉的下标**：存下标的话，以后在中间插一档就会让所有已存的
         * 设置悄悄改变含义，而没有任何迁移能发现它（用户选的「立即」变成「30 秒」）。
         */
        const val KEY_AUTO_LOCK = "autoLockSeconds"

        const val KEY_IDLE_LOCK = "idleLockSeconds"

        const val KEY_LOCK_ON_SCREEN_OFF = "lockOnScreenOff"

        const val KEY_BALANCE_THRESHOLDS = "balanceThresholds"

        const val KEY_CLIENT_KEYWORDS = "clientKeywords"

        const val KEY_PROXY = "httpProxy"

        /**
         * 阈值 → JSON 对象（键 = 币种代码，值 = 金额）。
         *
         * 顺序无关紧要（`Map` 本来就是无序的），所以用对象而不是数组；
         * 与 `ColumnCodecs.headersToJson` 不同——那里顺序是数据的一部分。
         */
        fun encodeThresholds(thresholds: Map<String, Double>): String = buildJsonObject {
            thresholds.forEach { (currency, amount) ->
                put(currency, JsonPrimitive(amount))
            }
        }.toString()

        /**
         * JSON → 阈值 map。**读方向单向容错**（同 `ColumnCodecs`）：不认识的值跳过，
         * 坏数据不拖垮整个仪表盘。没写过的键返回 [BalanceSnapshot.DEFAULT_THRESHOLDS]。
         */
        fun decodeThresholds(raw: String?): Map<String, Double> {
            if (raw == null) return BalanceSnapshot.DEFAULT_THRESHOLDS
            return runCatching {
                json.parseToJsonElement(raw)
                    .jsonObject
                    .entries
                    .mapNotNull { (currency, value) ->
                        val amount = value.jsonPrimitive.doubleOrNull ?: return@mapNotNull null
                        currency to amount
                    }
                    .toMap()
            }.getOrDefault(BalanceSnapshot.DEFAULT_THRESHOLDS)
        }

        /**
         * 关键词 → JSON 数组（元素顺序即显示顺序）。
         *
         * 顺序对匹配结果无关（关键词是 `contains` 任意命中），但对编辑页的展示有意义——
         * 用户按自己写的顺序看。所以用数组而不是集合。
         */
        fun encodeKeywords(keywords: List<String>): String = buildJsonArray {
            keywords.forEach { add(JsonPrimitive(it)) }
        }.toString()

        /**
         * JSON → 关键词列表。**读方向单向容错**：坏数据回退默认表。
         * 没写过的键返回 [ProbeClassifier.DEFAULT_CLIENT_KEYWORDS]。
         */
        fun decodeKeywords(raw: String?): List<String> {
            if (raw == null) return ProbeClassifier.DEFAULT_CLIENT_KEYWORDS
            return runCatching {
                json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                    element.jsonPrimitive.contentOrNull
                }
            }.getOrDefault(ProbeClassifier.DEFAULT_CLIENT_KEYWORDS)
        }

        /** 与 [com.lc33.tokenvault.data.mapper.ColumnCodecs] 同款：读方向单向容错。 */
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * `"true"` → true，其余（含 null / 坏值）→ false。
         *
         * 这两个开关默认都是关，而开关的「关」正是安全那一侧：坏数据不该让金库
         * 突然多出一条会自动锁定的路，也不该把一条用户亲手打开的锁定悄悄关掉。
         * 这里统一落回 false——与「没写过」同一语义，读方向单向容错。
         */
        private fun String?.toBooleanSafe(): Boolean = this == "true"
    }
}
