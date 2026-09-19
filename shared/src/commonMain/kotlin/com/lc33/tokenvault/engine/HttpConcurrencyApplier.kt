package com.lc33.tokenvault.engine

import com.lc33.tokenvault.domain.repo.SettingsRepository
import com.lc33.tokenvault.net.ConcurrencyGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 把"最大并发数"这条设置接到运行时的闸上（§13.4）。
 *
 * 为什么要单独一个应用单例，而不是在设置页的 ViewModel 里写库时顺手 `gate.setLimit(...)`：
 * 那样这个值只有**用户亲手动过那一枚下拉**才会被应用一次，进程重启后没人读库里的值，
 * 于是页面上写着 32、实际跑 8。订阅接在进程上（与 [AutoRefresher]、`AutoLocker` 同一个理由），
 * 冷启动、备份恢复之后都会自己跟上。
 *
 * 它只做搬运：解码与兜底在 `HttpConcurrencyPolicy.decode`，"改档不抢占已在飞的请求"这类
 * 判定在 [ConcurrencyGate]。这里没有策略，所以也没有需要单独测的分支——除了"没 start 之前
 * 不许偷偷读库"，那条由 [start] 的幂等守卫保证，测试盯着它。
 */
class HttpConcurrencyApplier constructor(
    private val settings: SettingsRepository,
    private val gate: ConcurrencyGate,
    private val scope: CoroutineScope,
) {

    private var job: Job? = null

    /** 幂等：两端入口各调一次就够（Android `onCreate` / iOS `initIosApp`）。 */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            settings.observeMaxConcurrency().collect { gate.setLimit(it) }
        }
    }
}
