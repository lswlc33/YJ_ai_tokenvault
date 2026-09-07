package com.lc33.tokenvault.ui.shell

import com.lc33.tokenvault.domain.ModelProbeState
import com.lc33.tokenvault.domain.ModelSource
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.AiModel
import com.lc33.tokenvault.domain.model.ProviderAccount
import com.lc33.tokenvault.screens.model.UiHealth
import com.lc33.tokenvault.screens.model.UiModelSource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 详情页模型行与账号行的映射（§13.4）。
 *
 * 值得单独测的理由：模型行的 `probeState → UiHealth` 分档表达的是"模型名写错"（NOT_FOUND）
 * 和"配置/瞬时问题"（NO_ACCESS / ERROR）的差别——画错颜色会让用户去修一个不存在的东西。
 */
class ModelAccountMappingTest {

    private fun model(
        source: ModelSource = ModelSource.MANUAL,
        probeState: ModelProbeState = ModelProbeState.UNKNOWN,
        enabled: Boolean = true,
        displayName: String? = null,
    ) = AiModel(
        providerId = 1,
        modelId = "gpt-4o",
        protocol = Protocol.CHAT,
        source = source,
        probeState = probeState,
        enabled = enabled,
        displayName = displayName,
    )

    // ---------------------------------------------------------------- AiModel.toRow

    @Test
    fun `模型来源与协议映射到展示层`() {
        val manual = model(source = ModelSource.MANUAL).toRow()
        assertEquals(UiModelSource.Manual, manual.source)
        assertEquals(Protocol.CHAT.wireName, manual.protocol)

        val discovered = model(source = ModelSource.DISCOVERED).toRow()
        assertEquals(UiModelSource.Discovered, discovered.source)
    }

    @Test
    fun `模型探测状态分档`() {
        assertEquals(UiHealth.Ok, model(probeState = ModelProbeState.OK).toRow().health)
        assertEquals(UiHealth.Error, model(probeState = ModelProbeState.NOT_FOUND).toRow().health)
        assertEquals(UiHealth.Warn, model(probeState = ModelProbeState.NO_ACCESS).toRow().health)
        assertEquals(UiHealth.Warn, model(probeState = ModelProbeState.ERROR).toRow().health)
        assertEquals(UiHealth.Unknown, model(probeState = ModelProbeState.UNKNOWN).toRow().health)
    }

    @Test
    fun `启用状态与显示名透传`() {
        val enabled = model(enabled = true, displayName = "GPT-4o").toRow()
        assertEquals(true, enabled.enabled)
        assertEquals("GPT-4o", enabled.contextLabel)

        val disabled = model(enabled = false).toRow()
        assertEquals(false, disabled.enabled)
    }

    // ---------------------------------------------------------------- ProviderAccount.toRow

    @Test
    fun `账号行只带遮蔽串`() {
        val account = ProviderAccount(id = 7, providerId = 1, label = "公司主号")
        val row = account.toRow("company***@example.com")
        assertEquals(7, row.id)
        assertEquals("公司主号", row.label)
        assertEquals(1, row.providerId)
        assertEquals("company***@example.com", row.maskedUsername)
    }
}
