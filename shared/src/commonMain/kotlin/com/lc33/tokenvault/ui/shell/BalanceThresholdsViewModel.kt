package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.domain.repo.SettingsRepository
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 余额低额阈值编辑（§9.3、§13.4 探测设置页的二级页）。
 *
 * 只编辑**已认识的币种**（USD / CNY）——这是默认阈值里仅有的两个，也是中转站余额
 * 最常见的币种。余额引擎里出现的其它币种（如 `UNKNOWN`）没有阈值、也永远不判 LOW
 * （红线 15：查不到该币种时不猜一个阈值）。
 *
 * 输入框用字符串状态而不是直接绑 `Double`：这样"正在输入 5."这种中间态不会因为
 * `toDoubleOrNull()` 失败就被当成非法、也不会因为 `Double` 的精度在输入过程中反复
 * 抖动。只在保存时做一次解析与校验。
 */
class BalanceThresholdsViewModel constructor(
    private val settings: SettingsRepository,
    private val failures: SettingsFailures,
) : ViewModel() {

    /**
     * 已存的阈值。**null = 还没读到**（Room 的首帧是异步的）。
     *
     * 不用 [SharingStarted.Eagerly] 加一个默认值：那样首帧拿到的永远是默认 `5/30`，
     * 而页面用 `remember` 初始化输入框时就会吃进这个假值、再也更新不成真实值。
     * 必须区分「没读到」和「读到了默认值」，所以初始值用 null，页面读到非空才建输入框。
     */
    val thresholds: StateFlow<Map<String, Double>?> = settings.observeBalanceThresholds()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * 写库成功。**由这里发、页面收到才退回**：以前是先回"已保存"再异步写库，
     * 写失败时用户已经离开这一页，而阈值还是旧的（下次低额判断就用错了线）。
     */
    private val _saved = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

    /**
     * 解析并校验一个输入框（规则见 [parseBalanceThreshold]）。
     *
     * 单独暴露给页面，是为了让"哪一格填错了"由页面自己逐格问——校验规则只在这里说一次，
     * 页面不会另算出一份和保存时不一致的判断。
     */
    fun parse(text: String): Double? = parseBalanceThreshold(text)

    /**
     * 保存。解析两个输入框 → 校验（非负的有限数）→ 写库。
     *
     * 校验失败返回 [SaveResult.Invalid]，调用方据此在对应输入框下提示，而不是弹窗。
     * 校验通过返回 [SaveResult.Accepted]：**这只表示写入已发起**，退出这一页要等 [saved]。
     */
    fun save(usdText: String, cnyText: String): SaveResult {
        val usd = parse(usdText) ?: return SaveResult.Invalid
        val cny = parse(cnyText) ?: return SaveResult.Invalid
        viewModelScope.launch {
            runCatching { settings.setBalanceThresholds(mapOf("USD" to usd, "CNY" to cny)) }
                .onSuccess { _saved.tryEmit(Unit) }
                // 写不进去时这一页留在原地（不会退回），另由 SettingsFailures 报一条提示。
                .onFailure { failures.report() }
        }
        return SaveResult.Accepted
    }

    /** 保存结果。 */
    sealed interface SaveResult {
        /** 校验通过，写入已在路上；落库成功由 [saved] 说。 */
        data object Accepted : SaveResult
        data object Invalid : SaveResult
    }
}

/**
 * 一个阈值输入：非负的**有限**数，非法返回 null。
 *
 * `isFinite()` 不是多余的：`"Infinity"` 与 `"NaN"` 都能被 `toDoubleOrNull()` 解析成功，
 * 而只判 `< 0.0` 会把两个都放行。存成 NaN 之后每一次「余额 < 阈值」都是 false，这一档
 * 就永久不再判低额；存成 Infinity 更糟——任何余额都低于它，全都判低额。两个方向都是
 * 悄悄错，所以当场拒绝，而不是等到比较的时候才发现。
 */
fun parseBalanceThreshold(text: String): Double? =
    text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
