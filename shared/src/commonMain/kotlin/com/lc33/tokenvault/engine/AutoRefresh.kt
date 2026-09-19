package com.lc33.tokenvault.engine

import com.lc33.tokenvault.domain.AutoRefreshPolicy
import com.lc33.tokenvault.domain.repo.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 一轮刷新要做的事。
 *
 * 抽出来是因为这一块真正有逻辑的是**时机**（开关、间隔、解锁后补一轮），而"刷什么"
 * 早就有既定答案：与仪表盘顶栏那颗刷新按钮同样的三件事。两者分开之后，
 * [AutoRefresher] 的时序能在 JVM 单测里钉住，不用把两个引擎拖进测试。
 */
fun interface RefreshRound {

    /** 发一轮刷新。返回 false = 这一轮没发出去（例如探测已经在跑）。 */
    suspend fun run(): Boolean
}

/**
 * 自动刷新（§13.4 探测设置页）。三条时机：
 *
 * 1. **进入解锁态**：由 [onUnlocked] 触发一次（`AppRoot` 在 `LockGate` 的解锁分支里调），
 *    这就是用户要的"每次打开 APP 自动刷新数据"；
 * 2. **应用运行期间按间隔**：[start] 里那条循环，跑在应用作用域上；
 * 3. **改设置立刻生效**：同一个订阅既发初值也发变更，所以关掉开关定时器就停，
 *    改间隔下一轮就按新间隔等，不需要重启应用。
 *
 * 它是应用单例而不是设置页的 ViewModel：定时器必须活得比任何一页长，否则"用户划走
 * 探测设置页"就把间隔刷新停了。这个形状与 [com.lc33.tokenvault.platform.AutoLocker] 一致。
 *
 * 为什么所有分支都先问 [ProbeSession.isUnlocked]：探测要 reveal 密钥、余额要解密字段，
 * 锁定态发这一轮只会得到一轮全失败，还会往审计日志里灌一片没有后果的错误。
 *
 * 开关**默认开**（[SettingsRepository.observeAutoRefresh]），所以从"进程起来"到"用户
 * 第一次解锁"之间一轮都不该发出去——上面那条 isUnlocked 检查不是兜底，是这一条路径的
 * 主闸。
 */
class AutoRefresher constructor(
    private val settings: SettingsRepository,
    private val session: ProbeSession,
    private val round: RefreshRound,
    private val scope: CoroutineScope,
) {

    private var configJob: Job? = null
    private var tickJob: Job? = null

    @Volatile
    private var enabled = false

    @Volatile
    private var intervalMillis = AutoRefreshPolicy.DEFAULT_MINUTES * MINUTE_MILLIS

    /** 有一轮正在发出去。挡的是"解锁那一刷恰好撞上到点那一刷"，不是挡探测引擎内部。 */
    @Volatile
    private var roundInFlight = false

    /** 幂等：两端入口各调一次就够（Android `onCreate` / iOS `initIosApp`）。 */
    fun start() {
        if (configJob?.isActive == true) return
        configJob = scope.launch {
            combine(
                settings.observeAutoRefresh(),
                settings.observeAutoRefreshIntervalMinutes(),
            ) { on, minutes -> on to minutes }
                .collect { (on, minutes) -> applyConfig(on, minutes) }
        }
    }

    /**
     * 解锁完成时由界面调一次。
     *
     * 不靠"启动时那一次"是因为应用先起来、后解锁（§6.1），解锁前发的那一轮必然全废；
     * 也不靠定时器是因为那要等满一个间隔，而用户刚打开应用看到的就是旧数据。
     */
    fun onUnlocked() {
        if (!enabled || !session.isUnlocked) return
        runRoundNow()
        // 间隔重新对齐到"从现在算"：否则解锁这一刷之后最多再等几十秒就又是一轮。
        restartTick()
    }

    private fun applyConfig(on: Boolean, minutes: Int) {
        val wasEnabled = enabled
        enabled = on
        intervalMillis = minutes.coerceAtLeast(1) * MINUTE_MILLIS
        if (!on) {
            tickJob?.cancel()
            tickJob = null
            return
        }
        // **只有"从关到开"这一边才立刻刷一轮**：用户刚把开关拨到开，值得立刻看到结果，
        // 而不是等满一个间隔才确认它生效了。反过来，只改间隔不该再发一轮——那一轮不在
        // 任何人的预期里（改的是"多久刷一次"，不是"现在刷一次"）。
        if (!wasEnabled && session.isUnlocked) runRoundNow()
        restartTick()
    }

    private fun restartTick() {
        tickJob?.cancel()
        if (!enabled) return
        tickJob = scope.launch {
            while (true) {
                delay(intervalMillis)
                if (session.isUnlocked) runRoundOnce()
            }
        }
    }

    private fun runRoundNow() {
        scope.launch { runRoundOnce() }
    }

    private suspend fun runRoundOnce() {
        if (!enabled || !session.isUnlocked || roundInFlight) return
        // 解锁那一刷与到点那一刷可能撞在一起（用户正好在定时器响的那一刻解锁），
        // 而间隔只该有一轮。探测引擎自己挡第二轮（`start()` 返回 false），
        // 但余额与连通性不挡，所以这一层也得挡。
        roundInFlight = true
        try {
            // 一轮失败不该把定时器打死：原因（网络、凭据、限流）都由引擎写进审计日志，
            // 下一轮照常试。
            runCatching { round.run() }
        } finally {
            roundInFlight = false
        }
    }

    companion object {
        private const val MINUTE_MILLIS = 60_000L
    }
}

/**
 * 生产用的一轮刷新：探测（不花钱那一档）+ 官网连通性 + 余额。
 *
 * 与仪表盘/管理页顶栏刷新做的是同一件事，所以这里不新发明"自动版只刷一半"的口径：
 * 手动按与自动跑出来的数据必须是一样新的，否则用户会说"我没点它怎么变了"。
 */
class VaultRefreshRound constructor(
    private val probeEngine: ProbeEngine,
    private val balanceEngine: BalanceEngine,
) : RefreshRound {

    override suspend fun run(): Boolean {
        // 正在探测时 start() 返回 false（不叠加第二轮，探测要花钱），
        // 但连通性与余额照刷——手动路径也是这个行为。
        val started = probeEngine.start()
        probeEngine.refreshReachability()
        runCatching { balanceEngine.refreshAll() }
        return started
    }
}
