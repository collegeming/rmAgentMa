/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 * Modified by rmAgentMa contributors for the renderer probe.
 */

import React from 'react';

interface WorkspaceOpenButtonProps {
  workspacePath: string;
  /**
   * Authoritative flag from `conversation.extra.is_temporary_workspace`.
   * External shell tools are hidden for temp workspaces (there is no meaningful
   * project folder to open), but the in-app browser still applies — so the button
   * itself stays, showing only Browser.
   */
  isTemporary: boolean;
}

/**
 * Hides the "open with VS Code / Terminal / File Explorer" button: those tools live on the machine
 * running the desktop app, while a workspace here belongs to a remote host. The component is kept
 * (rather than deleted upstream-side) so its two call sites stay untouched.
 */
const WorkspaceOpenButton: React.FC<WorkspaceOpenButtonProps> = () => null;

export default WorkspaceOpenButton;
