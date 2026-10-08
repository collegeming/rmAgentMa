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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.connectbot.data.dao.PubkeyDao
import org.connectbot.data.entity.KeyStorageType
import org.connectbot.data.entity.Pubkey
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.util.AesGcmPrivateKeyProtector
import org.connectbot.util.PrivateKeyProtector
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class PubkeyRepositoryTest {
    private lateinit var database: ConnectBotDatabase
    private lateinit var dao: PubkeyDao
    private lateinit var repository: PubkeyRepository
    private val dispatchers = CoroutineDispatchers(Dispatchers.Default, Dispatchers.IO, Dispatchers.Main)
    private val aes = AesGcmPrivateKeyProtector { SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES") }
    private val protector = object : PrivateKeyProtector by aes {
        var failWrites = false
        override fun protect(bytes: ByteArray, keyId: Long, type: String): ByteArray {
            assertFalse(Thread.currentThread() === android.os.Looper.getMainLooper().thread)
            if (failWrites) throw GeneralSecurityException("Synthetic wrapping failure")
            return aes.protect(bytes, keyId, type)
        }

        override fun unprotect(bytes: ByteArray, keyId: Long, type: String): ByteArray {
            assertFalse(Thread.currentThread() === android.os.Looper.getMainLooper().thread)
            return aes.unprotect(bytes, keyId, type)
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ConnectBotDatabase::class.java).build()
        dao = database.pubkeyDao()
        repository = PubkeyRepository(dao, database, protector, dispatchers)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun saveStoresCiphertextAndReturnsOriginalBytes() = runBlocking {
        val original = key("generated", encrypted = true)
        val saved = repository.save(original)
        val stored = dao.getById(saved.id)!!

        assertTrue(saved.id > 0)
        assertTrue(protector.isProtected(stored.privateKey!!))
        assertFalse(stored.privateKey.contentEquals(original.privateKey))
        assertFalse(stored.allowBackup)
        assertTrue(stored.encrypted)
        assertArrayEquals(original.privateKey, saved.privateKey)
        assertArrayEquals(original.privateKey, repository.getById(saved.id)!!.privateKey)
        assertNull(repository.getById(saved.id)!!.keystoreAlias)

        val updated = saved.copy(nickname = "renamed", type = "ED25519", privateKey = byteArrayOf(8, 7, 6))
        repository.save(updated)
        assertArrayEquals(updated.privateKey, repository.getByNickname("renamed")!!.privateKey)
        assertTrue(protector.isProtected(dao.getById(saved.id)!!.privateKey!!))
    }

    @Test
    fun generatedKeyStillUnlocksWithOriginalPassphraseAfterRepositoryRoundTrip() = runBlocking {
        val generator = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val pair = generator.generateKeyPair()
        val passphrase = "synthetic-test-passphrase"
        val original = key("generated-passphrase", encrypted = true).copy(
            privateKey = org.connectbot.util.PubkeyUtils.getEncodedPrivate(pair.private, passphrase),
            publicKey = pair.public.encoded,
        )
        val saved = repository.save(original)
        val retrieved = repository.getById(saved.id)!!
        val unlocked = org.connectbot.util.PubkeyUtils.convertToKeyPair(retrieved, passphrase)!!
        assertArrayEquals(original.privateKey, retrieved.privateKey)
        assertArrayEquals(pair.private.encoded, unlocked.private.encoded)
        assertArrayEquals(pair.public.encoded, unlocked.public.encoded)
        assertTrue(protector.isProtected(dao.getById(saved.id)!!.privateKey!!))
    }

    @Test
    fun initializationMigratesLegacyRowsAndLaterDaoImportsAreRechecked() = runBlocking {
        val legacy = key("legacy")
        val firstId = dao.insert(legacy)
        repository.initialize()
        assertTrue(protector.isProtected(dao.getById(firstId)!!.privateKey!!))
        assertFalse(dao.getById(firstId)!!.allowBackup)

        val lateImport = key("late-import", encrypted = true)
        val lateId = dao.insert(lateImport)
        assertArrayEquals(lateImport.privateKey, repository.getById(lateId)!!.privateKey)
        assertTrue(protector.isProtected(dao.getById(lateId)!!.privateKey!!))
        assertTrue(dao.getById(lateId)!!.encrypted)
    }

    @Test
    fun legacyDatabaseMigratorOutputIsWrappedByRepositoryInitialization() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = java.io.File.createTempFile("synthetic-legacy-", ".tmp", context.cacheDir).apply {
            check(delete() && mkdir()) { "Cannot create synthetic legacy database directory" }
        }
        val legacyContext = org.mockito.Mockito.mock(Context::class.java)
        listOf("pubkeys", "hosts", "pubkeys.migrated", "hosts.migrated", "connectbot.db").forEach { name ->
            org.mockito.Mockito.`when`(legacyContext.getDatabasePath(name)).thenReturn(java.io.File(directory, name))
        }
        val original = key("legacy-migrator", encrypted = true)
        try {
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(java.io.File(directory, "pubkeys"), null).use { legacy ->
                legacy.execSQL(
                    "CREATE TABLE pubkeys (_id INTEGER PRIMARY KEY, nickname TEXT, type TEXT, private BLOB, public BLOB, encrypted INTEGER, startup INTEGER, confirmuse INTEGER)",
                )
                legacy.execSQL(
                    "INSERT INTO pubkeys VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(12, original.nickname, original.type, original.privateKey, original.publicKey, 1, 0, 0),
                )
            }
            val migrator = org.connectbot.data.migration.DatabaseMigrator(
                legacyContext,
                database,
                org.connectbot.data.migration.LegacyHostDatabaseReader(legacyContext),
                org.connectbot.data.migration.LegacyPubkeyDatabaseReader(legacyContext),
                dispatchers,
            )
            assertTrue(migrator.migrate() is org.connectbot.data.migration.MigrationResult.Success)
            assertArrayEquals(original.privateKey, dao.getAll().single().privateKey)
            repository.initialize()
            assertTrue(protector.isProtected(dao.getAll().single().privateKey!!))
            assertFalse(dao.getAll().single().allowBackup)
            assertArrayEquals(original.privateKey, repository.getAll().single().privateKey)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun allReadAndObservePathsUnwrapWithoutChangingPassphraseSemantics() = runBlocking {
        val saved = repository.save(key("synthetic-import", encrypted = true).copy(type = "IMPORTED", startup = true))
        val values = listOf(
            repository.getAll().single(),
            repository.getByNickname(saved.nickname)!!,
            repository.getExportable().single(),
            repository.getStartupKeys().single(),
            repository.getByIdBlocking(saved.id)!!,
            repository.getStartupKeysBlocking().single(),
            repository.observeAll().first().single(),
            repository.observeByStorageType(KeyStorageType.EXPORTABLE).first().single(),
            repository.observePubkey(saved.id).first()!!,
        )
        values.forEach {
            assertArrayEquals(saved.privateKey, it.privateKey)
            assertTrue(it.encrypted)
            assertEquals("IMPORTED", it.type)
        }
    }

    @Test
    fun observationAlsoMigratesDirectDaoRows() = runBlocking {
        val raw = key("observe-legacy")
        val id = dao.insert(raw)
        assertArrayEquals(raw.privateKey, repository.observePubkey(id).first()!!.privateKey)
        assertTrue(protector.isProtected(dao.getById(id)!!.privateKey!!))
    }

    @Test
    fun nullAndBiometricKeysDoNotUseWrappingAndPrivateKeysCannotBeBackedUp() = runBlocking {
        protector.failWrites = true
        val publicOnly = repository.save(key("public-only").copy(privateKey = null))
        val biometric = repository.save(
            key("biometric").copy(privateKey = null, storageType = KeyStorageType.ANDROID_KEYSTORE, keystoreAlias = "synthetic-alias"),
        )
        assertNull(dao.getById(publicOnly.id)!!.privateKey)
        assertEquals("synthetic-alias", repository.getById(biometric.id)!!.keystoreAlias)
        assertFalse(biometric.allowBackup)
        assertEquals(listOf(publicOnly), repository.getBackupable())
        protector.failWrites = false
        val private = repository.save(key("private"))
        repository.updateBackupPermission(private.id, true)
        assertFalse(dao.getById(private.id)!!.allowBackup)
        assertEquals(listOf(publicOnly), repository.getBackupable())
    }

    @Test
    fun encryptionFailureRollsBackNewKeyAndPreservesExistingKey() = runBlocking {
        val existing = repository.save(key("existing"))
        val stored = dao.getById(existing.id)!!
        protector.failWrites = true
        expectSecurityFailure { repository.save(key("failed-insert")) }
        assertNull(dao.getByNickname("failed-insert"))
        expectSecurityFailure { repository.save(existing.copy(privateKey = byteArrayOf(9))) }
        assertEquals(stored, dao.getById(existing.id))
    }

    @Test
    fun migrationFailureDoesNotClaimSuccessOrReturnLegacyBytes() = runBlocking {
        val raw = key("migration-fails")
        val id = dao.insert(raw)
        protector.failWrites = true
        expectSecurityFailure { repository.initialize() }
        expectSecurityFailure { repository.getById(id) }
        assertArrayEquals(raw.privateKey, dao.getById(id)!!.privateKey)
    }

    @Test
    fun corruptedEnvelopeAndSwappedBlobFailClosedWithoutRewrapping() = runBlocking {
        val first = repository.save(key("first"))
        val second = repository.save(key("second"))
        val stored = dao.getById(first.id)!!
        val secondStored = dao.getById(second.id)!!
        dao.update(secondStored.copy(privateKey = stored.privateKey))
        expectSecurityFailure { repository.getById(second.id) }
        dao.update(secondStored)

        val corrupt = stored.privateKey!!.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        dao.update(stored.copy(privateKey = corrupt))
        expectSecurityFailure { repository.initialize() }
        expectSecurityFailure { repository.getById(first.id) }
        assertArrayEquals(corrupt, dao.getById(first.id)!!.privateKey)
        assertTrue(repository.getBackupable().isEmpty())
    }

    @Test
    fun saveRejectsDatabaseCiphertextInsteadOfDoubleWrapping() = runBlocking {
        val saved = repository.save(key("original"))
        val stored = dao.getById(saved.id)!!
        try {
            repository.save(stored)
            throw AssertionError("Expected original-byte contract rejection")
        } catch (_: IllegalArgumentException) {
            assertEquals(stored, dao.getById(saved.id))
        }
    }

    private suspend fun expectSecurityFailure(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected fail-closed private key error")
        } catch (_: GeneralSecurityException) {
            // Expected authentication or wrapping failure.
        }
    }

    private fun key(nickname: String, encrypted: Boolean = false) = Pubkey(
        nickname = nickname,
        type = "RSA",
        privateKey = byteArrayOf(1, 2, 3, 4, 5),
        publicKey = byteArrayOf(6, 7, 8),
        encrypted = encrypted,
        startup = false,
        confirmation = false,
        createdDate = 1,
    )
}
