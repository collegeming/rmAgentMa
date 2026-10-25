/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfigProvider } from '@arco-design/web-react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { request, t } = vi.hoisted(() => ({
  request: vi.fn(),
  t: (key: string, options?: Record<string, unknown>) =>
    options ? `${key}(${Object.values(options).join('|')})` : key,
}));

vi.mock('@/common/adapter/httpBridge', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/common/adapter/httpBridge')>()),
  httpRequest: request,
}));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t, i18n: { language: 'zh-CN' } }) }));
vi.mock('@renderer/components/base', () => ({
  // The real modal draws its footer itself; this stub keeps both halves so the save button is reachable.
  AionModal: ({
    visible,
    children,
    footer,
  }: {
    visible: boolean;
    children: React.ReactNode;
    footer?: React.ReactNode;
  }) =>
    visible ? (
      <div>
        {children}
        {footer}
      </div>
    ) : null,
}));

import HostCard from '@renderer/pages/hosts/HostCard';
import HostEditorDialog from '@renderer/pages/hosts/HostEditorDialog';
import { draftFromHost, draftProblem, hostFields, emptyDraft } from '@renderer/pages/hosts/hostModel';
import type { SavedHost } from '@/common/host/hostTransport';

const savedHost = (fields: Partial<SavedHost> = {}): SavedHost =>
  ({
    id: 'host-1',
    nickname: 'first',
    hostname: '192.168.1.10',
    port: 22,
    username: 'me',
    protocol: 'ssh',
    os_type: 'linux',
    agent_target: 'native',
    target: 'posix',
    target_supported: true,
    detection: { checked: true, agents: [] },
    auth_method: 'key',
    ...fields,
  }) as SavedHost;

beforeEach(() => request.mockReset());
afterEach(() => cleanup());

describe('how a host logs in, in the editor', () => {
  it('offers the two methods and sends the password once, without ever showing one back', async () => {
    const user = userEvent.setup();
    request.mockResolvedValueOnce(savedHost({ auth_method: 'password' }));
    const saved = vi.fn();
    render(
      <ConfigProvider>
        <HostEditorDialog visible host={null} onClose={vi.fn()} onSaved={saved} />
      </ConfigProvider>
    );

    await user.type(screen.getByLabelText('settings.hosts.nickname'), 'first');
    await user.type(screen.getByLabelText('settings.hosts.address'), '192.168.1.10');
    await user.type(screen.getByLabelText('settings.hosts.username'), 'me');
    // A create chooses a method and needs its password; an empty box is refused before anything is sent.
    await user.click(screen.getByRole('radio', { name: 'settings.hosts.authPassword' }));
    await user.click(screen.getByText('settings.hosts.save'));
    expect(request).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent('settings.hosts.passwordRequired');

    const password = screen.getByLabelText('settings.hosts.password');
    expect(password).toHaveAttribute('type', 'password');
    await user.type(password, 'hunter2');
    await user.click(screen.getByText('settings.hosts.save'));

    expect(request).toHaveBeenCalledWith('POST', '/api/hosts', {
      nickname: 'first',
      hostname: '192.168.1.10',
      port: 22,
      username: 'me',
      os_type: 'linux',
      agent_target: 'native',
      auth_method: 'password',
      password: 'hunter2',
    });
    expect(saved).toHaveBeenCalledWith(expect.objectContaining({ auth_method: 'password' }));
    // The host's own answer is the row it now holds, and no field of it is a password.
    const answered = saved.mock.calls.at(-1)?.[0] as Record<string, unknown>;
    expect(answered).not.toHaveProperty('password');
    // A later open of that saved host starts from an empty box: a stored password is never read back.
    cleanup();
    render(
      <ConfigProvider>
        <HostEditorDialog visible host={answered as SavedHost} onClose={vi.fn()} onSaved={vi.fn()} />
      </ConfigProvider>
    );
    expect(screen.getByLabelText('settings.hosts.password')).toHaveValue('');
  });

  it('keeps a stored password when the box is left empty, and never sends a password for the key method', async () => {
    const user = userEvent.setup();
    request.mockResolvedValueOnce(savedHost({ auth_method: 'password', has_password: true, key_name: null }));
    render(
      <ConfigProvider>
        <HostEditorDialog
          visible
          host={savedHost({ auth_method: 'password', has_password: true })}
          onClose={vi.fn()}
          onSaved={vi.fn()}
        />
      </ConfigProvider>
    );
    await user.click(screen.getByText('settings.hosts.save'));
    expect(request).toHaveBeenCalledWith(
      'PUT',
      '/api/hosts/host-1',
      expect.not.objectContaining({ password: expect.anything() })
    );
    expect(request.mock.calls.at(-1)?.[2]).toMatchObject({ auth_method: 'password' });
  });

  it('sends the key method for the default key, and names the key the row holds', () => {
    expect(hostFields({ ...emptyDraft(), nickname: 'n', hostname: 'h' })).toMatchObject({ auth_method: 'key' });
    expect(draftFromHost(savedHost({ auth_method: 'password', has_password: true }))).toMatchObject({
      authMethod: 'password',
      password: '',
    });
    expect(draftFromHost(savedHost({ auth_method: null, key_id: 'key-9', key_name: 'laptop' }))).toMatchObject({
      authMethod: 'key',
    });
    // A create without a password is a problem; the same draft while editing keeps the stored one.
    const draft = { ...emptyDraft(), nickname: 'n', hostname: 'h', authMethod: 'password' };
    expect(draftProblem(draft, false)).toEqual({ kind: 'password' });
    expect(draftProblem(draft, true)).toBeNull();

    render(
      <ConfigProvider>
        <HostCard
          host={savedHost({ key_name: 'laptop' })}
          refreshing={false}
          failure={null}
          onRefresh={vi.fn()}
          onEdit={vi.fn()}
          onDelete={vi.fn()}
        />
      </ConfigProvider>
    );
    expect(screen.getByTestId('host-auth-host-1')).toHaveTextContent('settings.hosts.authKeyNamed(laptop)');
  });
});
