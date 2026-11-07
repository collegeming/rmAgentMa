/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { sendToQueue } from '@renderer/host/hostActions';
import {
  projectTranscript,
  queueFailureText,
  readTranscript,
  transcriptFailureText,
  type TranscriptPage,
  type TranscriptRow,
} from '@renderer/host/hostTranscript';
import { Button, Empty, Input, Modal, Spin } from '@arco-design/web-react';
import React, { useCallback, useEffect, useState } from 'react';

export type HostTranscriptTarget = {
  conversationId: string;
  title?: string;
  hostId?: string;
  agent?: string;
};

type Props = {
  visible: boolean;
  target: HostTranscriptTarget;
  onClose: () => void;
};

type Reading = { rows: TranscriptRow[]; cursor: string } | null;

const READ_ONLY_NOTICE = '只读，不会切换当前会话';

/**
 * One conversation's history, read beside the open one. Nothing here opens that conversation: the host's own
 * read-only route answers one page at a time, and every refusal is stated as the sentence its code means. The
 * message box at the bottom is the existing queue send — the host holds that message until the conversation is
 * next opened and idle, so this panel never delivers anything by itself.
 */
const HostTranscriptPanel: React.FC<Props> = ({ visible, target, onClose }) => {
  const [reading, setReading] = useState<Reading>(null);
  const [loading, setLoading] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [failedCursor, setFailedCursor] = useState('');
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const [draft, setDraft] = useState('');
  const [sending, setSending] = useState(false);
  const [sent, setSent] = useState<string | null>(null);
  const [sendFailure, setSendFailure] = useState<string | null>(null);

  const conversationId = target.conversationId;

  const load = useCallback(
    async (next: string) => {
      setLoading(true);
      setFailure(null);
      try {
        const page: TranscriptPage = await readTranscript(conversationId, next || undefined);
        setReading((current) => {
          const start = next ? (current?.rows.length ?? 0) : 0;
          const rows = projectTranscript(page.items, start);
          return {
            rows: next ? [...(current?.rows ?? []), ...rows] : rows,
            // Only the host's own continuation can be followed: a `has_more` without one leaves nothing this
            // page could ask for, so no button points at an empty load.
            cursor: page.nextCursor,
          };
        });
      } catch (error) {
        // What was already read stays on screen; the failure names itself above the retry of the same page.
        setFailure(transcriptFailureText(error));
        setFailedCursor(next);
      } finally {
        setLoading(false);
      }
    },
    [conversationId]
  );

  useEffect(() => {
    if (!visible) return;
    setReading(null);
    setFailure(null);
    setDraft('');
    setSent(null);
    setSendFailure(null);
    setExpanded({});
    void load('');
  }, [visible, conversationId, load]);

  const send = useCallback(async () => {
    const text = draft.trim();
    if (!text) {
      setSendFailure('请先输入要发送的内容。');
      return;
    }
    setSending(true);
    setSendFailure(null);
    setSent(null);
    try {
      await sendToQueue({
        conversationId,
        hostId: target.hostId,
        agent: target.agent,
        kind: 'request',
        text,
      });
      setDraft('');
      setSent('已加入队列，该会话下次打开且空闲时自动发送。');
    } catch (error) {
      // The text stays in the box, so the retry hands over exactly what the host refused.
      setSendFailure(queueFailureText(error));
    } finally {
      setSending(false);
    }
  }, [conversationId, draft, target.agent, target.hostId]);

  const rows = reading?.rows ?? [];
  const cursor = reading?.cursor ?? '';

  return (
    <Modal
      visible={visible}
      onCancel={onClose}
      footer={null}
      unmountOnExit
      title={target.title || '会话内容'}
      className='w-[min(720px,calc(100vw-32px))]'
    >
      <div className='flex max-h-[70vh] flex-col gap-10px' data-testid='host-transcript-panel'>
        <p
          data-testid='host-transcript-readonly'
          className='m-0 shrink-0 rounded-8px bg-fill-2 px-10px py-6px text-12px leading-18px text-t-secondary'
        >
          {READ_ONLY_NOTICE}
        </p>

        <div className='min-h-0 flex-1 overflow-y-auto overscroll-contain'>
          {loading && !reading ? (
            <div className='flex min-h-120px items-center justify-center'>
              <Spin />
            </div>
          ) : rows.length === 0 && !failure ? (
            <div className='flex min-h-120px items-center justify-center'>
              <Empty description='这段历史没有可显示的内容' />
            </div>
          ) : (
            <div className='flex flex-col gap-6px'>
              {rows.map((row) => (
                <TranscriptRowView
                  key={row.key}
                  row={row}
                  open={expanded[row.key] === true}
                  onToggle={() => setExpanded((current) => ({ ...current, [row.key]: !current[row.key] }))}
                />
              ))}
            </div>
          )}
          {failure && (
            <div className='mt-8px flex flex-col gap-6px'>
              <p
                role='alert'
                data-testid='host-transcript-error'
                className='m-0 break-words text-13px leading-18px text-danger-6'
              >
                {failure}
              </p>
              <Button
                size='mini'
                className='self-start'
                loading={loading}
                data-testid='host-transcript-retry'
                onClick={() => void load(failedCursor)}
              >
                重试
              </Button>
            </div>
          )}
        </div>

        {cursor && (
          <Button
            size='small'
            long
            className='shrink-0'
            loading={loading}
            disabled={loading}
            data-testid='host-transcript-load-earlier'
            onClick={() => void load(cursor)}
          >
            加载更早
          </Button>
        )}

        <div className='flex shrink-0 flex-col gap-6px border-t border-solid border-[var(--color-border-2)] pt-10px'>
          <label className='text-13px text-t-secondary' htmlFor='host-transcript-message'>
            发消息给这个会话
          </label>
          <Input.TextArea
            id='host-transcript-message'
            value={draft}
            disabled={sending}
            autoSize={{ minRows: 2, maxRows: 4 }}
            placeholder='消息先进入队列，不会立刻发送'
            onChange={setDraft}
          />
          <div className='flex justify-end gap-8px'>
            <Button size='small' disabled={sending} onClick={onClose}>
              关闭
            </Button>
            <Button
              size='small'
              type='primary'
              loading={sending}
              data-testid='host-transcript-send'
              onClick={() => void send()}
            >
              加入队列
            </Button>
          </div>
          {sent && (
            <p
              role='status'
              data-testid='host-transcript-sent'
              className='m-0 break-words text-12px leading-18px text-t-secondary'
            >
              {sent}
            </p>
          )}
          {sendFailure && (
            <p
              role='alert'
              data-testid='host-transcript-send-error'
              className='m-0 break-words text-12px leading-18px text-danger-6'
            >
              {sendFailure}
            </p>
          )}
        </div>
      </div>
    </Modal>
  );
};

const ROW_CLASS: Record<TranscriptRow['kind'], string> = {
  user: 'text-t-primary',
  assistant: 'text-t-primary',
  tool: 'text-t-secondary',
  thinking: 'text-t-tertiary',
  other: 'text-t-secondary',
};

/** One projected line: its own label, its bounded text, and a fold for the kinds that carry detail. */
const TranscriptRowView: React.FC<{ row: TranscriptRow; open: boolean; onToggle: () => void }> = ({
  row,
  open,
  onToggle,
}) => {
  const folded = row.collapsed && !open;
  return (
    <div data-testid={`host-transcript-row-${row.kind}`} className='flex flex-col gap-2px rounded-8px bg-fill-2 p-8px'>
      <div className='flex min-w-0 items-center gap-6px'>
        <span className='shrink-0 text-11px leading-16px font-medium text-t-tertiary'>{row.label}</span>
        {row.meta && <span className='min-w-0 flex-1 truncate text-11px leading-16px text-t-tertiary'>{row.meta}</span>}
        {row.collapsed && (
          <button
            type='button'
            className='shrink-0 text-11px leading-16px text-t-tertiary'
            data-testid={`host-transcript-toggle-${row.kind}`}
            onClick={onToggle}
          >
            {open ? '收起' : '展开'}
          </button>
        )}
      </div>
      {!folded && (
        <span className={`break-words text-13px leading-20px ${ROW_CLASS[row.kind]}`}>
          {row.text || row.meta || '（无文本）'}
        </span>
      )}
    </div>
  );
};

export default HostTranscriptPanel;
