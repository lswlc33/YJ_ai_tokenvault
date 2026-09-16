package com.lc33.tokenvault.ui.shell

import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.DefaultProbeSettings
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.KeyProbeSettings
import com.lc33.tokenvault.domain.model.KeySettings
import com.lc33.tokenvault.endpoint.ApiEndpointSet
import com.lc33.tokenvault.endpoint.NormalizeResult
import com.lc33.tokenvault.screens.model.KeyDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Key 草稿映射（§8.3、红线 36）。
 *
 * 这里守的是两条会**静默失效**的映射：
 *
 * 1. 新建 Key 时探测那几档必须来自「探测」设置页的默认值。曾经编辑页直接构造
 *    `KeyDraft()`，于是设置页那五个开关改了什么都不影响——界面在、功能不在，
 *    单测是唯一能在提交前发现它的地方。
 * 2. 已有 Key → 草稿 → 设置 的往返不能丢字段：`probeKeys` 这个名字对应的是
 *    `KeyProbeSettings.keyValidity`，名字对不上，写反了编译照样过。
 */
class KeyDraftMappingTest {

    @Test
    fun `新建草稿的探测项取自设置页默认值`() {
        val defaults = DefaultProbeSettings(
            reachability = false,
            keys = false,
            balance = true,
            models = true,
            modelReachability = true,
        )

        val draft = defaults.toNewKeyDraft(providerId = 42)

        assertEquals(42L, draft.providerId)
        assertEquals(false, draft.probeReachability)
        assertEquals(false, draft.probeKeys)
        assertEquals(true, draft.probeBalance)
        assertEquals(true, draft.probeModels)
        assertEquals(true, draft.probeModelReachability)
        // 编辑页把这两个字段画成一个开关，引擎要两个都为真才发快捷探测；
        // 只给 modelReachability 的话，开关显示为开、长按却没反应。
        assertEquals(true, draft.probeQuickModel)
        // 探测总闸不在这五个默认值里，新建时仍然默认开。
        assertTrue(draft.probeEnabled)
    }

    @Test
    fun `新建草稿的六个探测项与默认值一一对应而不是错位`() {
        // 只置位一项，确认它落在**正确的那一项**上：五项一起置位再整块比对，
        // 顺序写错（比如 keys 与 balance 互换）是看不出来的。
        fun draftOf(defaults: DefaultProbeSettings) = defaults.toNewKeyDraft(providerId = 1)

        assertTrue(draftOf(DefaultProbeSettings(reachability = true, keys = false, balance = false, models = false, modelReachability = false)).probeReachability)
        assertTrue(draftOf(DefaultProbeSettings(reachability = false, keys = true, balance = false, models = false, modelReachability = false)).probeKeys)
        assertTrue(draftOf(DefaultProbeSettings(reachability = false, keys = false, balance = true, models = false, modelReachability = false)).probeBalance)
        assertTrue(draftOf(DefaultProbeSettings(reachability = false, keys = false, balance = false, models = true, modelReachability = false)).probeModels)
        assertTrue(draftOf(DefaultProbeSettings(reachability = false, keys = false, balance = false, models = false, modelReachability = true)).probeModelReachability)
    }

    @Test
    fun `已有 Key 往返不丢探测项`() {
        val key = ApiKey(
            id = 7,
            providerId = 3,
            label = "主力",
            note = "备注",
            secretEnc = ByteArray(0),
            fingerprint = "fp",
            settings = KeySettings(
                apiBaseUrl = "https://api.test/v1",
                apiRoot = "https://api.test",
                supportedProtocols = setOf(Protocol.CHAT),
                probe = KeyProbeSettings(
                    enabled = false,
                    reachability = false,
                    keyValidity = false,
                    balance = false,
                    models = true,
                    modelReachability = true,
                    quickModelProbe = true,
                ),
            ),
            sortOrder = 2,
        )

        val draft = key.toDraft(profiles = emptyList())

        assertEquals(7L, draft.id)
        assertEquals(false, draft.probeEnabled)
        assertEquals(false, draft.probeReachability)
        // probeKeys 对应 keyValidity：名字不同的两个字段，写反了这里就会红。
        assertEquals(false, draft.probeKeys)
        assertEquals(false, draft.probeBalance)
        assertEquals(true, draft.probeModels)
        assertEquals(true, draft.probeModelReachability)
        assertEquals(true, draft.probeQuickModel)
        assertEquals(2, draft.sortOrder)
    }

    @Test
    fun `余额开关关掉时落库为不查且保留下拉选择`() {
        // 关掉开关 = 落 `BalanceKind.NONE`；但下拉下标仍留在草稿里，
        // 重新打开时接着用，不用再挑一次。
        val draft = KeyDraft(
            baseUrl = "https://api.test/v1",
            balanceEnabled = false,
            balanceKindIndex = KEY_BALANCE_KINDS.indexOf(BalanceKind.OPENROUTER),
        )

        val settings = draft.toSettings(existing = null, normalized = okEndpoints(), profiles = emptyList())

        assertEquals(BalanceKind.NONE, settings.balanceKind)
    }

    @Test
    fun `余额开关打开时落回下拉选中的适配器`() {
        val index = KEY_BALANCE_KINDS.indexOf(BalanceKind.OPENROUTER)
        val draft = KeyDraft(
            baseUrl = "https://api.test/v1",
            balanceEnabled = true,
            balanceKindIndex = index,
        )

        val settings = draft.toSettings(existing = null, normalized = okEndpoints(), profiles = emptyList())

        assertEquals(BalanceKind.OPENROUTER, settings.balanceKind)
    }

    @Test
    fun `已有的不查 Key 往返进草稿后开关是关的`() {
        // `BalanceKind.NONE` 不在 KEY_BALANCE_KINDS 里，所以下标只能落到 0；
        // 开关必须同时记着"其实是关的"，否则一次往返就把「不查」变成第一个适配器。
        val key = ApiKey(
            id = 1,
            providerId = 1,
            secretEnc = ByteArray(0),
            fingerprint = "fp",
            settings = KeySettings(
                apiBaseUrl = "https://api.test/v1",
                apiRoot = "https://api.test",
                balanceKind = BalanceKind.NONE,
            ),
        )

        val draft = key.toDraft(profiles = emptyList())
        assertEquals(false, draft.balanceEnabled)

        val back = draft.toSettings(existing = null, normalized = okEndpoints(), profiles = emptyList())
        assertEquals(BalanceKind.NONE, back.balanceKind)
    }

    @Test
    fun `下拉里没有不查这一项`() {
        // 「不查」由开关表达；它若留在下拉里，开关与下拉就是同一个字段的两个手柄。
        assertTrue(BalanceKind.NONE !in KEY_BALANCE_KINDS)
    }

    private fun okEndpoints() = NormalizeResult.Ok(
        ApiEndpointSet(
            apiRoot = "https://api.test",
            ver = "v1",
            origin = "https://api.test",
            byProtocol = mapOf(Protocol.CHAT to "https://api.test/v1/chat/completions"),
            modelsUrl = "https://api.test/v1/models",
            insecure = false,
        ),
    )
}
