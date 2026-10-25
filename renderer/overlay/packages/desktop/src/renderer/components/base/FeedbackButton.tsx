/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 * Modified by rmAgentMa contributors for the renderer probe.
 */

import React from 'react';

type FeedbackButtonProps = {
  /** Pre-selects the module in the feedback modal (see FEEDBACK_MODULES tags). */
  module?: string;
  /** Extra Sentry tags attached to the feedback event. */
  feedbackTags?: Record<string, string>;
  /** Extra structured context attached to the feedback event. */
  feedbackExtra?: Record<string, unknown>;
  /** Additional classes appended to the default pill styling. */
  className?: string;
};

/**
 * Hides every inline feedback chip in this build: a report goes to AionUi's own service, which this
 * product does not use, so the entry is kept out of the interface instead of failing on submit. The
 * component is kept (rather than deleted upstream-side) so call sites stay untouched.
 */
const FeedbackButton: React.FC<FeedbackButtonProps> = () => null;

export default FeedbackButton;
