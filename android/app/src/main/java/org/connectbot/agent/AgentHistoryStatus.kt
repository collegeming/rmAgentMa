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

package org.connectbot.agent

/**
 * What this app holds of one conversation's history, as the store itself reports it.
 *
 * [state] is [HISTORY_COMPLETE] when every page this app set out to read is stored and the Agent's own replay
 * reached its barrier, [HISTORY_PARTIAL] when some pages are missing, unreadable or not read yet, and
 * [HISTORY_UNAVAILABLE] when the stored pages cannot be read at all. [note] is the human-readable reason for a
 * state that is not complete, or null while the history is complete. A state this app does not know is never
 * evidence of a complete history: a caller that needs one treats it as unavailable.
 */
data class AgentHistoryStatus(val state: String, val note: String?)
