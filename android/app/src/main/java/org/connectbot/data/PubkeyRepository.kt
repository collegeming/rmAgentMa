/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2025-2026 Kenny Root
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

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.connectbot.data.dao.PubkeyDao
import org.connectbot.data.entity.KeyStorageType
import org.connectbot.data.entity.Pubkey
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.util.PrivateKeyProtector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps the original private key bytes, including any user passphrase encryption,
 * at the database boundary. Callers receive the original encoding, not unlocked keys.
 */
@Singleton
class PubkeyRepository @Inject constructor(
    private val pubkeyDao: PubkeyDao,
    private val database: ConnectBotDatabase,
    private val protector: PrivateKeyProtector,
    private val dispatchers: CoroutineDispatchers,
) {
    fun observeAll(): Flow<List<Pubkey>> = pubkeyDao.observeAll()
        .map { getAll() }
        .flowOn(dispatchers.io)

    fun observeByStorageType(type: KeyStorageType): Flow<List<Pubkey>> = pubkeyDao.observeByStorageType(type)
        .map { read { pubkeyDao.getAll().filter { pubkey -> pubkey.storageType == type }.map(::unprotect) } }
        .flowOn(dispatchers.io)

    fun observePubkey(pubkeyId: Long): Flow<Pubkey?> = pubkeyDao.observeById(pubkeyId)
        .map { getById(pubkeyId) }
        .flowOn(dispatchers.io)

    suspend fun getAll(): List<Pubkey> = read { pubkeyDao.getAll().map(::unprotect) }

    suspend fun getById(pubkeyId: Long): Pubkey? = read { pubkeyDao.getById(pubkeyId)?.let(::unprotect) }

    /** Blocking Java interop; database and Keystore work still executes on the injected IO dispatcher. */
    fun getByIdBlocking(pubkeyId: Long): Pubkey? = runBlocking { getById(pubkeyId) }

    suspend fun getByNickname(nickname: String): Pubkey? = read { pubkeyDao.getByNickname(nickname)?.let(::unprotect) }

    /** Backup never unwraps or exports device-bound private keys, including legacy rows. */
    suspend fun getBackupable(): List<Pubkey> = read {
        pubkeyDao.getBackupable().filter { it.privateKey == null && !it.isBiometric }
    }

    suspend fun getExportable(): List<Pubkey> = read { pubkeyDao.getExportable().map(::unprotect) }

    suspend fun getStartupKeys(): List<Pubkey> = read { pubkeyDao.getStartupKeys().map(::unprotect) }

    fun getStartupKeysBlocking(): List<Pubkey> = runBlocking { getStartupKeys() }

    /**
     * Rechecks all rows on IO rather than caching a migration-complete flag: the
     * legacy database migrator can insert raw rows after this repository is created.
     */
    suspend fun initialize() = withContext(dispatchers.io) {
        database.withTransaction {
            migrateLegacyRows()
            pubkeyDao.getAll().forEach { stored ->
                stored.privateKey?.let { bytes ->
                    protector.unprotect(bytes, stored.id, stored.type).fill(0)
                }
            }
        }
    }

    suspend fun save(pubkey: Pubkey): Pubkey = withContext(dispatchers.io) {
        val bytes = pubkey.privateKey
        require(!pubkey.isBiometric || bytes == null) { "Biometric private keys must remain in Android Keystore" }
        require(
            bytes == null || (!protector.isProtected(bytes) && pubkey.keystoreAlias?.startsWith(ENVELOPE_MARKER_PREFIX) != true),
        ) { "save requires the original private key encoding" }
        database.withTransaction {
            migrateLegacyRows()
            val safePubkey = pubkey.copy(
                allowBackup = pubkey.allowBackup && bytes == null && !pubkey.isBiometric,
                keystoreAlias = if (pubkey.isBiometric) pubkey.keystoreAlias else null,
            )
            if (safePubkey.id == 0L) {
                val id = pubkeyDao.insert(safePubkey.copy(privateKey = null))
                val saved = safePubkey.copy(id = id)
                pubkeyDao.update(protect(saved))
                saved
            } else {
                pubkeyDao.update(protect(safePubkey))
                safePubkey
            }
        }
    }

    suspend fun delete(pubkey: Pubkey) = withContext(dispatchers.io) {
        pubkeyDao.delete(pubkey)
    }

    suspend fun updateBackupPermission(pubkeyId: Long, allowBackup: Boolean) = read {
        val pubkey = pubkeyDao.getById(pubkeyId)
        pubkeyDao.updateBackupPermission(pubkeyId, allowBackup && pubkey?.privateKey == null && pubkey?.isBiometric != true)
    }

    private suspend fun <T> read(block: suspend () -> T): T = withContext(dispatchers.io) {
        database.withTransaction {
            migrateLegacyRows()
            block()
        }
    }

    private suspend fun migrateLegacyRows() {
        pubkeyDao.getAll().forEach { stored ->
            val bytes = stored.privateKey
            val hasEnvelopeMarker = stored.keystoreAlias?.startsWith(ENVELOPE_MARKER_PREFIX) == true
            val migrated = when {
                bytes != null && !hasEnvelopeMarker && !protector.isProtected(bytes) -> protect(stored)
                (bytes != null || stored.isBiometric) && stored.allowBackup -> stored.copy(allowBackup = false)
                else -> stored
            }
            if (migrated !== stored) pubkeyDao.update(migrated)
        }
    }

    private fun protect(pubkey: Pubkey): Pubkey {
        val bytes = pubkey.privateKey ?: return pubkey
        require(!pubkey.isBiometric) { "Biometric private keys must remain in Android Keystore" }
        return pubkey.copy(
            privateKey = protector.protect(bytes, pubkey.id, pubkey.type),
            allowBackup = false,
            keystoreAlias = ENVELOPE_MARKER,
        )
    }

    private fun unprotect(pubkey: Pubkey): Pubkey {
        val bytes = pubkey.privateKey ?: return pubkey
        return pubkey.copy(
            privateKey = protector.unprotect(bytes, pubkey.id, pubkey.type),
            keystoreAlias = null,
        )
    }

    private companion object {
        const val ENVELOPE_MARKER_PREFIX = "rmagentma-private-key-envelope:"
        const val ENVELOPE_MARKER = "${ENVELOPE_MARKER_PREFIX}v1"
    }
}
