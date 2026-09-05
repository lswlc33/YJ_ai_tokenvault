package com.lc33.tokenvault.screens.sample

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lc33.tokenvault.R
import com.lc33.tokenvault.endpoint.Protocol
import com.lc33.tokenvault.screens.manage.ParsedPreview
import com.lc33.tokenvault.screens.model.AttentionItem
import com.lc33.tokenvault.screens.model.BackupStatus
import com.lc33.tokenvault.screens.model.BalanceSummary
import com.lc33.tokenvault.screens.model.ContentCounts
import com.lc33.tokenvault.screens.model.DashboardUiState
import com.lc33.tokenvault.screens.model.HealthBreakdown
import com.lc33.tokenvault.screens.model.ManageUiState
import com.lc33.tokenvault.screens.model.ProbeRunSummary
import com.lc33.tokenvault.screens.model.ProviderDetailUiState
import com.lc33.tokenvault.screens.model.ProviderDraft
import com.lc33.tokenvault.screens.model.UiGroup
import com.lc33.tokenvault.screens.model.UiAccountRow
import com.lc33.tokenvault.screens.model.UiHealth
import com.lc33.tokenvault.screens.model.UiKeyRow
import com.lc33.tokenvault.screens.model.UiModelRow
import com.lc33.tokenvault.screens.model.UiModelSource
import com.lc33.tokenvault.screens.model.UiMoney
import com.lc33.tokenvault.screens.model.UiProviderRow
import com.lc33.tokenvault.screens.probe.ProbeItemRow
import com.lc33.tokenvault.screens.settings.ProfileRow

/**
 * M0.8 的样例内容。
 *
 * 它的唯一用途是**让界面有东西可画**，好在真正接数据之前把布局、密度、状态色、
 * 空态都看清楚（计划.md §16 M0.8）。M3 会把它换成 ViewModel + 仓库，届时整个
 * `screens/sample/` 包删掉。
 *
 * 两条约束照真实实现来，免得布局做完才发现放不下：
 * - 密钥只有**遮蔽串**，因为列表页永远拿不到明文（§6.1 推论 3）。
 * - 金额是**已格式化的字符串**，页面不做浮点运算（§9.1）。
 *
 * 数字与站点形态取自 M0.5 实测的那三家，这样密度是真实的——`gpt-5.6-sol` 这种
 * 长模型 id 和 `USD 0.45` 这种小余额都会真的出现。
 */
object SampleContent {

    @Composable
    fun dashboard(): DashboardUiState = DashboardUiState(
        balance = BalanceSummary(
            perCurrency = listOf(
                UiMoney(currency = "USD", amount = "255.41"),
                UiMoney(currency = "CNY", amount = "0.89"),
            ),
            updatedAgo = stringResource(R.string.sample_ago_12min),
            failedProviderCount = 1,
        ),
        counts = ContentCounts(providers = 3, keys = 4, models = 10, accounts = 2),
        health = HealthBreakdown(ok = 2, warn = 1, error = 0, unknown = 1),
        attention = listOf(
            AttentionItem(
                providerId = 1L,
                providerName = "Agent Router",
                health = UiHealth.Warn,
                message = stringResource(R.string.sample_attention_client_blocked),
                offerClientProfileFix = true,
            ),
            AttentionItem(
                providerId = 3L,
                providerName = "DeepSeek",
                health = UiHealth.Warn,
                message = stringResource(R.string.sample_attention_low_balance),
            ),
        ),
        lastRun = ProbeRunSummary(
            finishedAgo = stringResource(R.string.sample_ago_2hour),
            total = 14,
            succeeded = 11,
            failed = 1,
            skipped = 2,
            durationLabel = "18s",
        ),
        progress = null,
        backup = BackupStatus(lastBackupAgo = null, targetLabel = null, sizeLabel = null),
    )

    @Composable
    fun manage(): ManageUiState {
        val providers = providers()
        return ManageUiState(
            groups = listOf(
                UiGroup(id = null, name = stringResource(R.string.group_all), providerCount = providers.size),
                UiGroup(id = 1L, name = stringResource(R.string.sample_group_work), providerCount = 1),
                UiGroup(id = 2L, name = stringResource(R.string.sample_group_spare), providerCount = 1),
                UiGroup(id = 3L, name = stringResource(R.string.sample_group_official), providerCount = 1),
            ),
            providers = providers,
        )
    }

    /** 供应商详情。找不到就退回第一家——M0.8 阶段没有"这一家被删了"这种状态。 */
    @Composable
    fun detail(providerId: Long): ProviderDetailUiState {
        val provider = providers().firstOrNull { it.id == providerId } ?: providers().first()
        return ProviderDetailUiState(
            provider = provider,
            keys = keys().filter { it.providerId == provider.id },
            models = models().filter { it.providerId == provider.id },
            accounts = accounts().filter { it.providerId == provider.id },
        )
    }

    /**
     * 内置客户端预设（§8.2 那张表）。     *
     * `claude_code` 是唯一带「已实测」的那条：M0.5 在 Agent Router 上验证过它能过闸，
     * 而且只需要换 UA。其余的置信度都只是社区观察。
     */
    @Composable
    fun profiles(): List<ProfileRow> = listOf(
        ProfileRow(
            id = null,
            name = stringResource(R.string.app_name),
            userAgent = "YuanJi/0.1.0 (Android 15; arm64)",
            builtin = true,
            verified = false,
        ),
        ProfileRow(
            id = 2L,
            name = "Claude Code",
            userAgent = "claude-cli/1.0.119 (external, cli)",
            builtin = true,
            verified = true,
        ),
        ProfileRow(
            id = 3L,
            name = "Codex CLI",
            userAgent = "codex_cli_rs/0.44.0 (Mac OS 15.5.0; arm64) Apple_Terminal",
            builtin = true,
            verified = false,
        ),
        ProfileRow(
            id = 4L,
            name = "Anthropic SDK (Python)",
            userAgent = "Anthropic/Python 0.40.0",
            builtin = true,
            verified = false,
        ),
        ProfileRow(
            id = 5L,
            name = "Cherry Studio",
            userAgent = "CherryStudio/1.4.0 (Windows NT 10.0; x64)",
            builtin = true,
            verified = false,
        ),
    )

    /** 编辑页的草稿。`id` 为 null 表示新建。 */
    @Composable
    fun draft(providerId: Long?): ProviderDraft {
        val provider = providers().firstOrNull { it.id == providerId } ?: return ProviderDraft()
        return ProviderDraft(
            id = provider.id,
            name = provider.name,
            note = provider.note.orEmpty(),
            website = "https://${provider.host}",
            baseUrl = "https://${provider.host}/v1",
            groupIndex = provider.groupId?.toInt() ?: 0,
            colorIndex = provider.colorIndex,
            pinned = provider.pinned,
            protocols = provider.protocols.mapNotNull { label ->
                Protocol.entries.firstOrNull { it.name.equals(label, ignoreCase = true) }
            }.toSet().ifEmpty { setOf(Protocol.CHAT) },
            balanceKindIndex = if (provider.host.contains("deepseek")) 2 else 1,
            // 真实实现里这一格是解密后的明文；M0.8 只给个形状，别当成真令牌
            balanceToken = if (provider.host.contains("deepseek")) "" else "token…",
            balanceUserId = if (provider.host.contains("deepseek")) "" else "199628",
            pathOverrideAnthropic = if (provider.host.contains("deepseek")) "/anthropic/v1/messages" else "",
        )
    }

    /** 导入预览。第三条刻意带一个问题，好看清"有问题但不阻止导入"长什么样。 */
    @Composable
    fun previews(): List<ParsedPreview> = listOf(
        ParsedPreview(
            name = "Agent Router",
            host = "ps.air-outer.com",
            keyCount = 1,
            modelCount = 3,
            accountCount = 1,
            protocols = listOf("CHAT", "RESPONSES", "ANTHROPIC"),
        ),
        ParsedPreview(
            name = "JustDoWork",
            host = "api.justwoker.icu",
            keyCount = 1,
            modelCount = 2,
            accountCount = 1,
            protocols = listOf("ANTHROPIC"),
        ),
        ParsedPreview(
            name = "DeepSeek",
            host = "api.deepseek.com",
            keyCount = 1,
            modelCount = 2,
            accountCount = 0,
            protocols = listOf("CHAT", "RESPONSES"),
            issues = listOf(stringResource(R.string.sample_import_issue_model_name)),
        ),
    )

    /**
     * 探测明细的三组。
     *
     * "本轮未探测"单独一组是有意的：它不是失败（红线 11），混进失败里会让用户以为
     * 有三样东西坏了，而其实只坏了一样。
     */
    @Composable
    fun probeFailed(): List<ProbeItemRow> = listOf(
        ProbeItemRow(
            target = "ps.air-outer.com · gpt-5.6-sol",
            providerId = 1L,
            health = UiHealth.Warn,
            outcome = stringResource(R.string.health_warn),
            detail = stringResource(R.string.sample_probe_detail_client),
            latencyMs = 248,
        ),
    )

    @Composable
    fun probeSkipped(): List<ProbeItemRow> = listOf(
        ProbeItemRow(
            target = "api.justwoker.icu · claude-opus-5-thinking",
            providerId = 2L,
            health = UiHealth.Unknown,
            outcome = stringResource(R.string.probe_run_skipped),
            detail = stringResource(R.string.sample_probe_detail_ratelimited),
            latencyMs = null,
        ),
        ProbeItemRow(
            target = "api.deepseek.com · deepseek-v4-pro",
            providerId = 3L,
            health = UiHealth.Unknown,
            outcome = stringResource(R.string.probe_run_skipped),
            detail = stringResource(R.string.sample_probe_detail_budget),
            latencyMs = null,
        ),
    )

    @Composable
    fun probeSucceeded(): List<ProbeItemRow> = listOf(
        ProbeItemRow(
            target = "api.justwoker.icu · claude-opus-5",
            providerId = 2L,
            health = UiHealth.Ok,
            outcome = stringResource(R.string.health_ok),
            detail = null,
            latencyMs = 412,
        ),
        ProbeItemRow(
            target = "api.deepseek.com · deepseek-v4-flash",
            providerId = 3L,
            health = UiHealth.Ok,
            outcome = stringResource(R.string.health_ok),
            detail = null,
            latencyMs = 236,
        ),
    )

    @Composable
    private fun providers(): List<UiProviderRow> = listOf(
        UiProviderRow(
            id = 1L,
            name = "Agent Router",
            note = stringResource(R.string.sample_note_company),
            host = "ps.air-outer.com",
            protocols = listOf("Chat", "Responses", "Anthropic"),
            colorIndex = 0,
            pinned = true,
            groupId = 1L,
            keyCount = 1,
            okKeyCount = 0,
            modelCount = 5,
            accountCount = 1,
            balance = UiMoney("USD", "0.45"),
            health = UiHealth.Warn,
        ),
        UiProviderRow(
            id = 2L,
            name = "JustDoWork",
            note = stringResource(R.string.sample_note_backup),
            host = "api.justwoker.icu",
            protocols = listOf("Anthropic"),
            colorIndex = 1,
            pinned = false,
            groupId = 2L,
            keyCount = 2,
            okKeyCount = 2,
            modelCount = 2,
            accountCount = 1,
            balance = UiMoney("USD", "254.96"),
            health = UiHealth.Ok,
        ),
        UiProviderRow(
            id = 3L,
            name = "DeepSeek",
            note = null,
            host = "api.deepseek.com",
            protocols = listOf("Chat", "Responses"),
            colorIndex = 2,
            pinned = false,
            groupId = 3L,
            keyCount = 1,
            okKeyCount = 1,
            modelCount = 3,
            accountCount = 0,
            balance = UiMoney("CNY", "0.89"),
            health = UiHealth.Ok,
            staleThisRound = true,
        ),
    )

    @Composable
    private fun keys(): List<UiKeyRow> = listOf(
        UiKeyRow(
            id = 11L,
            label = stringResource(R.string.sample_key_main),
            providerId = 1L,
            masked = "sk-LWxU…JrG0",
            health = UiHealth.Warn,
            latencyMs = null,
            checkedAgo = stringResource(R.string.sample_ago_2hour),
            isDefault = true,
        ),
        UiKeyRow(
            id = 21L,
            label = stringResource(R.string.sample_key_main),
            providerId = 2L,
            masked = "sk-k7Ks…Xp9G",
            health = UiHealth.Ok,
            latencyMs = 412,
            checkedAgo = stringResource(R.string.sample_ago_2hour),
            isDefault = true,
        ),
        UiKeyRow(
            id = 22L,
            label = stringResource(R.string.sample_key_spare),
            providerId = 2L,
            masked = "sk-9fQ2…mA7T",
            health = UiHealth.Unknown,
            latencyMs = null,
            checkedAgo = null,
            isDefault = false,
        ),
        UiKeyRow(
            id = 31L,
            label = stringResource(R.string.sample_key_main),
            providerId = 3L,
            masked = "sk-74ac…3ae3",
            health = UiHealth.Ok,
            latencyMs = 236,
            checkedAgo = stringResource(R.string.sample_ago_2hour),
            isDefault = true,
        ),
    )

    @Composable
    private fun models(): List<UiModelRow> = listOf(
        model(101L, "gpt-5.6-sol", 1L, "Responses", UiModelSource.Manual, UiHealth.Warn, true, "400K"),
        model(102L, "claude-opus-5", 1L, "Anthropic", UiModelSource.Discovered, UiHealth.Unknown, true, "200K"),
        model(103L, "glm-5.3", 1L, "Anthropic", UiModelSource.Discovered, UiHealth.Unknown, false, null),
        model(201L, "claude-opus-5", 2L, "Anthropic", UiModelSource.Manual, UiHealth.Ok, true, "200K"),
        model(202L, "claude-opus-5-thinking", 2L, "Anthropic", UiModelSource.Discovered, UiHealth.Ok, true, "200K"),
        model(301L, "deepseek-v4-flash", 3L, "Responses", UiModelSource.Manual, UiHealth.Ok, true, "128K"),
        model(302L, "deepseek-v4-pro", 3L, "Chat", UiModelSource.Discovered, UiHealth.Unknown, true, "128K"),
    )

    private fun model(
        id: Long,
        modelId: String,
        providerId: Long,
        protocol: String,
        source: UiModelSource,
        health: UiHealth,
        enabled: Boolean,
        contextLabel: String?,
    ) = UiModelRow(
        id = id,
        modelId = modelId,
        displayName = null,
        providerId = providerId,
        protocol = protocol,
        source = source,
        health = health,
        enabled = enabled,
        contextLabel = contextLabel,
    )

    @Composable
    private fun accounts(): List<UiAccountRow> = listOf(
        UiAccountRow(
            id = 41L,
            label = stringResource(R.string.sample_account_github),
            providerId = 1L,
            maskedUsername = "gith…9627",
        ),
        UiAccountRow(
            id = 42L,
            label = stringResource(R.string.sample_account_email),
            providerId = 2L,
            maskedUsername = "lswl….com",
        ),
    )
}
