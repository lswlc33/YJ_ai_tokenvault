package com.lc33.tokenvault.domain.repo

import com.lc33.tokenvault.domain.AutoLockTimeout
import com.lc33.tokenvault.domain.DefaultProbeSettings
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
     * 手动 HTTP 代理（§7.5、§13.4 探测设置页）。`host:port` 字符串，空 = 走系统代理。
     *
     * 代理对国内用户是刚需（访问 GitHub、被墙的中转站）。**没写过时发空串**（系统代理）。
     */
    fun observeProxy(): Flow<String>

    suspend fun setProxy(hostPort: String)

    /**
     * 客户端嗅探开关（§8.2、§13.4 探测设置页）。
     *
     * 开 = 探测遇 `CLIENT_BLOCKED` 时自动换鉴权头 / 试内置预设（[com.lc33.tokenvault.engine.ProbeEngine.trySniff]）；
     * 关 = 保留 `CLIENT_BLOCKED` 结论、不做任何重试。**没写过时发 true**（默认开，与既有行为一致）。
     */
    fun observeSniffClientProfile(): Flow<Boolean>

    suspend fun setSniffClientProfile(enabled: Boolean)

    /**
     * 复制密钥/密码后多久自动清空剪贴板（§7.5、§13.4 安全设置页）。
     *
     * 秒数，`0` = 从不（[com.lc33.tokenvault.domain.ClipboardClearPolicy] 里就是这一档）。
     * **没写过时发 60**（默认）。
     */
    fun observeClipboardClearSeconds(): Flow<Int>

    suspend fun setClipboardClearSeconds(seconds: Int)

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
}
