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

/** One bridged request as the host receives it. */
type RecordedRequest = { action: string; requestId?: string; payload: { body: Record<string, unknown> } };

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
  /** One offline host on `window.RmAgentHost`, answering the handshake and whatever `answer` returns. */
  const startHost = async (answer: (request: RecordedRequest) => { status: number; body: unknown }) => {
    const original = window;
    const nativeWindow = new Proxy(original, {
      get(target, key) {
        if (key === 'location') return { origin: 'https://appassets.androidplatform.net' };
        return Reflect.get(target, key);
      },
    });
    vi.stubGlobal('window', nativeWindow);
    const requests: RecordedRequest[] = [];
    const host = {
      postMessage(raw: string) {
        const request = JSON.parse(raw) as RecordedRequest;
        requests.push(request);
        const answered = answer(request);
        queueMicrotask(() =>
          host.onmessage?.({
            data: JSON.stringify({
              requestId: (request as { requestId?: string }).requestId,
              documentId: 'real-sendbox-document',
              ok: answered.status < 300,
              status: answered.status,
              body: answered.body,
            }),
          } as MessageEvent<string>)
        );
      },
      onmessage: undefined as ((event: MessageEvent<string>) => void) | undefined,
    };
    window.RmAgentHost = host;
    await initializeHost();
    return requests;
  };

  const renderSendBox = (conversationId = 'conv-probe-1') => {
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
        <AcpSendBox conversation_id={conversationId} backend='dsh' messageState={state} />
      </SWRConfig>
    );
  };

  const acceptedSend = {
    status: 202,
    body: {
      success: true,
      data: {
        msg_id: 'accepted-msg',
        turn_id: 'accepted-turn',
        runtime: { state: 'running', can_send_message: false },
      },
    },
  };

  it('clicks the actual send button and passes the real ACP, HTTP and host mappings', async () => {
    try {
      const requests = await startHost((request) => {
        if (request.action === 'host.handshake')
          return { status: 200, body: { documentId: 'real-sendbox-document', protocolVersion: 1 } };
        if (request.action === 'draft.load')
          return {
            status: 200,
            body: { success: true, data: { conversation_id: 'conv-probe-1', text: '', files: [] } },
          };
        if (request.action === 'messages.send')
          return typeof request.payload.body.loading_id === 'string' && request.payload.body.loading_id.length > 0
            ? acceptedSend
            : { status: 400, body: { success: false, error: 'INVALID_REQUEST' } };
        return { status: 200, body: { success: true, data: [] } };
      });
      renderSendBox();
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

  it('keeps the attachment the box holds while the draft it saves carries the text alone', async () => {
    // The absolute remote path the host's own attachment.finish answers with, held by this conversation's box.
    const ref = '/home/ci/work/probe.txt';
    try {
      const requests = await startHost((request) => {
        if (request.action === 'host.handshake')
          return { status: 200, body: { documentId: 'real-sendbox-document', protocolVersion: 1 } };
        if (request.action === 'draft.load')
          return {
            status: 200,
            body: {
              success: true,
              data: { conversation_id: 'conv-probe-2', text: '带附件的旧草稿', files: [{ kind: 'upload', path: ref }] },
            },
          };
        // The product host stores the text alone: a draft that carried a file list answers 422, so a request
        // that brings one fails here exactly as it does on a device.
        if (request.action === 'draft.save')
          return ((request.payload.body.files ?? []) as unknown[]).length > 0
            ? { status: 422, body: { success: false, code: 'ATTACHMENT_UNSUPPORTED', error: '草稿只保存文本。' } }
            : {
                status: 200,
                body: {
                  success: true,
                  data: { conversation_id: 'conv-probe-2', text: request.payload.body.text, files: [], saved: true },
                },
              };
        if (request.action === 'messages.send') return acceptedSend;
        return { status: 200, body: { success: true, data: [] } };
      });
      // A conversation of its own: the draft store and its host round trip belong to one conversation.
      renderSendBox('conv-probe-2');
      const input = await screen.findByTestId('sendbox-input');
      await waitFor(() => expect(input).toHaveValue('带附件的旧草稿'));

      fireEvent.change(input, { target: { value: '带附件的旧草稿（续）' } });
      await waitFor(() => expect(requests.filter((request) => request.action === 'draft.save')).toHaveLength(1));
      expect(requests.find((request) => request.action === 'draft.save')!.payload.body).toEqual({
        text: '带附件的旧草稿（续）',
      });
      expect(screen.queryByTestId('draft-unsaved')).toBeNull();

      // The attachment is still the box's own: sending the message names it, and the draft never held it.
      fireEvent.click(screen.getByTestId('sendbox-send-btn'));
      await waitFor(() => expect(requests.filter((request) => request.action === 'messages.send')).toHaveLength(1));
      expect(requests.find((request) => request.action === 'messages.send')!.payload.body.files).toEqual([
        { kind: 'upload', path: ref },
      ]);
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
