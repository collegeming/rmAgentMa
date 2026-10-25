/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2025-2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package org.connectbot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import org.connectbot.BuildConfig
import org.connectbot.R
import org.connectbot.data.entity.Host
import org.connectbot.service.TerminalManager
import org.connectbot.ui.navigation.ConnectBotNavHost
import org.connectbot.ui.navigation.NavDestinations
import org.connectbot.ui.navigation.TerminalRequest
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.util.IconStyle
import org.connectbot.util.ThemeMode

val LocalTerminalManager = compositionLocalOf<TerminalManager?> {
    null
}

@Composable
fun ConnectBotApp(
    appUiState: AppUiState,
    navController: NavHostController,
    makingShortcut: Boolean,
    authRequired: Boolean,
    isAuthenticated: Boolean,
    themeMode: ThemeMode,
    onAuthenticationSuccess: () -> Unit,
    onRetryMigration: () -> Unit,
    onSelectShortcut: (Host, String?, IconStyle) -> Unit,
    onNavigateToConsole: (Host) -> Unit,
    modifier: Modifier = Modifier,
    onHandOverToRenderer: () -> Unit = {},
    rendererFailureCode: String? = null,
    onRetryRenderer: () -> Unit = {},
    terminalRequest: TerminalRequest? = null,
) {
    ConnectBotTheme(themeMode = themeMode) {
        when (appUiState) {
            is AppUiState.Loading -> {
                LoadingScreen(modifier = modifier)
            }

            is AppUiState.MigrationInProgress -> {
                MigrationScreen(
                    uiState = MigrationUiState.InProgress(appUiState.state),
                    onRetry = onRetryMigration,
                    modifier = modifier,
                )
            }

            is AppUiState.MigrationFailed -> {
                MigrationScreen(
                    uiState = MigrationUiState.Failed(
                        appUiState.error,
                        appUiState.debugLog,
                    ),
                    onRetry = onRetryMigration,
                    modifier = modifier,
                )
            }

            is AppUiState.Ready -> {
                // Migration, key protection and the authentication gate have all passed before this point.
                // The renderer is then the product's main surface; a failure code keeps the Compose shell
                // instead, so a failed handover can never loop straight back into the renderer. A terminal
                // hand-over also keeps the shell, because the console it asks for is an auxiliary route.
                val rendererActive = BuildConfig.RENDERER_ASSETS && !makingShortcut && rendererFailureCode == null && terminalRequest == null
                if (authRequired && !isAuthenticated && !makingShortcut) {
                    AuthenticationScreen(
                        onAuthenticationSuccess = onAuthenticationSuccess,
                        modifier = modifier,
                    )
                } else if (rendererActive) {
                    LaunchedEffect(Unit) { onHandOverToRenderer() }
                    LoadingScreen(modifier = modifier)
                } else {
                    CompositionLocalProvider(LocalTerminalManager provides appUiState.terminalManager) {
                        Column(modifier = modifier) {
                            rendererFailureCode?.let { code ->
                                RendererFallbackNotice(code = code, onRetry = onRetryRenderer)
                            }
                            ConnectBotNavHost(
                                navController = navController,
                                // The shell is only the fallback: host management and the SSH terminal are its
                                // entry, never the chat, which now belongs to the renderer.
                                startDestination = terminalRequest
                                    ?.let { "${NavDestinations.CONSOLE}/${it.hostId}" }
                                    ?: NavDestinations.HOST_LIST,
                                makingShortcut = makingShortcut,
                                onSelectShortcut = onSelectShortcut,
                                onNavigateToConsole = onNavigateToConsole,
                                terminalRequest = terminalRequest,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** States why the renderer left the main path and offers the retry; the code itself stays machine-readable. */
@Composable
private fun RendererFallbackNotice(
    code: String,
    onRetry: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.renderer_fallback_notice, code),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.renderer_fallback_retry))
            }
        }
    }
}
