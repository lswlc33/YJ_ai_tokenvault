package com.lc33.tokenvault.screens.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lc33.tokenvault.ui.miuix.AppArrowRow
import com.lc33.tokenvault.ui.miuix.AppBottomSheet
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.licenses_intro
import tokenvault.shared.generated.resources.licenses_license_label
import tokenvault.shared.generated.resources.licenses_title

private data class LicenseEntry(
    val name: String,
    val license: String,
    val copyright: String,
)

private val licenses = listOf(
    LicenseEntry("Kotlin", "Apache License 2.0", "JetBrains s.r.o. and Kotlin contributors"),
    LicenseEntry("Compose Multiplatform", "Apache License 2.0", "JetBrains s.r.o."),
    LicenseEntry("AndroidX / Jetpack", "Apache License 2.0", "The Android Open Source Project"),
    LicenseEntry("Room", "Apache License 2.0", "The Android Open Source Project"),
    LicenseEntry("Navigation 3", "Apache License 2.0", "The Android Open Source Project"),
    LicenseEntry("Lifecycle", "Apache License 2.0", "The Android Open Source Project"),
    LicenseEntry("Activity Compose", "Apache License 2.0", "The Android Open Source Project"),
    LicenseEntry("WorkManager", "Apache License 2.0", "The Android Open Source Project"),
    LicenseEntry("Biometric", "Apache License 2.0", "The Android Open Source Project"),
    LicenseEntry("Koin", "Apache License 2.0", "Koin contributors"),
    LicenseEntry("Ktor", "Apache License 2.0", "JetBrains s.r.o."),
    LicenseEntry("OkHttp", "Apache License 2.0", "Square, Inc."),
    LicenseEntry("Okio", "Apache License 2.0", "Square, Inc."),
    LicenseEntry("kotlinx.coroutines", "Apache License 2.0", "JetBrains s.r.o."),
    LicenseEntry("kotlinx.serialization", "Apache License 2.0", "JetBrains s.r.o."),
    LicenseEntry("kotlinx-datetime", "Apache License 2.0", "JetBrains s.r.o."),
    LicenseEntry("MIUIX", "Apache License 2.0", "MIUIX contributors"),
    LicenseEntry("cryptography-kotlin", "Apache License 2.0", "Whyoleg and contributors"),
)

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current
    var selected by remember { mutableStateOf<LicenseEntry?>(null) }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(Res.string.licenses_title),
                scrollState = scrollState,
                navigationIcon = {
                    AppIconButton(
                        icon = AppIcon.Back,
                        contentDescription = stringResource(Res.string.back_cd),
                        onClick = onBack,
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .appTopBarScroll(scrollState),
            contentPadding = padding,
        ) {
            item {
                AppText(
                    text = stringResource(Res.string.licenses_intro),
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                    modifier = Modifier.padding(
                        horizontal = tokens.screenPadding,
                        vertical = tokens.itemSpacing,
                    ),
                )
            }
            item {
                AppPreferenceGroup {
                    licenses.forEach { entry ->
                        AppArrowRow(
                            title = entry.name,
                            summary = entry.license,
                            onClick = { selected = entry },
                        )
                    }
                }
            }
        }
    }

    AppBottomSheet(
        show = selected != null,
        onDismissRequest = { selected = null },
        title = selected?.name,
    ) {
        selected?.let { entry ->
            AppText(text = entry.name, style = AppTextStyle.Title)
            AppText(
                text = stringResource(Res.string.licenses_license_label) + ": " + entry.license,
                style = AppTextStyle.Body,
                modifier = Modifier.fillMaxWidth().padding(top = tokens.itemSpacing),
            )
            AppText(
                text = entry.copyright,
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
                modifier = Modifier.padding(top = tokens.itemSpacing),
            )
        }
    }
}
