/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { Tooltip } from '@arco-design/web-react';
import type { SiderTooltipProps } from '@renderer/utils/ui/siderTooltip';
import classNames from 'classnames';
import React from 'react';

interface SiderHostToolEntryProps {
  isMobile: boolean;
  isActive?: boolean;
  collapsed: boolean;
  label: string;
  icon: React.ReactNode;
  /** Stable hook for tests and for the collapsed tooltip. */
  testId: string;
  siderTooltipProps: SiderTooltipProps;
  onClick: () => void;
}

/**
 * One sidebar entry of the cross-session surfaces (content search, the waiting-work list). It reads the same
 * as the host page's own entry: an icon column when the sidebar is collapsed, a label when it is not.
 */
const SiderHostToolEntry: React.FC<SiderHostToolEntryProps> = ({
  isMobile,
  isActive = false,
  collapsed,
  label,
  icon,
  testId,
  siderTooltipProps,
  onClick,
}) => (
  <Tooltip {...siderTooltipProps} content={label} position='right'>
    <div
      data-testid={testId}
      aria-label={label}
      className={classNames(
        'box-border group h-34px w-full flex items-center cursor-pointer shrink-0 transition-all text-t-primary',
        collapsed ? 'justify-center rd-8px' : 'justify-start gap-8px ps-10px pe-8px rd-0.5rem',
        isMobile && 'sider-action-btn-mobile',
        isActive ? 'bg-fill-3' : 'hover:bg-fill-3 active:bg-fill-4'
      )}
      onClick={onClick}
    >
      <span className='size-22px flex items-center justify-center shrink-0 text-t-primary'>
        <span className='block leading-none' style={{ lineHeight: 0 }}>
          {icon}
        </span>
      </span>
      <span className='collapsed-hidden text-t-primary text-14px font-[500] leading-24px'>{label}</span>
    </div>
  </Tooltip>
);

export default SiderHostToolEntry;
