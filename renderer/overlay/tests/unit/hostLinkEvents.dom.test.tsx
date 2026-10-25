/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
// @vitest-environment-options {"url": "https://appassets.androidplatform.net/"}
import React from 'react';
import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * The host's own link frames are what this surface answers to. This document is served from the renderer's own
 * origin, so only the host side of the bridge is replaced: the transport, its fan-out into the renderer's
 * emitters and the strip are the real ones, and a frame the host publishes is followed here as on a phone.
 */
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, unknown>) =>
      options ? `${key}(${Object.values(options).join('|')})` : key,
    i18n: { language: 'zh-CN' },
  }),
}));

import { initializeHost } from '@/common/host/hostTransport';
import { resetHostConnection } from '@/common/host/hostConnection';
import { HostReconnectBanner } from '@renderer/host/HostConversationFailure';

type Frame = { requestId: string; action: string };
type Bridge = { postMessage: (message: string) => void; onmessage?: (event: MessageEvent<string>) => void };

let frames: Frame[] = [];
let bridge: Bridge = { postMessage: () => {} };
let seq = 0;

/** The host side of one request: the frame it received last is the one this answers. */
const answer = (body: unknown) => {
  const frame = frames.at(-1);
  if (!frame) throw new Error('no host frame to answer');
  bridge.onmessage?.({
    data: JSON.stringify({ requestId: frame.requestId, documentId: 'document-1', ok: true, status: 200, body }),
  } as MessageEvent<string>);
};

/** One frame of the host's own event stream, in the envelope the transport accepts. */
const emit = (name: string, data: unknown) =>
  bridge.onmessage?.({
    data: JSON.stringify({ type: 'event', documentId: 'document-1', name, data, epoch: 1, seq: seq++ }),
  } as MessageEvent<string>);

// The transport is bound to one document, and the renderer's event fan-out is wired to the first one, so the
// host here is opened once for the file and every case speaks to that same document.
beforeAll(async () => {
  bridge = { postMessage: (message: string) => frames.push(JSON.parse(message) as Frame) };
  (window as unknown as { RmAgentHost?: Bridge }).RmAgentHost = bridge;

  const ready = initializeHost();
  await vi.waitFor(() => expect(frames).toHaveLength(1));
  answer({ documentId: 'document-1', protocolVersion: 1 });
  await vi.waitFor(() => expect(frames.at(-1)?.action).toBe('events.subscribe'));
  answer({ subscribed: true });
  await ready;
});

beforeEach(() => resetHostConnection());
afterEach(() => cleanup());

const strip = () => screen.queryByTestId('host-reconnect-banner');

describe('the host link strip', () => {
  it('appears on the frame that says the link is lost and goes away when the host reports it back', async () => {
    render(<HostReconnectBanner />);
    expect(strip()).toBeNull();

    await act(async () =>
      emit('realtime.disconnected', {
        conversation_id: 'conv-1',
        host_id: 'host-3',
        state: 'offline',
        link_state: 'offline',
        reconnected: false,
        needs_user: false,
      })
    );
    expect(strip()).toHaveTextContent('conversation.hostTools.reconnecting');
    // The state shown is the one the host named, not one this build guessed.
    expect(strip()).toHaveTextContent('offline');

    await act(async () =>
      emit('realtime.reconnected', {
        conversation_id: 'conv-1',
        host_id: 'host-3',
        state: 'connected',
        link_state: 'connected',
        reconnected: true,
        needs_user: false,
      })
    );
    expect(strip()).toBeNull();
  });

  it('raises no strip from unrelated frames, and names only the frame when the state is one it did not hear', async () => {
    render(<HostReconnectBanner />);

    await act(async () => emit('message.stream', { conversation_id: 'conv-1', content: { type: 'text', text: 'hi' } }));
    expect(strip()).toBeNull();

    await act(async () => emit('realtime.disconnected', { conversation_id: 'conv-1' }));
    expect(strip()).toHaveTextContent('realtime.disconnected');
  });
});
