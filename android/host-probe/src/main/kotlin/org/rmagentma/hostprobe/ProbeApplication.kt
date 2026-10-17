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

package org.rmagentma.hostprobe

import android.app.Application
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

class ProbeApplication : Application() {
    internal val worker = Executors.newSingleThreadExecutor()
    internal var nextPickerCode = 100
    internal lateinit var store: ProbeStore
    internal lateinit var attachments: ProbeAttachments
    internal lateinit var rendererAssets: ProbeAssets
    internal var stateFailure = false
    internal var assetsFailure = false
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        worker.execute {
            try {
                store = ProbeStore(File(noBackupFilesDir, "synthetic-state.json")) { delay, task -> main.postDelayed({ worker.execute(task) }, delay) }
                attachments = ProbeAttachments(File(noBackupFilesDir, "synthetic-uploads"))
            } catch (_: Exception) {
                stateFailure = true
            }
            try {
                rendererAssets = ProbeAssets(assets)
            } catch (_: Exception) {
                assetsFailure = true
            }
        }
    }
}
