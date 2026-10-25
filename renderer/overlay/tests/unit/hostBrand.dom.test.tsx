/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

/**
 * The phone names itself: the bar at the top of the app and the window title both carry this product's name.
 * Both used to carry the upstream project's, which is software this build is not.
 */
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key, i18n: { language: 'zh-CN' } }),
}));
vi.mock('react-router-dom', () => ({
  useLocation: () => ({ pathname: '/hosts', search: '', hash: '' }),
  useNavigate: () => vi.fn(),
}));
vi.mock('@/common', () => ({ ipcBridge: { conversation: { get: { invoke: vi.fn() } } } }));
vi.mock('@/common/config/constants', () => ({ TEAM_MODE_ENABLED: false }));
vi.mock('@renderer/pages/conversation/GroupedHistory/ConversationSearchPopover', () => ({ default: () => null }));
vi.mock('@/renderer/components/layout/Titlebar/MobileConversationBrand', () => ({ default: () => null }));
vi.mock('@/renderer/hooks/context/LayoutContext', () => ({
  useLayoutContext: () => ({ isMobile: true, siderCollapsed: false, setSiderCollapsed: () => {} }),
}));
vi.mock('@/renderer/hooks/context/NavigationHistoryContext', () => ({ useNavigationHistory: () => null }));
vi.mock('@/renderer/utils/platform', () => ({ isElectronDesktop: () => false, isMacOS: () => false }));

import Titlebar from '@renderer/components/layout/Titlebar';
import { titleForPath } from '@renderer/components/layout/DocumentTitle';

const t = (key: string) => key;

afterEach(() => cleanup());

describe('this product’s own name', () => {
  it('is what the top bar shows, on the phone and on every route', () => {
    render(<Titlebar workspaceAvailable />);

    expect(screen.getByText('RmAgentMa')).toBeInTheDocument();
    expect(screen.queryByText('AionUi')).toBeNull();
  });

  it('is what the window title holds, whatever route is open', () => {
    expect(titleForPath('/guid', t)).toBe('RmAgentMa');
    expect(titleForPath('/conversation/conv-probe-1', t)).toBe('RmAgentMa');
    expect(titleForPath('/hosts', t)).toBe('RmAgentMa');
    expect(titleForPath('/login', t)).toBe('RmAgentMa');
  });
});
