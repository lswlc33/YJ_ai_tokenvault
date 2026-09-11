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
        enabled: Boolean = true,
        baseUrl: String = "https://api$providerId.example.test/v1",
        protocols: Set<Protocol> = setOf(Protocol.CHAT),
        probe: KeyProbeSettings = KeyProbeSettings(),
    ) = ApiKey(
        id = id,
        providerId = providerId,
        label = "key$id",
        note = "",
        secretEnc = ByteArray(0),
        fingerprint = "fp$id",
        enabled = enabled,
        settings = KeySettings(
            apiBaseUrl = baseUrl,
            apiRoot = baseUrl.removeSuffix("/v1"),
            supportedProtocols = protocols,
            probe = probe,
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
    fun `L2 只为启用的 Key 生成`() {
        val p = provider(1)
        val keys = listOf(
            key(10, 1, enabled = true),
            key(11, 1, enabled = false),
            key(12, 2, enabled = true),
        )
        val plan = ProbePlanBuilder.build(listOf(p)) { pid -> keys.filter { it.providerId == pid } }

        val l2 = plan.tasks.filter { it.level == ProbeLevel.L2_KEY_VALIDITY }
        assertEquals(1, l2.size)
        assertEquals(10L, l2.single().keyId)
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
    fun `端点规范化失败的 Key 跳过`() {
        val p = provider(1)
        val keys = listOf(key(10, 1, baseUrl = ""))
        val plan = ProbePlanBuilder.build(listOf(p)) { pid -> keys.filter { it.providerId == pid } }

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
    fun `无协议的 Key 不生成任何任务`() {
        val p = provider(1)
        val keys = listOf(key(10, 1, protocols = emptySet()))
        val plan = ProbePlanBuilder.build(listOf(p)) { pid -> keys.filter { it.providerId == pid } }

        assertTrue(plan.tasks.isEmpty())
    }
}
