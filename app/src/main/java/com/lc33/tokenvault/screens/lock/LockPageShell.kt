package com.lc33.tokenvault.screens.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.lc33.tokenvault.R
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconTint
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.appPrimaryColor
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.theme.LocalAppTokens

/**
 * 锁闸这几页的共用外壳：没有 topBar、没有底栏、内容居中的一整屏。
 *
 * 没有返回键是刻意的——这几页不是"页面"而是**闸**：`LockPhase` 决定画哪一个，
 * 用户不能从这里"返回"到已解锁的界面（返回到哪去？树的另一半还没建起来）。
 *
 * 内容可滚动：引导页在小屏加大字号时会超过一屏，而超出的那部分恰好是「继续」按钮。
 *
 * **弹层要写在 [content] 里面**：MIUIX 的 `Overlay*` 画在最近一个 Scaffold 的 popupHost 里，
 * 而锁闸这几页没有外层 Shell 的 Scaffold 兜着（业务界面那一半还没建起来）。
 * 写在 `LockPage` 外面不会报任何错，只是什么都不显示。
 */
@Composable
fun LockPage(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalAppTokens.current
    AppScaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing)
                .then(modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

/** 大标题 + 一句说明。四个锁闸页的头部长得一样，改文案时不用四处找。 */
@Composable
fun LockPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: AppIcon? = AppIcon.Locked,
) {
    val tokens = LocalAppTokens.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
    ) {
        if (icon != null) {
            AppIconTint(icon = icon, size = tokens.sectionSpacing * 2, tint = appPrimaryColor)
        }
        AppText(text = title, style = AppTextStyle.Title, textAlign = TextAlign.Center)
        if (subtitle != null) {
            AppText(
                text = subtitle,
                style = AppTextStyle.Secondary,
                color = appSecondaryTextColor,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * `LockPhase.Loading` 那一帧（§7.4）。
 *
 * 读 boot 文件通常几毫秒就完了，但**这一帧必须有内容**：什么都不画的话冷启动会闪一下
 * 白屏，而白屏之后紧接着弹出的是要输 PIN 的页面，观感像是应用崩了一次又起来。
 * 这里不放进度条：几毫秒的进度条只会闪一下，比不放更吵。
 */
@Composable
fun SplashScreen() {
    LockPage {
        LockPageHeader(title = stringResource(R.string.app_name))
    }
}
