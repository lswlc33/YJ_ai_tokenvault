package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.data.dao.AppSettingDao
import com.lc33.tokenvault.data.entity.AppSettingEntity
import com.lc33.tokenvault.domain.AutoLockPolicy
import com.lc33.tokenvault.domain.AutoLockTimeout
import com.lc33.tokenvault.domain.ClipboardClearPolicy
import com.lc33.tokenvault.domain.DefaultProbeSettings
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.model.LogRetention
import com.lc33.tokenvault.domain.model.PredictiveBackExitDirection
import com.lc33.tokenvault.domain.model.PredictiveBackStyle
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import com.lc33.tokenvault.domain.repo.SettingsRepository
import com.lc33.tokenvault.probe.ProbeClassifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
class RoomSettingsRepository constructor(
    private val dao: AppSettingDao,
    private val audit: AuditLogRepository? = null,
) : SettingsRepository {

    override fun observeAutoLockTimeout(): Flow<AutoLockTimeout> = dao.observeAll()
        .map { rows -> AutoLockPolicy.decode(rows.firstOrNull { it.key == KEY_AUTO_LOCK }?.value) }
        .distinctUntilChanged()

    override suspend fun setAutoLockTimeout(timeout: AutoLockTimeout) {
        dao.put(AppSettingEntity(key = KEY_AUTO_LOCK, value = AutoLockPolicy.encode(timeout)))
        auditChange(KEY_AUTO_LOCK)
    }

    override fun observeIdleLock(): Flow<Boolean> = dao.observeAll()
        .map { rows -> rows.firstOrNull { it.key == KEY_IDLE_LOCK }?.value.toBooleanSafe() }
        .distinctUntilChanged()

    override suspend fun setIdleLock(enabled: Boolean) {
        dao.put(AppSettingEntity(key = KEY_IDLE_LOCK, value = enabled.toString()))
        auditChange(KEY_IDLE_LOCK)
    }

    override fun observeLockOnScreenOff(): Flow<Boolean> = dao.observeAll()
        .map { rows -> rows.firstOrNull { it.key == KEY_LOCK_ON_SCREEN_OFF }?.value.toBooleanSafe() }
        .distinctUntilChanged()

    override suspend fun setLockOnScreenOff(enabled: Boolean) {
        dao.put(AppSettingEntity(key = KEY_LOCK_ON_SCREEN_OFF, value = enabled.toString()))
        auditChange(KEY_LOCK_ON_SCREEN_OFF)
    }

    override fun observeBalanceThresholds(): Flow<Map<String, Double>> = dao.observeAll()
        .map { rows -> decodeThresholds(rows.firstOrNull { it.key == KEY_BALANCE_THRESHOLDS }?.value) }
        .distinctUntilChanged()

    override suspend fun setBalanceThresholds(thresholds: Map<String, Double>) {
        dao.put(AppSettingEntity(key = KEY_BALANCE_THRESHOLDS, value = encodeThresholds(thresholds)))
        auditChange(KEY_BALANCE_THRESHOLDS)
    }

    override fun observeClientKeywords(): Flow<List<String>> = dao.observeAll()
        .map { rows -> decodeKeywords(rows.firstOrNull { it.key == KEY_CLIENT_KEYWORDS }?.value) }
        .distinctUntilChanged()

    override suspend fun setClientKeywords(keywords: List<String>) {
        dao.put(AppSettingEntity(key = KEY_CLIENT_KEYWORDS, value = encodeKeywords(keywords)))
        auditChange(KEY_CLIENT_KEYWORDS)
    }

    override fun observeProxy(): Flow<String> = dao.observeAll()
        .map { rows -> rows.firstOrNull { it.key == KEY_PROXY }?.value.orEmpty() }
        .distinctUntilChanged()

    override suspend fun setProxy(hostPort: String) {
        dao.put(AppSettingEntity(key = KEY_PROXY, value = hostPort.trim()))
        auditChange(KEY_PROXY)
    }

    override fun observeSniffClientProfile(): Flow<Boolean> = dao.observeAll()
        .map { rows -> rows.firstOrNull { it.key == KEY_SNIFF_CLIENT_PROFILE }?.value.toBooleanDefaultTrue() }
        .distinctUntilChanged()

    override suspend fun setSniffClientProfile(enabled: Boolean) {
        dao.put(AppSettingEntity(key = KEY_SNIFF_CLIENT_PROFILE, value = enabled.toString()))
        auditChange(KEY_SNIFF_CLIENT_PROFILE)
    }

    override fun observeClipboardClearSeconds(): Flow<Int> = dao.observeAll()
        .map { rows -> ClipboardClearPolicy.decode(rows.firstOrNull { it.key == KEY_CLIPBOARD_CLEAR }?.value) }
        .distinctUntilChanged()

    override suspend fun setClipboardClearSeconds(seconds: Int) {
        dao.put(AppSettingEntity(key = KEY_CLIPBOARD_CLEAR, value = ClipboardClearPolicy.encode(seconds)))
        auditChange(KEY_CLIPBOARD_CLEAR)
    }

    override fun observeUpdateChannel(): Flow<Int> = dao.observeAll()
        .map { rows -> rows.firstOrNull { it.key == KEY_UPDATE_CHANNEL }?.value?.trim()?.toIntOrNull()?.takeIf { it in 0..1 } ?: 0 }
        .distinctUntilChanged()

    override suspend fun setUpdateChannel(channel: Int) {
        dao.put(AppSettingEntity(key = KEY_UPDATE_CHANNEL, value = channel.toString()))
        auditChange(KEY_UPDATE_CHANNEL)
    }

    override fun observeDefaultProbeSettings(): Flow<DefaultProbeSettings> = dao.observeAll()
        .map { rows -> decodeDefaultProbe(rows.firstOrNull { it.key == KEY_DEFAULT_PROBE }?.value) }
        .distinctUntilChanged()

    override suspend fun setDefaultProbeSettings(settings: DefaultProbeSettings) {
        dao.put(AppSettingEntity(key = KEY_DEFAULT_PROBE, value = encodeDefaultProbe(settings)))
        auditChange(KEY_DEFAULT_PROBE)
    }

    override fun observeLogLevelFilter(): Flow<LogLevel> = dao.observeAll()
        .map { rows ->
            LogLevel.fromWireName(
                rows.firstOrNull { it.key == KEY_LOG_LEVEL_FILTER }?.value ?: LogLevel.INFO.wireName,
            )
        }
        .distinctUntilChanged()

    override suspend fun setLogLevelFilter(level: LogLevel) {
        dao.put(AppSettingEntity(key = KEY_LOG_LEVEL_FILTER, value = level.wireName))
        auditChange(KEY_LOG_LEVEL_FILTER)
    }

    override fun observeLogRetention(): Flow<LogRetention> = dao.observeAll()
        .map { rows ->
            when (val raw = rows.firstOrNull { it.key == KEY_LOG_RETENTION_DAYS }?.value?.trim()) {
                RETENTION_FOREVER -> LogRetention.FOREVER
                null, "" -> LogRetention.SEVEN_DAYS
                else -> LogRetention.fromDays(raw.toIntOrNull())
            }
        }
        .distinctUntilChanged()

    override suspend fun setLogRetention(retention: LogRetention) {
        dao.put(
            AppSettingEntity(
                key = KEY_LOG_RETENTION_DAYS,
                value = retention.days?.toString() ?: RETENTION_FOREVER,
            ),
        )
        auditChange(KEY_LOG_RETENTION_DAYS)
    }

    override fun observeBlurNavBar(): Flow<Boolean> = dao.observeAll()
        .map { rows -> rows.firstOrNull { it.key == KEY_BLUR_NAV_BAR }?.value.toBooleanDefaultTrue() }
        .distinctUntilChanged()

    override suspend fun setBlurNavBar(enabled: Boolean) {
        dao.put(AppSettingEntity(key = KEY_BLUR_NAV_BAR, value = enabled.toString()))
        auditChange(KEY_BLUR_NAV_BAR)
    }

    override fun observePredictiveBackStyle(): Flow<PredictiveBackStyle> = dao.observeAll()
        .map { rows -> PredictiveBackStyle.fromStorage(rows.firstOrNull { it.key == KEY_PREDICTIVE_BACK_STYLE }?.value) }
        .distinctUntilChanged()

    override suspend fun setPredictiveBackStyle(style: PredictiveBackStyle) {
        dao.put(AppSettingEntity(key = KEY_PREDICTIVE_BACK_STYLE, value = style.storageValue))
        auditChange(KEY_PREDICTIVE_BACK_STYLE)
    }

    override fun observePredictiveBackExitDirection(): Flow<PredictiveBackExitDirection> = dao.observeAll()
        .map { rows ->
            PredictiveBackExitDirection.fromStorage(
                rows.firstOrNull { it.key == KEY_PREDICTIVE_BACK_EXIT_DIRECTION }?.value,
            )
        }
        .distinctUntilChanged()

    override suspend fun setPredictiveBackExitDirection(direction: PredictiveBackExitDirection) {
        dao.put(
            AppSettingEntity(
                key = KEY_PREDICTIVE_BACK_EXIT_DIRECTION,
                value = direction.storageValue,
            ),
        )
        auditChange(KEY_PREDICTIVE_BACK_EXIT_DIRECTION)
    }

    private suspend fun auditChange(key: String) {
        audit.recordSafe(LogLevel.INFO, LogCategory.VAULT, "setting changed", "key=$key")
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

        const val KEY_SNIFF_CLIENT_PROFILE = "sniffClientProfile"

        const val KEY_CLIPBOARD_CLEAR = "clipboardClearSeconds"

        const val KEY_UPDATE_CHANNEL = "updateChannel"

        const val KEY_DEFAULT_PROBE = "defaultProbe"

        const val KEY_LOG_LEVEL_FILTER = "logLevelFilter"

        const val KEY_LOG_RETENTION_DAYS = "logRetentionDays"

        /** 永久保留的存储值；与 null 区分于“坏数据落回默认 7 天”。 */
        const val RETENTION_FOREVER = "forever"

        const val KEY_BLUR_NAV_BAR = "blurNavBar"

        const val KEY_PREDICTIVE_BACK_STYLE = "predictiveBackStyle"

        const val KEY_PREDICTIVE_BACK_EXIT_DIRECTION = "predictiveBackExitDirection"

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

        /**
         * 默认探测值 → JSON 对象（五个布尔，键名照 [DefaultProbeSettings] 字段名）。
         *
         * 用对象而不是数组，理由同 [encodeThresholds]：字段顺序无关紧要，
         * 未来加字段只增不改，旧值不丢。
         */
        fun encodeDefaultProbe(settings: DefaultProbeSettings): String = buildJsonObject {
            put(KEY_PROBE_REACHABILITY, JsonPrimitive(settings.reachability))
            put(KEY_PROBE_KEYS, JsonPrimitive(settings.keys))
            put(KEY_PROBE_BALANCE, JsonPrimitive(settings.balance))
            put(KEY_PROBE_MODELS, JsonPrimitive(settings.models))
            put(KEY_PROBE_MODEL_REACHABILITY, JsonPrimitive(settings.modelReachability))
        }.toString()

        /**
         * JSON → 默认探测值。**读方向单向容错**：缺哪个字段就用 [DefaultProbeSettings]
         * 的默认值补，坏数据不拖垮新建流程。没写过的键返回全默认。
         */
        fun decodeDefaultProbe(raw: String?): DefaultProbeSettings {
            if (raw == null) return DefaultProbeSettings()
            val defaults = DefaultProbeSettings()
            return runCatching {
                val obj = json.parseToJsonElement(raw).jsonObject
                DefaultProbeSettings(
                    reachability = obj[KEY_PROBE_REACHABILITY]?.jsonPrimitive?.booleanOrNull ?: defaults.reachability,
                    keys = obj[KEY_PROBE_KEYS]?.jsonPrimitive?.booleanOrNull ?: defaults.keys,
                    balance = obj[KEY_PROBE_BALANCE]?.jsonPrimitive?.booleanOrNull ?: defaults.balance,
                    modelReachability = obj[KEY_PROBE_MODEL_REACHABILITY]?.jsonPrimitive?.booleanOrNull
                        ?: defaults.modelReachability,
                    // 旧包里的 models 是“逐模型付费探测”，不是“模型列表自动检测”。
                    // 只有同包出现 modelReachability 才说明它是 v2 语义；否则迁移时重置为关。
                    models = if (obj.containsKey(KEY_PROBE_MODEL_REACHABILITY)) {
                        obj[KEY_PROBE_MODELS]?.jsonPrimitive?.booleanOrNull ?: defaults.models
                    } else {
                        false
                    },
                )
            }.getOrDefault(defaults)
        }

        const val KEY_PROBE_REACHABILITY = "reachability"

        const val KEY_PROBE_KEYS = "keys"

        const val KEY_PROBE_BALANCE = "balance"

        const val KEY_PROBE_MODELS = "models"

        const val KEY_PROBE_MODEL_REACHABILITY = "modelReachability"

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

        /**
         * `"false"` → false，其余（含 null / 坏值）→ true。
         *
         * 嗅探开关默认**开**：它的「开」是增强可用性那一侧（客户端被拦时自动找能用的
         * 伪装），坏数据不该让探测失去这条自动兜底。与 [toBooleanSafe] 方向相反——
         * 两个开关的安全侧不同，一个往 false 倒、一个往 true 倒。
         */
        private fun String?.toBooleanDefaultTrue(): Boolean = this != "false"
    }
}
