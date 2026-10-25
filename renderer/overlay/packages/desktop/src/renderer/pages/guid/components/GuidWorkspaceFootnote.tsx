/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 * Modified by rmAgentMa contributors for the renderer probe.
 */

import { Button, Input, Select, Tooltip } from '@arco-design/web-react';
import { Close, Down } from '@icon-park/react';
import { isAbsoluteRemotePath } from '@renderer/host/remotePath';
import { getSelectedHostId, setSelectedHostId } from '@renderer/host/selectedHost';
import { hostIdOf, hostWorkspaceOf } from '@renderer/host/hostRows';
import { useConversationListSync } from '@renderer/pages/conversation/GroupedHistory/hooks/useConversationListSync';
import type { SavedHost } from '@/common/host/hostTransport';
import { hostFailureText, listHosts } from '@renderer/pages/hosts/hostsApi';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import styles from '../index.module.css';

type GuidWorkspaceFootnoteProps = {
  workspaceDir: string;
  onSelectWorkspace: (dir: string) => void;
  onClearWorkspace: () => void;
};

const FolderIcon = ({ size = 12 }: { size?: number }) => (
  <svg
    width={size}
    height={size}
    fill='none'
    stroke='currentColor'
    strokeWidth='1.8'
    viewBox='0 0 24 24'
    style={{ lineHeight: 0, flexShrink: 0 }}
  >
    <path d='M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2V7z' />
  </svg>
);

/**
 * The workspace picker: a host and a working directory on it, both named by the user. The directory is
 * typed rather than browsed, because the folder belongs to the host and no folder dialog on this phone can
 * reach it; a local machine path would name a directory the host does not have.
 */
const GuidWorkspaceFootnote: React.FC<GuidWorkspaceFootnoteProps> = ({
  workspaceDir,
  onSelectWorkspace,
  onClearWorkspace,
}) => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { conversations } = useConversationListSync();
  const [open, setOpen] = useState(false);
  const [hosts, setHosts] = useState<SavedHost[]>([]);
  const [hostsLoaded, setHostsLoaded] = useState(false);
  const [hostId, setHostId] = useState(() => getSelectedHostId() ?? '');
  const [dir, setDir] = useState(workspaceDir);
  const [failure, setFailure] = useState<string | null>(null);
  const [dropdownStyle, setDropdownStyle] = useState<React.CSSProperties>({});
  const triggerRef = useRef<HTMLButtonElement | HTMLDivElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    setDir(workspaceDir);
  }, [workspaceDir]);

  // What this host already holds: the directories its own session rows name, and the sessions themselves.
  // The rows come from the conversation list the app already loaded, so opening this picker costs no extra
  // request — and a host with nothing loaded yet shows that rather than an invented directory.
  const hostRows = useMemo(
    () => (hostId ? conversations.filter((row) => hostIdOf(row) === hostId) : []),
    [conversations, hostId]
  );
  const hostDirectories = useMemo(() => {
    const seen = new Map<string, true>();
    for (const row of hostRows) {
      const cwd = hostWorkspaceOf(row)?.cwd;
      if (cwd && !seen.has(cwd)) seen.set(cwd, true);
    }
    return [...seen.keys()];
  }, [hostRows]);

  // The host list is read once, when the picker is first opened: it costs a host request, and the home page
  // must not spend one before the user asks for it.
  useEffect(() => {
    if (!open || hostsLoaded) return undefined;
    let alive = true;
    void listHosts()
      .then((rows) => {
        if (!alive) return;
        setHosts(rows);
        setHostsLoaded(true);
        setHostId((current) => (rows.some((row) => row.id === current) ? current : (rows[0]?.id ?? '')));
        setFailure(null);
      })
      .catch((error) => {
        if (alive) setFailure(hostFailureText(error, t));
      });
    return () => {
      alive = false;
    };
  }, [open, hostsLoaded, t]);

  const openDropdown = useCallback(() => {
    const el = triggerRef.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    // position above the trigger, aligned to left edge
    setDropdownStyle({
      position: 'fixed',
      left: rect.left,
      bottom: window.innerHeight - rect.top + 6,
      minWidth: 260,
      zIndex: 9999,
    });
    setOpen(true);
  }, []);

  const closeDropdown = useCallback(() => {
    setOpen(false);
    setFailure(null);
  }, []);

  const toggleOpen = useCallback(() => {
    if (open) closeDropdown();
    else openDropdown();
  }, [open, openDropdown, closeDropdown]);

  // close on outside click
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      const target = e.target as Node;
      if (
        triggerRef.current &&
        !triggerRef.current.contains(target) &&
        dropdownRef.current &&
        !dropdownRef.current.contains(target)
      ) {
        closeDropdown();
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open, closeDropdown]);

  const applyDirectory = useCallback(() => {
    const path = dir.trim();
    if (!path) {
      setFailure(t('settings.escape.cwdRequired'));
      return;
    }
    if (!isAbsoluteRemotePath(path)) {
      setFailure(t('settings.escape.cwdNotAbsolute'));
      return;
    }
    setSelectedHostId(hostId || null);
    onSelectWorkspace(path);
    setOpen(false);
    setFailure(null);
  }, [dir, hostId, onSelectWorkspace, t]);

  // Opening one of the host's own sessions from the picker: the session names its own host, so the picker
  // records it forward and the app's conversation route does the rest.
  const openSession = useCallback(
    (conversationId: string) => {
      setSelectedHostId(hostId || null);
      closeDropdown();
      void navigate(`/conversation/${encodeURIComponent(conversationId)}`);
    },
    [closeDropdown, hostId, navigate]
  );

  const workspaceName = workspaceDir ? workspaceDir.split(/[\\/]/).pop() || workspaceDir : '';

  const dropdownEl = open
    ? createPortal(
        <div ref={dropdownRef} className={styles.wsDropdown} style={dropdownStyle}>
          <div className='flex flex-col gap-8px'>
            <div className='flex flex-col gap-4px'>
              <label className='text-12px text-t-tertiary' htmlFor='guid-workspace-host'>
                {t('settings.escape.host')}
              </label>
              <Select
                id='guid-workspace-host'
                size='small'
                value={hostId || undefined}
                placeholder={t('settings.escape.host')}
                onChange={(value) => setHostId(typeof value === 'string' ? value : '')}
                // The option list has to live inside this dropdown, or the outside-click handler closes
                // the whole picker on the first click on an option.
                getPopupContainer={() => dropdownRef.current ?? document.body}
                options={hosts.map((row) => ({ label: row.nickname || row.hostname, value: row.id }))}
              />
              {hostsLoaded && hosts.length === 0 && (
                <span className='text-12px text-t-tertiary'>{t('settings.escape.hostEmpty')}</span>
              )}
            </div>
            <div className='flex flex-col gap-4px'>
              <label className='text-12px text-t-tertiary' htmlFor='guid-workspace-cwd'>
                {t('settings.escape.cwd')}
              </label>
              <Input
                id='guid-workspace-cwd'
                size='small'
                value={dir}
                placeholder={t('settings.escape.cwdPlaceholder')}
                onChange={setDir}
                onPressEnter={applyDirectory}
              />
            </div>
            {failure && (
              <p role='alert' className='m-0 break-words text-12px leading-16px text-danger-6'>
                {failure}
              </p>
            )}
            <div className='flex items-center justify-end'>
              <Button size='small' type='primary' onClick={applyDirectory}>
                {t('common.confirm')}
              </Button>
            </div>

            {/* What this host already holds, so a workspace can be picked from it instead of typed. */}
            {hostId && (
              <div className='flex flex-col gap-2px' data-testid='guid-host-workspaces'>
                <span className='text-12px text-t-tertiary'>{t('guid.workspace.hostDirectories')}</span>
                {hostDirectories.slice(0, 8).map((hostDir) => (
                  <div
                    key={hostDir}
                    role='button'
                    tabIndex={0}
                    title={hostDir}
                    className='cursor-pointer truncate rounded-4px px-6px py-4px text-12px leading-16px text-t-secondary hover:bg-fill-2'
                    onClick={() => {
                      setDir(hostDir);
                      setFailure(null);
                    }}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault();
                        setDir(hostDir);
                        setFailure(null);
                      }
                    }}
                  >
                    {hostDir}
                  </div>
                ))}
                {hostRows.length === 0 && (
                  <span className='text-12px text-t-tertiary'>{t('guid.workspace.hostDirectoriesEmpty')}</span>
                )}
                {hostRows.slice(0, 5).map((row) => (
                  <div
                    key={row.id}
                    role='button'
                    tabIndex={0}
                    className='flex min-w-0 cursor-pointer flex-col rounded-4px px-6px py-4px hover:bg-fill-2'
                    onClick={() => openSession(row.id)}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault();
                        openSession(row.id);
                      }
                    }}
                  >
                    <span className='truncate text-12px leading-16px text-t-primary'>{row.name}</span>
                    <span className='truncate text-11px leading-16px text-t-tertiary'>
                      {hostWorkspaceOf(row)?.cwd ?? ''}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>

          <div className={styles.wsDropdownSep} />

          <div
            className={`${styles.wsDropdownItem} ${workspaceDir ? styles.wsDropdownItemMuted : styles.wsDropdownItemMutedDisabled}`}
            onClick={() => {
              if (workspaceDir) onClearWorkspace();
              closeDropdown();
            }}
          >
            <svg
              width='13'
              height='13'
              fill='none'
              stroke='currentColor'
              strokeWidth='1.8'
              viewBox='0 0 24 24'
              style={{ flexShrink: 0 }}
            >
              <path d='M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2V7z' />
              <line x1='2' y1='2' x2='22' y2='22' strokeWidth='1.5' />
            </svg>
            <span>{t('guid.workspace.noProject')}</span>
          </div>
        </div>,
        document.body
      )
    : null;

  return (
    <div className={styles.workspaceFootnote}>
      {workspaceDir ? (
        <>
          <Tooltip content={workspaceDir} position='top'>
            <div className={styles.workspacePill}>
              <button
                ref={triggerRef as React.RefObject<HTMLButtonElement>}
                className={styles.workspacePillMain}
                onClick={toggleOpen}
              >
                <FolderIcon size={14} />
                <span className={styles.workspacePillName}>{workspaceName}</span>
                <Down
                  theme='outline'
                  size='12'
                  fill='currentColor'
                  style={{ flexShrink: 0, transform: 'translateY(1px)' }}
                />
              </button>
              <span
                role='button'
                aria-label={t('guid.workspace.clearWorkspace')}
                className={styles.workspacePillClose}
                onClick={(e) => {
                  e.stopPropagation();
                  onClearWorkspace();
                }}
              >
                <Close theme='outline' size='10' fill='currentColor' />
              </span>
            </div>
          </Tooltip>
          {dropdownEl}
        </>
      ) : (
        <>
          <button
            ref={triggerRef as React.RefObject<HTMLButtonElement>}
            className={styles.workspaceEmptyBtn}
            data-testid='workspace-selector-btn'
            onClick={toggleOpen}
          >
            <FolderIcon size={14} />
            <span>{t('guid.workspace.workInProject')}</span>
            <Down
              theme='outline'
              size='12'
              fill='currentColor'
              style={{ flexShrink: 0, transform: 'translateY(1px)' }}
            />
          </button>
          {dropdownEl}
        </>
      )}
    </div>
  );
};

export default GuidWorkspaceFootnote;
