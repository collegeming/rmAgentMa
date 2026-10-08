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

package org.connectbot.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.connectbot.R
import org.connectbot.agent.AgentRepository
import org.connectbot.agent.AgentSshPool
import org.connectbot.di.CoroutineDispatchers
import javax.inject.Inject
import javax.inject.Provider

@AndroidEntryPoint
class AgentConnectionService : Service() {
    @Inject lateinit var repository: Provider<AgentRepository>

    @Inject lateinit var pool: AgentSshPool

    @Inject lateinit var dispatchers: CoroutineDispatchers
    private lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + dispatchers.main)
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.agent_notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        publish(0)
        scope.launch {
            kotlinx.coroutines.flow.combine(pool.connectionCount, repository.get().operationCount) { count, operations -> count to operations }
                .collect { (count, operations) ->
                    publish(count)
                    if (count == 0 && operations == 0) {
                        repository.get().stopServiceIfIdle { stopSelf() }
                    }
                }
        }
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                repository.get().stopServiceIfIdle { stopSelf() }
            }
        }
    }

    private fun publish(count: Int) {
        val disconnect = PendingIntent.getService(
            this,
            NOTIFICATION_ID,
            Intent(this, AgentConnectionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, NOTIFICATION_ID, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.icon)
            .setContentTitle(getString(R.string.agent_notification_title))
            .setContentText(resources.getQuantityString(R.plurals.agent_connection_count, count, count))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(launch)
            .addAction(0, getString(R.string.agent_disconnect_all), disconnect)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            scope.launch(dispatchers.io) {
                repository.get().disconnectAll()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        repository.get().onServiceDestroyed()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "agent_connections"
        private const val NOTIFICATION_ID = 0x4147
        private const val ACTION_DISCONNECT = "org.connectbot.agent.DISCONNECT_ALL"
    }
}
