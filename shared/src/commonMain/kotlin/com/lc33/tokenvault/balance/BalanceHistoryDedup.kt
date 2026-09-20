package com.lc33.tokenvault.balance

import com.lc33.tokenvault.domain.model.BalanceSample

/**
 * 要不要为这次余额结果追加一条历史。**纯函数**，抽出来单独测。
 *
 * 两条规则，缺一趋势线就会脏：
 * 1. **没金额不记**：查询失败（`amount == null`）画不进折线，记进去只是一段空洞。
 * 2. **与上一条相同不记**：余额只在真用了 API 才变，探测频率远高于变化频率；不去重的话
 *    表里全是重复点，折线看着像高频采样其实原地不动，还平白把表撑大。判等同时看
 *    amount / used / currency——三者任一变了都算「变化点」，要留。
 *
 * 首次（[previous] 为 null）只要有金额就记：那是这把 Key 的第一个点，折线的起点。
 *
 * @param previous 这把 Key 已记的最近一条；从没记过传 null。
 * @param amount 本次可用余额。
 * @param used 本次已用。
 * @param currency 本次币种。
 */
fun shouldRecordSample(
    previous: BalanceSample?,
    amount: Double?,
    used: Double?,
    currency: String?,
): Boolean {
    if (amount == null) return false
    if (previous == null) return true
    return previous.amount != amount ||
        previous.used != used ||
        previous.currency != currency
}
