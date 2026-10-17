/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { SWRConfig } from 'swr';
vi.mock('react-router-dom', () => ({
  useNavigate: () => vi.fn(),
  useLocation: () => ({ pathname: '/conversation/conv-probe-1', search: '', hash: '' }),
}));
import '@arco-design/web-react/es/_util/react-19-adapter';
import AcpSendBox from '@/renderer/pages/conversation/platforms/acp/AcpSendBox';
import { initializeHost } from '@/common/host/hostTransport';
import type { UseAcpMessageReturn } from '@/renderer/pages/conversation/platforms/acp/useAcpMessage';

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key, i18n: { language: 'zh-CN' } }) }));
vi.mock('@/renderer/hooks/context/LayoutContext', () => ({ useLayoutContext: () => ({ isMobile: false }) }));
vi.mock('@/renderer/hooks/context/ConversationContext', () => ({
  useConversationContextSafe: () => ({
    conversation_id: 'conv-probe-1',
    type: 'acp',
    loadedSkills: [],
    loadedMcpServers: [],
  }),
}));
vi.mock('@/renderer/pages/conversation/Preview', () => ({
  usePreviewContext: () => ({
    setSendBoxHandler: vi.fn(),
    domSnippets: [],
    clearDomSnippets: vi.fn(),
    removeDomSnippet: vi.fn(),
  }),
}));
vi.mock('@/renderer/pages/conversation/Messages/hooks', () => ({
  useAddOrUpdateMessage: () => vi.fn(),
  useMessageList: () => [],
}));
vi.mock('@/renderer/hooks/chat/useAutoTitle', () => ({ useAutoTitle: () => ({ checkAndUpdateTitle: vi.fn() }) }));
vi.mock('@/renderer/hooks/agent/useAcpConfigOptions', () => ({
  classifyConfigSetError: () => 'unknown',
  useAcpConfigOptions: () => ({
    mode: null,
    model: null,
    thoughtLevel: null,
    setStatus: { state: 'idle' },
    setConfigOption: vi.fn(),
    pendingValues: {},
  }),
}));
vi.mock('@/renderer/hooks/agent/useAcpModelInfo', () => ({
  useAcpModelInfo: () => ({ model_info: null, canSwitch: false, selectModel: vi.fn() }),
}));
vi.mock('@/renderer/components/agent/AgentModeSelector', () => ({ default: () => null }));
vi.mock('@/renderer/components/chat/CommandQueuePanel', () => ({ default: () => null }));
vi.mock('@/renderer/components/chat/MobileActionSheet', () => ({
  default: () => null,
  useAttachEntry: () => ({ entries: [], hiddenFileInput: null }),
}));
vi.mock('@/renderer/components/chat/ThoughtDisplay', () => ({ default: () => null }));
vi.mock('@/renderer/components/chat/SpeechInputButton', () => ({ default: () => null }));
vi.mock('@/renderer/components/chat/BtwOverlay', () => ({ default: () => null }));
vi.mock('@/renderer/components/media/UploadProgressBar', () => ({ default: () => null }));
vi.mock('@/renderer/hooks/file/useOpenFileSelector', () => ({
  useOpenFileSelector: () => ({ openFileSelector: vi.fn(), onSlashBuiltinCommand: vi.fn() }),
}));
vi.mock('@/renderer/hooks/file/useDragUpload', () => ({
  useDragUpload: () => ({ isFileDragging: false, dragHandlers: {} }),
}));
vi.mock('@/renderer/hooks/file/usePasteService', () => ({
  usePasteService: () => ({ onPaste: vi.fn(), onFocus: vi.fn() }),
}));
vi.mock('@/renderer/hooks/file/useUploadState', () => ({ useUploadState: () => ({ isUploading: false }) }));
vi.mock('@/renderer/hooks/file/useAbortUploadsOnConversationChange', () => ({
  useAbortUploadsOnConversationChange: vi.fn(),
}));
vi.mock('@/renderer/hooks/file/useConversationExport', () => ({
  useConversationExport: () => ({
    isOpen: false,
    showMenu: false,
    step: 'menu',
    filename: '',
    pathPreview: '',
    menuItems: [],
    activeIndex: 0,
    loading: false,
    openExportFlow: vi.fn(),
    closeExportFlow: vi.fn(),
    handleKeyDown: vi.fn(),
    onSelectMenuItem: vi.fn(),
    setActiveIndex: vi.fn(),
    setFilename: vi.fn(),
    submitFilename: vi.fn(),
  }),
}));
vi.mock('@/renderer/components/chat/BtwOverlay/useBtwCommand', () => ({
  useBtwCommand: () => ({ answer: '', question: '', isLoading: false, isOpen: false, ask: vi.fn(), dismiss: vi.fn() }),
}));
vi.mock('@/renderer/hooks/chat/useInputFocusRing', () => ({
  useInputFocusRing: () => ({
    activeBorderColor: 'var(--color-primary-6)',
    inactiveBorderColor: 'var(--color-border-2)',
    activeShadow: 'none',
  }),
}));
vi.mock('@/renderer/pages/team/hooks/TeamPermissionContext', () => ({ useTeamPermission: () => null }));
vi.mock('@/renderer/pages/conversation/runtime/useConversationRuntimeView', () => ({
  useConversationRuntimeView: () => ({
    hydrated: true,
    state: 'idle',
    canSendMessage: true,
    isProcessing: false,
    activeTurnId: null,
    supportsMidturnDelivery: false,
    markSendStarted: vi.fn(),
    markSendAccepted: vi.fn(),
    markSendFailed: vi.fn(),
  }),
}));
vi.mock('@/renderer/pages/conversation/platforms/useConversationCommandQueue', () => ({
  useConversationCommandQueue: () => ({
    items: [],
    mode: 'manual',
    isInteractionLocked: false,
    enqueue: vi.fn(),
    remove: vi.fn(),
    prioritize: vi.fn(),
    clear: vi.fn(),
    reorder: vi.fn(),
    toggleMode: vi.fn(),
    lockInteraction: vi.fn(),
    unlockInteraction: vi.fn(),
    resetActiveExecution: vi.fn(),
  }),
}));
vi.mock('@/renderer/utils/emitter', () => ({ emitter: { emit: vi.fn() }, useAddEventListener: vi.fn() }));

describe('real SendBox to native v1 mapper', () => {
  it('clicks the actual send button and passes the real ACP, HTTP and host mappings', async () => {
    const original = window;
    const nativeWindow = new Proxy(original, {
      get(target, key) {
        if (key === 'location') return { origin: 'https://appassets.androidplatform.net' };
        return Reflect.get(target, key);
      },
    });
    vi.stubGlobal('window', nativeWindow);
    const requests: { action: string; payload: { body: Record<string, unknown> } }[] = [];
    const host = {
      postMessage(raw: string) {
        const request = JSON.parse(raw);
        requests.push(request);
        let status = 200;
        let body: unknown = { success: true, data: [] };
        if (request.action === 'host.handshake') body = { documentId: 'real-sendbox-document', protocolVersion: 1 };
        if (request.action === 'draft.load')
          body = { success: true, data: { conversation_id: 'conv-probe-1', text: '', files: [] } };
        if (request.action === 'messages.send') {
          const valid =
            typeof request.payload.body.loading_id === 'string' && request.payload.body.loading_id.length > 0;
          status = valid ? 202 : 400;
          body = valid
            ? {
                success: true,
                data: {
                  msg_id: 'accepted-msg',
                  turn_id: 'accepted-turn',
                  runtime: { state: 'running', can_send_message: false },
                },
              }
            : { success: false, error: 'INVALID_REQUEST' };
        }
        queueMicrotask(() =>
          host.onmessage?.({
            data: JSON.stringify({
              requestId: request.requestId,
              documentId: 'real-sendbox-document',
              ok: status < 300,
              status,
              body,
            }),
          } as MessageEvent<string>)
        );
      },
      onmessage: undefined as ((event: MessageEvent<string>) => void) | undefined,
    };
    window.RmAgentHost = host;
    try {
      await initializeHost();
      const state = {
        aiProcessing: false,
        setAiProcessing: vi.fn(),
        resetState: vi.fn(),
        hasThinkingMessage: false,
        slashCommands: [],
        tokenUsage: null,
      } as unknown as UseAcpMessageReturn;
      const cache = new Map();
      render(
        <SWRConfig value={{ provider: () => cache, errorRetryCount: 0 }}>
          <AcpSendBox conversation_id='conv-probe-1' backend='dsh' messageState={state} />
        </SWRConfig>
      );
      const input = await screen.findByTestId('sendbox-input');
      fireEvent.change(input, { target: { value: '实际输入中文' } });
      fireEvent.click(screen.getByTestId('sendbox-send-btn'));
      await waitFor(() => expect(requests.filter((request) => request.action === 'messages.send')).toHaveLength(1));
      const submitted = requests.find((request) => request.action === 'messages.send')!.payload.body;
      expect(submitted).toMatchObject({ content: '实际输入中文', loading_id: expect.any(String), files: [] });
      expect((submitted.loading_id as string).length).toBeGreaterThan(0);
      await waitFor(() => expect(input).toHaveValue(''));
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
