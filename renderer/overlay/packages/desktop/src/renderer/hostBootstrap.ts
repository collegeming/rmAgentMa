/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { initializeHost, isNativeHost } from '@/common/host/hostTransport';
import zhCN from './services/i18n/locales/zh-CN/messages.json';

async function bootstrap(): Promise<void> {
  await initializeHost();
  if (isNativeHost() && !location.hash) location.hash = '/conversation/conv-probe-1';
  await import('./main');
}

void bootstrap().catch((error: unknown) => {
  const root = document.getElementById('root');
  if (root) {
    root.setAttribute('role', 'alert');
    root.textContent = `${zhCN.hostConnectionFailed} ${error instanceof Error ? error.message : String(error)}`;
  }
});
