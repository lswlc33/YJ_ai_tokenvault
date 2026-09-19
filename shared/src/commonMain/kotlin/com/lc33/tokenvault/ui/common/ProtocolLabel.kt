package com.lc33.tokenvault.ui.common

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import com.lc33.tokenvault.domain.Protocol
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.protocol_anthropic
import tokenvault.shared.generated.resources.protocol_chat
import tokenvault.shared.generated.resources.protocol_responses

/**
 * 协议的展示名。底层的 wireName / 枚举名都不直接给用户看。
 *
 * 和 [loginMethodLabel] 同一条道理（红线 17：同一个状态全应用一套文案）：这份实现以前
 * 住在 `screens/manage/ManageRows.kt` 里，`settings` 那一侧够不着，于是客户端预设编辑页
 * 自己画了 `protocol.name`——同一个协议在密钥设置页显示 "Chat"、在预设页显示 "CHAT"，
 * 而改协议名的人只会看到自己手头那一处。
 */
@Composable
fun protocolLabel(protocol: Protocol): String = stringResource(
    when (protocol) {
        Protocol.CHAT -> Res.string.protocol_chat
        Protocol.RESPONSES -> Res.string.protocol_responses
        Protocol.ANTHROPIC -> Res.string.protocol_anthropic
    },
)

/**
 * 库里存的 wireName 直接取标签。
 *
 * 认不出来时**原样显示**而不是兜底成某个协议：一个来自旧版本或备份包的未知值，
 * 猜成 CHAT 会让用户以为它就是 CHAT，而显示成原始字符串至少能被认出来是数据问题。
 */
@Composable
fun protocolLabel(wireName: String): String =
    Protocol.fromWireName(wireName)?.let { protocolLabel(it) } ?: wireName
