package com.lc33.tokenvault.ui.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lc33.tokenvault.platform.PlatformBackHandler
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.DialogLayout

/**
 * **宽版对话框**：比 [AppDialog] 更宽、更居中、内容按块排布的弹层。
 *
 * ## 为什么要自己搭一层
 *
 * MIUIX 到 0.9.1 为止，`OverlayDialog` 的宽度被 `DialogContent` 内部写死
 * （`widthIn(max = DialogDefaults.MaxWidth)` = 420dp），**没有任何参数能放宽**；
 * 官方那个 `maxWidth` 入参是 0.9.3 之后才有的。本项目刻意锁在 0.9.1
 * （`libs.versions.toml` 有说明：navigation3-ui / blur 的版本约束），
 * 所以这里用 MIUIX **公开的** [DialogLayout]（遮罩、层级、出入场动画都由它管）
 * 加我们自己的卡片壳。壳只做四件事：居中、限宽、圆角背景、标题/说明排版。
 *
 * 与 [AppDialog] 的分工：
 *
 * - [AppDialog] 走 MIUIX 原样形态，宽度 420dp、手机上贴底——**默认用它**；
 * - 本组件给"内容本身需要更宽"的场合（多条并列动作、两列信息），上限 560dp 且居中。
 *   手机竖屏上两者宽度接近（屏幕本来就比 420dp 窄），差别在平板/横屏才明显。
 *
 * 破坏性动作仍然必须给退路：点遮罩、按返回键都能关（[onDismissRequest]）。
 */
@Composable
fun AppWideDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalAppTokens.current
    val shape = RoundedCornerShape(tokens.dialogRadius)

    // DialogLayout 自己管出入场动画，但它只认识一个 MutableState<Boolean>。
    // 这里多留一个 mounted：退场动画播完（onDismissFinished）才真正卸载，
    // 否则卡片会在动画途中直接消失。
    val visibleState = remember { mutableStateOf(show) }
    var mounted by remember { mutableStateOf(show) }
    LaunchedEffect(show) {
        if (show) {
            mounted = true
            visibleState.value = true
        } else {
            visibleState.value = false
        }
    }
    if (!mounted) return

    // 返回键要能关掉弹层，否则系统返回会直接退到上一页、把弹层留在半空。
    PlatformBackHandler(enabled = show) { onDismissRequest() }

    DialogLayout(
        visible = visibleState,
        enableWindowDim = true,
        onDismissFinished = { mounted = false },
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .imePadding()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest,
                )
                .padding(horizontal = tokens.screenPadding, vertical = tokens.sectionSpacing),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = WideDialogMaxWidth)
                    .wrapContentHeight()
                    .clip(shape)
                    .background(MiuixTheme.colorScheme.background)
                    // 吃掉落在卡片上的点击，否则点卡片空白处会被上面那层读成"点了遮罩"。
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
            ) {
                title?.let {
                    AppText(
                        text = it,
                        style = AppTextStyle.Title,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                summary?.let {
                    AppText(
                        text = it,
                        style = AppTextStyle.Secondary,
                        color = appSecondaryTextColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                content()
            }
        }
    }
}

/**
 * 宽版弹层的宽度上限。取自 MIUIX 官方宽版的 560dp——比默认的 420dp 宽出一档，
 * 够放下两列信息或并排两条动作，又不至于在平板上拉成一条。
 */
private val WideDialogMaxWidth = 560.dp
