/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { describe, expect, it } from 'vitest';
import { BUILTIN_THEMES } from '@/renderer/theme/builtinThemes';
import { defaultThemeCover } from '@/renderer/pages/settings/AppearanceSettings/themeCovers';

describe('built-in theme cover admission', () => {
  it('uses the existing palette preview instead of a branded image', () => {
    expect(defaultThemeCover).toBeUndefined();
    expect(BUILTIN_THEMES.every((theme) => !theme.cover)).toBe(true);
    expect(BUILTIN_THEMES.map((theme) => theme.appearance)).toEqual(['light', 'dark']);
  });
});
