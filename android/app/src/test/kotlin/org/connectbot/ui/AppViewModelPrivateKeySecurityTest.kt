/*
 * rmAgentMa
 * Copyright 2026 rmAgentMa contributors
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

package org.connectbot.ui

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.connectbot.data.PubkeyRepository
import org.connectbot.data.migration.DatabaseMigrator
import org.connectbot.data.migration.MigrationResult
import org.connectbot.data.migration.MigrationState
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.TerminalManager
import org.connectbot.util.NotificationPermissionHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AppViewModelPrivateKeySecurityTest {
    private val dispatcher = StandardTestDispatcher()
    private val dispatchers = CoroutineDispatchers(dispatcher, dispatcher, dispatcher)
    private val store = ViewModelStore()
    private lateinit var migrator: DatabaseMigrator
    private lateinit var repository: PubkeyRepository
    private lateinit var permission: NotificationPermissionHelper
    private lateinit var manager: TerminalManager
    private val migrationStates = MutableStateFlow(MigrationState())

    @Before
    fun setUp() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        migrator = mock(DatabaseMigrator::class.java)
        repository = mock(PubkeyRepository::class.java)
        permission = mock(NotificationPermissionHelper::class.java)
        manager = mock(TerminalManager::class.java)
        `when`(migrator.migrationState).thenReturn(migrationStates)
        `when`(migrator.isMigrationNeeded()).thenReturn(false)
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun existingRoomDatabaseIsProtectedBeforeReady() = runTest(dispatcher) {
        val model = model()
        `when`(repository.initialize()).thenAnswer {
            assertFalse(model.uiState.value is AppUiState.Ready)
            Unit
        }
        model.setTerminalManager(manager)
        advanceUntilIdle()

        verify(repository).initialize()
        verify(migrator, never()).migrate()
        assertTrue(model.uiState.value is AppUiState.Ready)
    }

    @Test
    fun legacyMigrationIsFollowedByProtectionAndCollectorCannotOverwriteReady() = runTest(dispatcher) {
        `when`(migrator.isMigrationNeeded()).thenReturn(true)
        `when`(migrator.migrate()).thenReturn(MigrationResult.Success(0, 1, 0, 0, 0))
        val model = model()
        model.setTerminalManager(manager)
        advanceUntilIdle()

        val order = inOrder(migrator, repository)
        order.verify(migrator).migrate()
        order.verify(repository).initialize()
        assertTrue(model.uiState.value is AppUiState.Ready)
        migrationStates.value = MigrationState(currentStep = "late legacy event", progress = 0.5f)
        advanceUntilIdle()
        assertTrue(model.uiState.value is AppUiState.Ready)
    }

    @Test
    fun wrappingFailureBlocksReadyAndRetryInitializesAgain() = runTest(dispatcher) {
        `when`(repository.initialize()).thenThrow(IllegalStateException("Synthetic unavailable wrapping key"))
        val model = model()
        model.setTerminalManager(manager)
        advanceUntilIdle()

        assertTrue(model.uiState.value is AppUiState.MigrationFailed)
        assertEquals("Synthetic unavailable wrapping key", (model.uiState.value as AppUiState.MigrationFailed).error)
        org.mockito.Mockito.doAnswer { Unit }.`when`(repository).initialize()
        model.retryMigration()
        advanceUntilIdle()
        assertTrue(model.uiState.value is AppUiState.Ready)
    }

    @Test
    fun legacySuccessDoesNotBypassWrappingFailure() = runTest(dispatcher) {
        `when`(migrator.isMigrationNeeded()).thenReturn(true)
        `when`(migrator.migrate()).thenReturn(MigrationResult.Success(0, 1, 0, 0, 0))
        `when`(repository.initialize()).thenThrow(IllegalStateException("Synthetic protection failed"))
        val model = model()
        model.setTerminalManager(manager)
        advanceUntilIdle()

        assertTrue(model.uiState.value is AppUiState.MigrationFailed)
        migrationStates.value = MigrationState(currentStep = "late event after failure")
        advanceUntilIdle()
        assertTrue(model.uiState.value is AppUiState.MigrationFailed)
    }

    @Test
    fun initializationCancellationDoesNotBecomeMigrationFailureOrReady() = runTest(dispatcher) {
        `when`(repository.initialize()).thenThrow(CancellationException("Synthetic cancellation"))
        val model = model()
        model.setTerminalManager(manager)
        advanceUntilIdle()

        assertFalse(model.uiState.value is AppUiState.MigrationFailed)
        assertFalse(model.uiState.value is AppUiState.Ready)
    }

    @Test
    fun legacyFailureDoesNotInitializeOrAllowReady() = runTest(dispatcher) {
        `when`(migrator.isMigrationNeeded()).thenReturn(true)
        `when`(migrator.migrate()).thenReturn(MigrationResult.Failure(IllegalStateException("Synthetic legacy failure")))
        val model = model()
        model.setTerminalManager(manager)
        advanceUntilIdle()

        verify(repository, never()).initialize()
        assertTrue(model.uiState.value is AppUiState.MigrationFailed)
    }

    @Test
    fun wrappedLegacyCancellationDoesNotInitializePrivateKeys() = runTest(dispatcher) {
        `when`(migrator.isMigrationNeeded()).thenReturn(true)
        `when`(migrator.migrate()).thenReturn(MigrationResult.Failure(CancellationException("Synthetic legacy cancellation")))
        val model = model()
        model.setTerminalManager(manager)
        advanceUntilIdle()

        verify(repository, never()).initialize()
        assertFalse(model.uiState.value is AppUiState.MigrationFailed)
        assertFalse(model.uiState.value is AppUiState.Ready)
    }

    private fun model(): AppViewModel {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("private-key-security", Context.MODE_PRIVATE)
        val factory = androidx.lifecycle.viewmodel.viewModelFactory {
            initializer { AppViewModel(migrator, prefs, dispatchers, permission, repository) }
        }
        return androidx.lifecycle.ViewModelProvider.create(store, factory)[AppViewModel::class]
    }
}
