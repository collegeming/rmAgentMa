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

package org.connectbot.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.entity.Host
import org.connectbot.util.SecurePasswordStorage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * A host remembers how it authenticates, and the password it authenticates with is handed to the Keystore
 * protected storage alone: the host row, the database and every export stay free of it.
 */
@RunWith(AndroidJUnit4::class)
class HostRepositoryPasswordTest {
    private lateinit var database: ConnectBotDatabase
    private lateinit var repository: HostRepository
    private lateinit var passwords: SecurePasswordStorage

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ConnectBotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        passwords = mock()
        repository = HostRepository(
            context,
            database,
            database.hostDao(),
            database.portForwardDao(),
            database.knownHostDao(),
            passwords,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun keepsTheAuthenticationMethodOnTheSavedHostAndDefaultsToAKey() = runTest {
        val passwordHost = repository.saveHost(
            Host(nickname = "work", hostname = "work.test", username = "colle", authMethod = Host.AUTH_METHOD_PASSWORD),
        )
        assertThat(repository.findHostById(passwordHost.id)?.authMethod).isEqualTo(Host.AUTH_METHOD_PASSWORD)

        val keyHost = repository.saveHost(Host(nickname = "plain", hostname = "plain.test", username = "colle"))
        assertThat(repository.findHostById(keyHost.id)?.authMethod).isEqualTo(Host.AUTH_METHOD_KEY)
    }

    @Test
    fun handsThePasswordToTheProtectedStorageAndKeepsItOutOfEveryReadableCopy() = runTest {
        val host = repository.saveHost(Host(nickname = "work", hostname = "work.test", username = "colle"))

        repository.setHostPassword(host.id, SECRET)

        verify(passwords).savePassword(host.id, SECRET)
        // Nothing this app reads back, exports or stores carries the password itself.
        assertThat(repository.findHostById(host.id).toString()).doesNotContain(SECRET)
        assertThat(repository.getHosts().toString()).doesNotContain(SECRET)
        assertThat(repository.exportHostsToJson(pretty = false).first).doesNotContain(SECRET)
        assertThat(database.hostDao().getAll().single().toString()).doesNotContain(SECRET)
    }

    @Test
    fun passesTheRemovalOfAPasswordToTheStorageTheSameWay() = runTest {
        val host = repository.saveHost(Host(nickname = "work", hostname = "work.test", username = "colle"))

        repository.setHostPassword(host.id, null)

        verify(passwords).savePassword(host.id, null)
    }

    @Test
    fun answersWhetherAPasswordIsSavedAndClearsItWithTheHost() = runTest {
        whenever(passwords.hasPassword(7L)).thenReturn(true)
        whenever(passwords.hasPassword(8L)).thenReturn(false)
        assertThat(repository.hasHostPassword(7L)).isTrue()
        assertThat(repository.hasHostPassword(8L)).isFalse()

        val host = repository.saveHost(Host(nickname = "gone", hostname = "gone.test"))
        repository.deleteHost(host)
        verify(passwords).deletePassword(host.id)
    }

    private companion object {
        const val SECRET = "hunter2-not-in-any-row"
    }
}
