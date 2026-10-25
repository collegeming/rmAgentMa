/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The host the user last named on a surface that starts a session — the workspace picker on the home
 * page and the new-conversation dialog. It only carries a default forward, so the next surface opens on
 * the host the previous one was about instead of an unrelated first row.
 */
let selectedHostId: string | null = null;

export function getSelectedHostId(): string | null {
  return selectedHostId;
}

export function setSelectedHostId(hostId: string | null): void {
  selectedHostId = hostId;
}
