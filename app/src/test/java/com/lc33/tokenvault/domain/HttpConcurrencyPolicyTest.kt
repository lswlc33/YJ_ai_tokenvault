package com.lc33.tokenvault.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 最大并发数的档位与编解码（§13.4）。
 *
 * 与 [AutoRefreshPolicyTest] 同一类风险，而且后果更直白：下拉按下标取值，两边一旦错位，
 * 用户选「8」得到的是 32，等于一次把四倍请求打给上游——红线 29 挡的正是这个。
 * 所以这里既钉顺序，也钉"引擎那一档必须容得下最高档"，否则顶两档是假数。
 */
class HttpConcurrencyPolicyTest {

    @Test
    fun `五档与下拉一一对应，顺序不能变`() {
        // 顺序就是 `probe_max_concurrency_options` 里那五项的顺序。
        assertEquals(listOf(2, 4, 8, 16, 32), HttpConcurrencyPolicy.OPTIONS)
    }

    @Test
    fun `每一档下标取回来都是它自己`() {
        HttpConcurrencyPolicy.OPTIONS.forEachIndexed { index, count ->
            assertEquals(count, HttpConcurrencyPolicy.at(index))
            assertEquals(index, HttpConcurrencyPolicy.indexOf(count))
        }
    }

    @Test
    fun `库里存的是次数不是下标`() {
        // 断言字面量而不是 `encode(16)`：这一条守的就是"备份恢复到另一台机器时，
        // 读出来的到底是哪个数"——存下标的话，以后中间插一档就把老用户的含义改了。
        assertEquals("16", HttpConcurrencyPolicy.encode(16))
        assertEquals(16, HttpConcurrencyPolicy.decode("16"))
    }

    @Test
    fun `坏值与零都落回默认档而不是 1 也不是 0`() {
        // 0 尤其要挡：那是"一个请求都别想出去"，坏数据不能把整个应用的网络掐死。
        assertEquals(HttpConcurrencyPolicy.DEFAULT, HttpConcurrencyPolicy.decode(null))
        assertEquals(HttpConcurrencyPolicy.DEFAULT, HttpConcurrencyPolicy.decode(""))
        assertEquals(HttpConcurrencyPolicy.DEFAULT, HttpConcurrencyPolicy.decode("garbage"))
        assertEquals(HttpConcurrencyPolicy.DEFAULT, HttpConcurrencyPolicy.decode("0"))
        assertEquals(HttpConcurrencyPolicy.DEFAULT, HttpConcurrencyPolicy.decode("-5"))
    }

    @Test
    fun `表里没有的数落到默认档那一枚，下拉总有一项是选中的`() {
        // 库里可能是老版本写下的、如今不在档位表里的数。
        assertEquals(HttpConcurrencyPolicy.indexOf(HttpConcurrencyPolicy.DEFAULT), HttpConcurrencyPolicy.indexOf(999))
        assertEquals(HttpConcurrencyPolicy.DEFAULT, HttpConcurrencyPolicy.at(-1))
        assertEquals(HttpConcurrencyPolicy.DEFAULT, HttpConcurrencyPolicy.at(99))
    }

    @Test
    fun `默认档就是改动前安卓引擎的并发上限，所以默认是个空操作`() {
        // 8 = 这一版之前 `HttpEngine.android.kt` / `HttpEngine.jvm.kt` 里写死的 maxRequests。
        // 默认值改变既有行为，是这类"新加的设置"最容易悄悄造成的回归。
        assertEquals(8, HttpConcurrencyPolicy.DEFAULT)
    }

    @Test
    fun `最高档不超过引擎的并发上限`() {
        // 引擎那一档是"上限的上限"：它比最高档小的话，选 16/32 只是把请求从应用闸门口
        // 挪进 OkHttp 队列里排队，还一边排队一边占名额。
        assertEquals(32, HttpConcurrencyPolicy.MAX)
        assertEquals(HttpConcurrencyPolicy.OPTIONS.max(), HttpConcurrencyPolicy.MAX)
    }

    @Test
    fun `每主机上限是三，与限流实测一致`() {
        // 红线 29：挂 Cloudflare 的站同 host 约 2.4 秒内第 3 个请求就撞 1015。
        assertEquals(3, HttpConcurrencyPolicy.PER_HOST_MAX_REQUESTS)
    }
}
