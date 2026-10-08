/*
 * rmAgentMa
 * Copyright 2025-2026 rmAgentMa contributors
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

package org.connectbot.service

import android.content.Context
import androidx.room.Room
import org.connectbot.data.ColorSchemeRepository
import org.connectbot.data.ConnectBotDatabase
import org.connectbot.data.HostRepository
import org.connectbot.data.PubkeyRepository
import org.connectbot.data.entity.KeyStorageType
import timber.log.Timber
import java.io.File

/**
 * Handles filtering logic for backup operations.
 *
 * This class is separated from BackupAgent to allow for unit testing
 * of the filtering logic without requiring system-level backup permissions.
 */
class BackupFilter(
    private val context: Context,
    private val hostRepository: HostRepository,
    private val colorSchemeRepository: ColorSchemeRepository,
    private val pubkeyRepository: PubkeyRepository,
) {
    /**
     * Build a filtered database containing only backupable data.
     *
     * Opens both the main database and a new temporary database,
     * then copies all data except non-backupable pubkeys.
     *
     * @param tempDbFile The temporary database file to create
     */
    suspend fun buildFilteredDatabase(tempDbFile: File, backupKeys: Boolean) {
        cleanupTempDatabase(tempDbFile)
        val tempDb = Room.databaseBuilder(
            context,
            ConnectBotDatabase::class.java,
            tempDbFile.absolutePath,
        )
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()

        try {
            // Get all data from main database
            val allHosts = hostRepository.getHosts()
            val allColorSchemes = colorSchemeRepository.getAllSchemes()

            val backupablePubkeys = if (backupKeys) {
                filterBackupablePubkeys(pubkeyRepository.getBackupable())
            } else {
                emptyList()
            }

            Timber.d("Backing up ${allHosts.size} hosts, ${backupablePubkeys.size} pubkeys, ${allColorSchemes.size} color schemes")

            // Insert all backupable data into temp database
            allHosts.forEach { host ->
                tempDb.hostDao().insert(host)
                // Also backup port forwards and known hosts for this host
                val portForwards = hostRepository.getPortForwardsForHost(host.id)
                portForwards.forEach { tempDb.portForwardDao().insert(it) }
                tempDb.automationActionDao().insert(hostRepository.getAutomation(host.id))

                val knownHosts = hostRepository.getKnownHostsForHost(host.id)
                knownHosts.forEach { tempDb.knownHostDao().insert(it) }
            }

            backupablePubkeys.forEach { pubkey ->
                tempDb.pubkeyDao().insert(pubkey)
            }

            allColorSchemes.forEach { scheme ->
                if (!scheme.isBuiltIn) {
                    tempDb.colorSchemeDao().insert(scheme)
                    // Also backup color palette for this scheme
                    val colors = colorSchemeRepository.getSchemeColors(scheme.id)
                    colors.forEachIndexed { index, color ->
                        tempDb.colorSchemeDao().insertColor(
                            org.connectbot.data.entity.ColorPalette(
                                schemeId = scheme.id,
                                colorIndex = index,
                                color = color,
                            ),
                        )
                    }
                }
            }
        } finally {
            tempDb.close()
        }
    }

    /**
     * Only public-only records may leave the device. Neither wrapped private keys
     * nor biometric keys can be restored without this device's Android Keystore.
     * Legacy raw private keys are excluded too, even if allowBackup is still true.
     */
    fun filterBackupablePubkeys(pubkeys: List<org.connectbot.data.entity.Pubkey>): List<org.connectbot.data.entity.Pubkey> = pubkeys.filter { pubkey ->
        val isBackupable = pubkey.allowBackup && pubkey.storageType != KeyStorageType.ANDROID_KEYSTORE && pubkey.privateKey == null

        if (!isBackupable) {
            Timber.d(
                "Filtering out pubkey: ${pubkey.nickname} " +
                    "(allowBackup=${pubkey.allowBackup}, storageType=${pubkey.storageType})",
            )
        }

        isBackupable
    }

    /**
     * Clean up temporary database files.
     *
     * @param tempDbFile The temporary database file
     */
    fun cleanupTempDatabase(tempDbFile: File) {
        listOf(tempDbFile, File(tempDbFile.path + "-wal"), File(tempDbFile.path + "-shm"), File(tempDbFile.path + "-journal"))
            .forEach { file ->
                check(!file.exists() || file.delete()) { "Cannot remove previous temporary backup database" }
            }
        Timber.d("Deleted temporary database files")
    }
}
