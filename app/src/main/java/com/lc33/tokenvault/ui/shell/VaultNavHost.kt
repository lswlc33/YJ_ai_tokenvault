package com.lc33.tokenvault.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.lc33.tokenvault.screens.probe.ProbeScreen
import com.lc33.tokenvault.screens.settings.AboutScreen
import com.lc33.tokenvault.screens.settings.SettingsScreen
import com.lc33.tokenvault.screens.vault.VaultScreen

@Composable
fun VaultNavHost(
    nav: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = nav,
        startDestination = VaultRoute,
        modifier = modifier,
    ) {
        composable<VaultRoute> { VaultScreen() }
        composable<ProbeRoute> { ProbeScreen() }
        composable<SettingsRoute> {
            SettingsScreen(onOpenAbout = { nav.navigate(AboutRoute) })
        }
        composable<AboutRoute> {
            AboutScreen(onBack = { nav.popBackStack() })
        }
    }
}
