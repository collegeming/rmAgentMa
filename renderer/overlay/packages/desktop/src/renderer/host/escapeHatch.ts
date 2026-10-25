/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { httpRequest } from '@/common/adapter/httpBridge';

/**
 * What an escape-hatch request addresses: the open conversation, whose host and working directory the
 * host itself resolves from the conversation, or one saved host named from the settings page.
 */
export type EscapeTarget = { conversationId: string } | { hostId: string };

export type EscapeResult = { opened: boolean };

function terminalPath(target: EscapeTarget, suffix = ''): string {
  return 'conversationId' in target
    ? `/api/conversations/${encodeURIComponent(target.conversationId)}/terminal${suffix}`
    : `/api/hosts/${encodeURIComponent(target.hostId)}/terminal${suffix}`;
}

/** Opens an SSH terminal on the target's host, in `cwd` when one is given. */
export function openSshTerminal(target: EscapeTarget, cwd: string | null): Promise<EscapeResult> {
  return httpRequest<EscapeResult>('POST', terminalPath(target), { cwd });
}

/**
 * Starts the Agent's own TUI command in a terminal — the same semantics as the app's `ShellCommands.start`:
 * the host builds the command for that Agent and its target, so the renderer never composes one.
 */
export function startTuiCommand(target: EscapeTarget, cwd: string | null): Promise<EscapeResult> {
  return httpRequest<EscapeResult>('POST', terminalPath(target, '/command'), { cwd });
}
