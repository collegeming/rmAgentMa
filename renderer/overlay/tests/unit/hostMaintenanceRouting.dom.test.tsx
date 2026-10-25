/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
// @vitest-environment-options {"url": "https://appassets.androidplatform.net/"}
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfigProvider } from '@arco-design/web-react';
// Arco's imperative toasts render through the root this adapter installs under React 19, as main.tsx does.
import '@arco-design/web-react/lib/_util/react-19-adapter';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * This document is served from the renderer's own origin, so `isNativeHost()` is the real one and nothing but
 * the host side of the bridge is replaced. The page's own `httpRequest`, the route table behind it and the
 * transport are real, and a maintenance route this build does not declare fails here exactly as it fails on a
 * phone — a mocked request would pass either way and prove nothing.
 */
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, unknown>) =>
      options ? `${key}(${Object.values(options).join('|')})` : key,
    i18n: { language: 'zh-CN' },
  }),
}));
vi.mock('@renderer/pages/settings/components/SettingsPageWrapper', () => ({
  default: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

import { initializeHost } from '@/common/host/hostTransport';
import HostMaintenanceSettings from '@renderer/pages/settings/HostMaintenanceSettings';

type Frame = { requestId: string; documentId: string; action: string; payload: { params: unknown; body: unknown } };
type Bridge = { postMessage: (message: string) => void; onmessage?: (event: MessageEvent<string>) => void };

let frames: Frame[] = [];
let bridge: Bridge = { postMessage: () => {} };

/** The host side of one request: the frame it received last is the one this answers. */
const answer = (body: unknown, status = 200) => {
  const frame = frames.at(-1);
  if (!frame) throw new Error('no host frame to answer');
  bridge.onmessage?.({
    data: JSON.stringify({
      requestId: frame.requestId,
      documentId: 'document-1',
      ok: status < 300,
      status,
      body,
    }),
  } as MessageEvent<string>);
};

beforeEach(async () => {
  frames = [];
  bridge = { postMessage: (message: string) => frames.push(JSON.parse(message) as Frame) };
  (window as unknown as { RmAgentHost?: Bridge }).RmAgentHost = bridge;

  const ready = initializeHost();
  await vi.waitFor(() => expect(frames).toHaveLength(1));
  answer({ documentId: 'document-1', protocolVersion: 1 });
  await vi.waitFor(() => expect(frames.at(-1)?.action).toBe('events.subscribe'));
  answer({ subscribed: true });
  await ready;
});

afterEach(() => {
  cleanup();
  (window as unknown as { RmAgentHost?: Bridge }).RmAgentHost = undefined;
});

describe('the maintenance page over the real host transport', () => {
  it('clears the index cache through its declared route instead of an unsupported action', async () => {
    render(
      <ConfigProvider>
        <HostMaintenanceSettings />
      </ConfigProvider>
    );
    const user = userEvent.setup();
    await user.click(screen.getByTestId('clear-index-cache'));
    await user.click(await screen.findByTestId('clear-index-confirm'));

    // The page's own POST reaches the host as the action the contract declares for that path.
    await vi.waitFor(() => expect(frames.at(-1)?.action).toBe('index.clear'));
    expect(frames.at(-1)?.payload.body).toEqual({});

    answer({ cleared: true, sessions_before: 42, sessions: 0 });
    expect(await screen.findByTestId('clear-index-done')).toHaveTextContent(
      'settings.hostMaintenance.clearIndexDone(42|0)'
    );
  });

  it('reports the code and reason the host answered instead of a clear it never performed', async () => {
    render(
      <ConfigProvider>
        <HostMaintenanceSettings />
      </ConfigProvider>
    );
    const user = userEvent.setup();
    await user.click(screen.getByTestId('clear-index-cache'));
    await user.click(await screen.findByTestId('clear-index-confirm'));

    await vi.waitFor(() => expect(frames.at(-1)?.action).toBe('index.clear'));
    answer({ success: false, code: 'INDEX_CLEAR_FAILED', error: '索引文件被占用' }, 500);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'settings.hosts.requestFailed(INDEX_CLEAR_FAILED|索引文件被占用)'
    );
    expect(screen.queryByTestId('clear-index-done')).toBeNull();
  });
});
