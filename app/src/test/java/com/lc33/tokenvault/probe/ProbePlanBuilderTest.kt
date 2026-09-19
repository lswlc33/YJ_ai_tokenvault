package com.lc33.tokenvault.probe

import com.lc33.tokenvault.domain.KeyHealth
import com.lc33.tokenvault.domain.ProbeLevel
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.KeyProbeSettings
import com.lc33.tokenvault.domain.model.KeySettings
import com.lc33.tokenvault.domain.model.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbePlanBuilderTest {

    private fun provider(id: Long) = Provider(id = id, name = "Provider $id")

    private fun key(
        id: Long,
        providerId: Long,
        baseUrl: String = "https://api$providerId.example.test/v1",
        protocols: Set<Protocol> = setOf(Protocol.CHAT),
        probe: KeyProbeSettings = KeyProbeSettings(),
        timeoutSeconds: Int? = null,
    ) = ApiKey(
        id = id,
        providerId = providerId,
        label = "key$id",
        note = "",
        secretEnc = ByteArray(0),
        fingerprint = "fp$id",
        settings = KeySettings(
            apiBaseUrl = baseUrl,
            apiRoot = baseUrl.removeSuffix("/v1"),
            supportedProtocols = protocols,
            probe = probe,
            timeoutSeconds = timeoutSeconds,
        ),
        health = KeyHealth.UNKNOWN,
    )

    @Test
    fun `探测总闸关掉的那家跳过`() {
        val p = provider(1)
        val keys = listOf(key(10, 1, probe = KeyProbeSettings(enabled = false)))
        val plan = ProbePlanBuilder.build(listOf(p)) { pid -> keys.filter { it.providerId == pid } }

        assertTrue(plan.tasks.isEmpty())
        assertEquals(listOf(p), plan.skippedProviders)
    }

    @Test
    fun `每把 Key 每个协议生成一条 L1`() {
        val p = provider(1)
        val keys = listOf(
            key(10, 1, protocols = setOf(Protocol.CHAT, Protocol.ANTHROPIC)),
        )
        val plan = ProbePlanBuilder.build(listOf(p)) { pid -> keys.filter { it.providerId == pid } }

        val l1 = plan.tasks.filter { it.level == ProbeLevel.L1_REACHABILITY }
        assertEquals(2, l1.size)
        assertEquals(setOf(Protocol.CHAT, Protocol.ANTHROPIC), l1.map { it.protocol }.toSet())
        assertEquals(setOf(10L), l1.map { it.keyId }.toSet())
    }

    @Test
    fun `每把 Key 都生成 L2`() {
        val p = provider(1)
        val keys = listOf(
            key(10, 1),
            key(11, 1),
            key(12, 1),
        )
        val plan = ProbePlanBuilder.build(listOf(p)) { pid -> keys.filter { it.providerId == pid } }

        val l2 = plan.tasks.filter { it.level == ProbeLevel.L2_KEY_VALIDITY }
        assertEquals(setOf(10L, 11L, 12L), l2.map { it.keyId }.toSet())
    }

    @Test
    fun `reachability 关掉就不发 L1，keyValidity 关掉就不发 L2`() {
        val p = provider(1)
        val keys = listOf(
            key(10, 1, probe = KeyProbeSettings(reachability = false, keyValidity = false)),
        )
        val plan = ProbePlanBuilder.build(listOf(p)) { pid -> keys.filter { it.providerId == pid } }

        assertTrue(plan.tasks.isEmpty())
    }

    @Test
    fun `自动计划里永远不产出 L3 模型探测`() {
        // 模型可达性探测必然花钱，**只能手动长按触发**（红线 36）。
        // 这条把它锁死在"自动路径根本没有这个任务"上：以后谁想把 L3 塞进计划，
        // 会先在这里红。
        val p = provider(1)
        val keys = listOf(
            key(10, 1, probe = KeyProbeSettings(modelReachability = true, quickModelProbe = true)),
        )
        val plan = ProbePlanBuilder.build(listOf(p)) { pid -> keys.filter { it.providerId == pid } }

        assertTrue(plan.tasks.none { it.level == ProbeLevel.L3_MODEL })
    }

    @Test
    fun `端点规范化失败的 Key 跳过`() {
        val p = provider(1)
        val keys = listOf(key(10, 1, baseUrl = ""))
        val plan = ProbePlanBuilder.build(listOf(p)) { pid -> keys.filter { it.providerId == pid } }

        assertTrue(plan.tasks.isEmpty())
        assertEquals(listOf(p), plan.skippedProviders)
    }

    @Test
    fun `host 预算基数等于该 host 的 Key 数`() {
        val p = provider(1)
        val keys = listOf(key(10, 1), key(11, 1), key(12, 1))
        val plan = ProbePlanBuilder.build(listOf(p)) { pid -> keys.filter { it.providerId == pid } }

        assertEquals(3, plan.perHostKeyAndModelCount["api1.example.test"])
    }

    @Test
    fun `无协议的 Key 不生成任何任务`() {
        val p = provider(1)
        val keys = listOf(key(10, 1, protocols = emptySet()))
        val plan = ProbePlanBuilder.build(listOf(p)) { pid -> keys.filter { it.providerId == pid } }

        assertTrue(plan.tasks.isEmpty())
    }

    /** §8.1：Key 自己的超时进骨架任务，后面才谈得上透传到 net 层。 */
    @Test
    fun `每把 Key 的超时换算成毫秒进任务骨架`() {
        val p = provider(1)
        val keys = listOf(key(10, 1, protocols = setOf(Protocol.CHAT, Protocol.ANTHROPIC), timeoutSeconds = 60))
        val plan = ProbePlanBuilder.build(listOf(p)) { pid -> keys.filter { it.providerId == pid } }

        assertTrue(plan.tasks.isNotEmpty())
        plan.tasks.forEach { assertEquals(60_000L, it.timeoutMs) }
    }

    @Test
    fun `零与负数超时按没填处理`() {
        // 0 毫秒的超时等于每个请求必失败，不该是用户能意外得到的结果。
        val p = provider(1)
        val plan = ProbePlanBuilder.build(listOf(p)) { pid ->
            listOf(key(10, 1, timeoutSeconds = 0), key(11, 1, timeoutSeconds = -5)).filter { it.providerId == pid }
        }

        assertTrue(plan.tasks.isNotEmpty())
        plan.tasks.forEach { assertEquals(null, it.timeoutMs) }
    }

    // ------------------------------------------------------------ 模型列表任务
    //
    // 这一组钉的是"一次刷新只发一次 GET"。`modelsUrl` 不分协议，而 [build] 按协议铺 L1
    // 再补一条 L2，所以模型列表刷新一旦复用那份计划，同一份列表就被发两三次，且按协议
    // 各写一套行——界面上每个模型出现两遍。

    @Test
    fun `模型列表任务每把 Key 恰好一条，多协议也只一条`() {
        val p = provider(1)
        val keys = listOf(key(10, 1, protocols = setOf(Protocol.CHAT, Protocol.ANTHROPIC)))
        val tasks = ProbePlanBuilder.buildModelListTasks(listOf(p)) { pid ->
            keys.filter { it.providerId == pid }
        }

        assertEquals(1, tasks.size)
        assertEquals(10L, tasks.single().keyId)
        // 协议取声明序的第一个，与 [build] 的 L2 同口径。
        assertEquals(Protocol.CHAT, tasks.single().protocol)
    }

    @Test
    fun `L1 与 L2 的 url 完全相同，所以按协议铺任务就是重复请求`() {
        // 这一条是上一组断言的前提：models 端点不分协议，所以"每个协议一条"等于
        // "同一份列表发几次"。哪天有人给 modelsUrl 加上分协议版本，这里会先红。
        val p = provider(1)
        val keys = listOf(key(10, 1, protocols = setOf(Protocol.CHAT, Protocol.ANTHROPIC)))
        val plan = ProbePlanBuilder.build(listOf(p)) { pid -> keys.filter { it.providerId == pid } }
        val models = ProbePlanBuilder.buildModelListTasks(listOf(p)) { pid -> keys.filter { it.providerId == pid } }

        // 两个协议 → 两条 L1 + 一条 L2，三条任务、一个 url。
        assertEquals(3, plan.tasks.size)
        assertEquals(1, plan.tasks.map { it.url }.distinct().size)
        assertEquals(plan.tasks.map { it.url }.distinct(), models.map { it.url })
    }

    @Test
    fun `可达性与密钥有效性都关掉时，模型列表照旧铺一条`() {
        // 那两个开关不该决定"拉不拉得到列表"：挂在它们上，"只开模型列表自动更新"的 Key
        // 就永远拉不到列表，而界面上的刷新按钮按下去是零请求。
        val p = provider(1)
        val keys = listOf(
            key(10, 1, probe = KeyProbeSettings(reachability = false, keyValidity = false, models = true)),
        )
        assertTrue(ProbePlanBuilder.build(listOf(p)) { pid -> keys.filter { it.providerId == pid } }.tasks.isEmpty())

        val tasks = ProbePlanBuilder.buildModelListTasks(listOf(p)) { pid -> keys.filter { it.providerId == pid } }
        assertEquals(1, tasks.size)
        // 没有 L2 可对齐，级别回落到 L1：`ProbeClassifier` 只在 400 上分级别，别把一个
        // 没在检密钥的 Key 判成"参数被拒但密钥有效"。
        assertEquals(ProbeLevel.L1_REACHABILITY, tasks.single().level)
    }

    @Test
    fun `密钥有效性开着时模型列表任务按 L2 铺`() {
        val p = provider(1)
        val keys = listOf(key(10, 1, probe = KeyProbeSettings(models = true)))
        val tasks = ProbePlanBuilder.buildModelListTasks(listOf(p)) { pid -> keys.filter { it.providerId == pid } }

        // 这一条路径会顺带写密钥健康度（引擎那边按 keyValidity 开关决定），级别必须对得上。
        assertEquals(ProbeLevel.L2_KEY_VALIDITY, tasks.single().level)
    }

    @Test
    fun `探测总闸关掉的 Key 不铺模型列表任务`() {
        val p = provider(1)
        val keys = listOf(key(10, 1, probe = KeyProbeSettings(enabled = false, models = true)))
        val tasks = ProbePlanBuilder.buildModelListTasks(listOf(p)) { pid -> keys.filter { it.providerId == pid } }

        assertTrue(tasks.isEmpty())
    }

    @Test
    fun `没声明协议的 Key 不铺模型列表任务`() {
        // 协议决定鉴权头，一个都没有就拼不出可发的请求——这里必须跳过而不是默认 CHAT，
        // 否则 Anthropic 系的站点会收到一份它认不出的头，报 401 让人去查密钥。
        val p = provider(1)
        val keys = listOf(key(10, 1, protocols = emptySet(), probe = KeyProbeSettings(models = true)))
        val tasks = ProbePlanBuilder.buildModelListTasks(listOf(p)) { pid -> keys.filter { it.providerId == pid } }

        assertTrue(tasks.isEmpty())
    }

    @Test
    fun `模型列表任务带上这把 Key 自己的超时`() {
        val p = provider(1)
        val keys = listOf(key(10, 1, timeoutSeconds = 45, probe = KeyProbeSettings(models = true)))
        val tasks = ProbePlanBuilder.buildModelListTasks(listOf(p)) { pid -> keys.filter { it.providerId == pid } }

        assertEquals(45_000L, tasks.single().timeoutMs)
    }
}
