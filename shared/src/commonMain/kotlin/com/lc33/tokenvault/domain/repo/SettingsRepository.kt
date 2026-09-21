package com.lc33.tokenvault.domain.repo

import com.lc33.tokenvault.domain.AutoLockTimeout
import com.lc33.tokenvault.domain.DefaultProbeSettings
import com.lc33.tokenvault.domain.model.LastBackup
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.model.LogRetention
import com.lc33.tokenvault.domain.model.PredictiveBackExitDirection
import com.lc33.tokenvault.domain.model.PredictiveBackStyle
import kotlinx.coroutines.flow.Flow

/**
 * 软件自己的设置（`app_settings` 表）。
 *
 * **这一版只有自动锁定时限一项**，刻意没有把七个设置页一次接完：那些项里大半还没有
 * 消费方（前台空闲计时、屏幕关闭即锁定的代码都还没写），先落库只会得到一批"存下来了
 * 但没人读"的键，而红线 16 要求每个持久化字段都有明确的产生与消费路径。
 * 需要哪一项就在这里加一对方法，接口跟着长。
 *
 * 三条约束：
 *
 * 1. **这张表没有秘密**（WebDAV 凭据将来走 `valueBlob`，另说），所以整个仓库不碰 DEK，
 *    锁定态也能读写（§6.1 推论 2）。自动锁定时限必须在**锁定态**也读得到——
 *    应用是先起来、后解锁的，而 `AutoLocker` 从进程一开始就要知道该等多久。
 * 2. **`boot` 里那几项不在这里**（红线 31）：`themeMode` / `localeTag` / `onboarded` /
 *    生物识别开关的权威存储是 boot，因为解锁前就要读。两处都存就是"设置页画着一个值、
 *    真正生效的是另一个值"。
 * 3. 读一律是 `Flow`（红线 10）：设置页与 `AutoLocker` 订阅同一条流，所以不存在
 *    "改了设置但那边还是旧值"的窗口。
 */
interface SettingsRepository {

    /**
     * 切后台之后多久锁。
     *
     * **没写过时发默认值**（[com.lc33.tokenvault.domain.AutoLockPolicy.DEFAULT]），
     * 不是发 null 让调用方各自兜——那样"默认多少秒"就有几个调用方几份定义。
     */
    fun observeAutoLockTimeout(): Flow<AutoLockTimeout>

    suspend fun setAutoLockTimeout(timeout: AutoLockTimeout)

    /**
     * 前台空闲锁定（§7.4）。默认关闭。
     *
     * 开 = 用户在解锁态下 [AutoLockPolicy.IDLE_LOCK_SECONDS] 秒不碰屏幕就锁。
     * 关 = 只有切后台 / 屏幕关闭 / 手动这三条路能锁。
     */
    fun observeIdleLock(): Flow<Boolean>

    suspend fun setIdleLock(enabled: Boolean)

    /**
     * 屏幕关闭即锁定（§7.4）。默认关闭。
     *
     * 开 = 收到 `ACTION_SCREEN_OFF` 就锁，不等任何时限。关 = 屏幕关闭走普通的后台锁定。
     */
    fun observeLockOnScreenOff(): Flow<Boolean>

    suspend fun setLockOnScreenOff(enabled: Boolean)

    /**
     * 余额低额提醒阈值，按币种（§9.3、§13.4 探测设置页）。
     *
     * 键是 ISO 4217 币种代码，值是"低于这个数判 LOW"的金额。
     * **没写过时发 [com.lc33.tokenvault.domain.model.BalanceSnapshot.DEFAULT_THRESHOLDS]**，
     * 不是发空 map 让调用方自己填——否则"默认多少"就有几份定义（红线 15）。
     */
    fun observeBalanceThresholds(): Flow<Map<String, Double>>

    suspend fun setBalanceThresholds(thresholds: Map<String, Double>)

    /**
     * 客户端拦截关键词（§8.2、§13.4 探测设置页）。
     *
     * 命中任一关键词的响应判 `CLIENT_BLOCKED` 而不是 `UNAUTHORIZED`/`FORBIDDEN`。
     * **没写过时发 [com.lc33.tokenvault.probe.ProbeClassifier.DEFAULT_CLIENT_KEYWORDS]**。
     *
     * 关键词是匹配**上游协议内容**的，不是 UI 文案，所以不进 strings.xml（i18n-exempt）。
     */
    fun observeClientKeywords(): Flow<List<String>>

    suspend fun setClientKeywords(keywords: List<String>)

    /**
     * 客户端嗅探开关（§8.2、§13.4 探测设置页）。
     *
     * 开 = 探测遇 `CLIENT_BLOCKED` 时自动换鉴权头 / 试内置预设（[com.lc33.tokenvault.engine.ProbeEngine.trySniff]）；
     * 关 = 保留 `CLIENT_BLOCKED` 结论、不做任何重试。**没写过时发 true**（默认开，与既有行为一致）。
     */
    fun observeSniffClientProfile(): Flow<Boolean>

    suspend fun setSniffClientProfile(enabled: Boolean)

    /**
     * 更新渠道（§13.4 更新页）。下标与 `update_channels` 数组对齐：0=正式版、1=nightly。
     *
     * 渠道只有固定两档、不随版本演进，所以这里存下标而非另造枚举（与
     * [com.lc33.tokenvault.update.ReleaseMatcher.match] 的 `channel` 参数直接对应）。
     * **没写过时发 0**（正式版，安全那一侧——nightly 是 pre-release）。
     */
    fun observeUpdateChannel(): Flow<Int>

    suspend fun setUpdateChannel(channel: Int)

    /**
     * 新建供应商时的探测默认值（§8.3、红线 36）。**不是总开关**——权威在每家供应商
     * 自己的行上，这里只是「新建时拷进 `ProviderDraft`」的初值。
     *
     * **没写过时发 [com.lc33.tokenvault.domain.DefaultProbeSettings] 的默认值**
     * （`true/true/true/false`，与 `ProviderDraft` 的硬编码默认一致）。
     */
    fun observeDefaultProbeSettings(): Flow<DefaultProbeSettings>

    suspend fun setDefaultProbeSettings(settings: DefaultProbeSettings)

    /**
     * 自动刷新总开关（§13.4 探测设置页）。
     *
     * 开 = 进入解锁态时刷一轮，之后应用活着就按 [observeAutoRefreshIntervalMinutes] 再刷；
     * 关 = 只有用户自己按刷新才发请求。**没写过时发 true**（2026-09 决策：这一项要的就是
     * "打开应用就看到新数据"，默认关等于大多数人 never 打开它）。
     *
     * 默认开能接受的前提是自动路径只发零成本请求（红线 36）：端点与密钥连通性，加上用户
     * 自己开了自动获取的余额；消耗额度那一档永远只在手动路径上。
     *
     * 消费方是 [com.lc33.tokenvault.engine.AutoRefresher]，它是应用单例而不是设置页的
     * ViewModel——订阅活得比页面长，用户离开设置页定时器不能停。
     */
    fun observeAutoRefresh(): Flow<Boolean>

    suspend fun setAutoRefresh(enabled: Boolean)

    /**
     * 自动刷新的间隔，分钟（§13.4）。存储与档位见 [com.lc33.tokenvault.domain.AutoRefreshPolicy]。
     *
     * **没写过时发 [com.lc33.tokenvault.domain.AutoRefreshPolicy.DEFAULT_MINUTES]**。
     * 存分钟数而不是下拉下标：以后中间插一档时，用户已选的含义不能变。
     */
    fun observeAutoRefreshIntervalMinutes(): Flow<Int>

    suspend fun setAutoRefreshIntervalMinutes(minutes: Int)

    /**
     * 应用内 HTTP 的最大并发数（§13.4 探测设置页）。档位与兜底见
     * [com.lc33.tokenvault.domain.HttpConcurrencyPolicy]；**没写过时发
     * [com.lc33.tokenvault.domain.HttpConcurrencyPolicy.DEFAULT]**。
     *
     * 存并发数而不是下拉下标，理由与间隔一样：以后中间插一档，用户已选的含义不能变。
     *
     * 消费方是 [com.lc33.tokenvault.engine.HttpConcurrencyApplier]（应用单例），它把值推给
     * [com.lc33.tokenvault.net.ConcurrencyGate]。放在应用单例而不是设置页 ViewModel 上：
     * 否则用户从没进过那一页，整个进程就跑在硬默认上，而页面上写着另一档。
     *
     * 这一档管的是所有走 `HttpEngine` 的请求（探测、模型列表、官网可达性、余额、检查更新），
     * **不含 WebDAV**——备份用的是另一个 client，不该被探测挤住。
     */
    fun observeMaxConcurrency(): Flow<Int>

    suspend fun setMaxConcurrency(count: Int)

    /**
     * 底栏模糊（§13.4 外观页）。开 = 底栏对下方内容做背景模糊（`miuix-blur`，要 GPU）；
     * 关 = 底栏用实色背景。
     *
     * **没写过时发 true**（默认开，与既有的视觉一致）。
     */
    fun observeBlurNavBar(): Flow<Boolean>

    suspend fun setBlurNavBar(enabled: Boolean)

    /**
     * 预见式返回样式（§13.4 外观页）。没写过的键回退 [PredictiveBackStyle.Miuix]。
     *
     * 存稳定字符串而不是枚举下标：将来调整下拉顺序时，用户已选择的含义不能变。
     */
    fun observePredictiveBackStyle(): Flow<PredictiveBackStyle>

    suspend fun setPredictiveBackStyle(style: PredictiveBackStyle)

    /** 日志页默认展示等级。默认 INFO；DEBUG 只有在用户显式选择后才会显示出来。 */
    fun observeLogLevelFilter(): Flow<LogLevel>

    suspend fun setLogLevelFilter(level: LogLevel)

    /** 日志保留期。默认 7 天；永久保留是显式选项。 */
    fun observeLogRetention(): Flow<LogRetention>

    suspend fun setLogRetention(retention: LogRetention)

    /** Scale 样式的退出方向。没写过的键回退 [PredictiveBackExitDirection.AlwaysRight]。 */
    fun observePredictiveBackExitDirection(): Flow<PredictiveBackExitDirection>

    suspend fun setPredictiveBackExitDirection(direction: PredictiveBackExitDirection)

    /**
     * 是不是「会员」。设置页顶部那张卡的形态由它决定。
     *
     * **这是纯展示的娱乐开关，不承载任何权限**：没有任何功能读它来决定"能不能做某事"，
     * 也没有任何数据依赖它。所以它既不进 boot、也不加密，就是 `app_settings` 里一个布尔键。
     * **没写过时发 false**（普通用户），与这张卡引入之前的表现一致。
     */
    fun observeMember(): Flow<Boolean>

    suspend fun setMember(enabled: Boolean)

    /**
     * 最近一次**成功**备份（时间 + 落点）。null = 这台机器上从来没成功备份过。
     *
     * 存在的理由：同步页那张状态卡以前只活在 ViewModel 里，退页即丢，"上次备份"于是
     * 永远写着"还没有备份"，与远端真有包这件事对不上。
     */
    fun observeLastBackup(): Flow<LastBackup?>

    suspend fun setLastBackup(backup: LastBackup)

    /**
     * models.dev 目录：上次**成功**同步的时刻（epoch 毫秒）。0 = 从来没同步过。
     *
     * 消费方有两处：设置页那句"上次更新：…"，以及 7 天自动更新的判定
     * （`engine/CatalogSync`）。判定放在读侧而不是写侧定时，是因为这个 app 没有后台
     * 执行器（不为了一个目录引入 WorkManager），冷启动与进模型页时各查一次已经够准时。
     */
    fun observeCatalogLastSyncAt(): Flow<Long>

    suspend fun setCatalogLastSyncAt(epochMillis: Long)

    /**
     * 上次拿到的 `ETag`。条件请求用它：models.dev 的 `api.json` 是 4.7 MB，
     * 每 7 天无条件重下一遍在移动网络上不是"慢一点"，是"多烧一份用户的流量"。
     * 上游回 304 时原样保留，不写空。
     */
    fun observeCatalogEtag(): Flow<String?>

    suspend fun setCatalogEtag(etag: String?)

    /**
     * 要不要每 7 天自动更新目录。默认**开**。
     *
     * 关掉的语义只是"不自己发起下载"，手动「立即更新」不受它管；关掉之后模型页照常能用，
     * 只是分组与厂商信息停在最后一次同步的那份。开关存在的理由是有人就是不想起
     * 4.7 MB 的后台流量，而不是因为这功能可选。
     */
    fun observeCatalogAutoUpdate(): Flow<Boolean>

    suspend fun setCatalogAutoUpdate(enabled: Boolean)
}
