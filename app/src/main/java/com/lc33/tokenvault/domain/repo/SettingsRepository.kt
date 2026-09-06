package com.lc33.tokenvault.domain.repo

import com.lc33.tokenvault.domain.AutoLockTimeout
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
}
