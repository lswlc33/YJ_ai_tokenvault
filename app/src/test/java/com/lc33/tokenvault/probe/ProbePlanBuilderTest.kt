package com.lc33.tokenvault.probe

import com.lc33.tokenvault.domain.KeyHealth
import com.lc33.tokenvault.domain.ProbeLevel
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.domain.model.ProviderProbeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探测计划生成（§8.6 第一步：过滤供应商，§8.3 只做 L1/L2）。
 *
 * 纯函数，验证：
 * - `probeEnabled = 0` 的整家跳过，进 skippedProviders。
 * - L1 按 `supportedProtocols` 每个协议一条。
 * - L2 只为启用的 Key 生成。
 * - 端点规范化失败（空地址 / 无 host）的那家跳过。
 * - host 预算的基数 = 该 host 的启用 Key 数。
 */
class ProbePlanBuilderTest {

    private fun provider(
        id: Long,
        baseUrl: String = "https://api$id.example.test/v1",
        protocols: Set<Protocol> = setOf(Protocol.CHAT),
        probe: ProviderProbeSettings = ProviderProbeSettings(),
    ) = Provider(
        id = id,
        name = "Provider $id",
        apiBaseUrl = baseUrl,
        apiRoot = baseUrl.removeSuffix("/v1"),
        supportedProtocols = protocols,
        probe = probe,
    )

    private fun key(id: Long, providerId: Long, enabled: Boolean = true) = ApiKey(
        id = id,
        providerId = providerId,
        secretEnc = ByteArray(0),
        fingerprint = "fp$id",
        health = KeyHealth.UNKNOWN,
        enabled = enabled,
    )

    @Test
    fun `probeEnabled 关掉的那家整家跳过`() {
        val p = provider(1, probe = ProviderProbeSettings(enabled = false))
        val plan = ProbePlanBuilder.build(listOf(p)) { emptyList() }

        assertTrue(plan.tasks.isEmpty())
        assertEquals(listOf(p), plan.skippedProviders)
    }

    @Test
    fun `每个协议生成一条 L1`() {
        val p = provider(1, protocols = setOf(Protocol.CHAT, Protocol.ANTHROPIC))
        val plan = ProbePlanBuilder.build(listOf(p)) { emptyList() }

        val l1 = plan.tasks.filter { it.level == ProbeLevel.L1_REACHABILITY }
        assertEquals(2, l1.size)
        assertEquals(setOf(Protocol.CHAT, Protocol.ANTHROPIC), l1.map { it.protocol }.toSet())
        assertNull("L1 不绑密钥", l1.first().keyId)
    }

    @Test
    fun `L2 只为启用的 Key 生成`() {
        val p = provider(1)
        val keys = listOf(
            key(10, providerId = 1, enabled = true),
            key(11, providerId = 1, enabled = false),
            key(12, providerId = 2, enabled = true), // 别家的，不该算进来
        )
        val plan = ProbePlanBuilder.build(listOf(p)) { pid -> keys.filter { it.providerId == pid } }

        val l2 = plan.tasks.filter { it.level == ProbeLevel.L2_KEY_VALIDITY }
        assertEquals(1, l2.size)
        assertEquals(10L, l2.single().keyId)
    }

    @Test
    fun `reachability 关掉就不发 L1，keyValidity 关掉就不发 L2`() {
        val p = provider(
            1,
            probe = ProviderProbeSettings(reachability = false, keyValidity = false),
        )
        val plan = ProbePlanBuilder.build(listOf(p)) { pid -> listOf(key(10, pid)) }

        assertTrue(plan.tasks.isEmpty())
    }

    @Test
    fun `端点规范化失败的那家跳过`() {
        val p = provider(1, baseUrl = "") // 空地址
        val plan = ProbePlanBuilder.build(listOf(p)) { emptyList() }

        assertTrue(plan.tasks.isEmpty())
        assertEquals(listOf(p), plan.skippedProviders)
    }

    @Test
    fun `host 预算基数等于该 host 的启用 Key 数`() {
        val p = provider(1)
        val keys = listOf(key(10, 1), key(11, 1), key(12, 1, enabled = false))
        val plan = ProbePlanBuilder.build(listOf(p)) { pid -> keys.filter { it.providerId == pid } }

        assertEquals(2, plan.perHostKeyAndModelCount["api1.example.test"])
    }

    @Test
    fun `无协议的供应商不生成任何任务`() {
        val p = provider(1, protocols = emptySet())
        val plan = ProbePlanBuilder.build(listOf(p)) { pid -> listOf(key(10, pid)) }

        assertTrue(plan.tasks.isEmpty())
    }
}
