/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { useEffect, useRef } from 'react';
import type { TouchEvent } from 'react';

/** How far a finger may travel before the gesture reads as a scroll instead of a hold. */
const MOVE_TOLERANCE_PX = 12;
/** How long a finger must rest before the press counts as a long press. */
const HOLD_MS = 500;

export type LongPressPoint = { x: number; y: number };

type LongPressHandlers = {
  onTouchStart: (event: TouchEvent<HTMLElement>) => void;
  onTouchMove: (event: TouchEvent<HTMLElement>) => void;
  onTouchEnd: (event: TouchEvent<HTMLElement>) => void;
  onTouchCancel: () => void;
};

/**
 * Turns a held finger into the action a right-click used to run, because a phone holds no right button:
 * put these handlers on the element that carried `onContextMenu`. A press that fires swallows the click
 * that would follow it, so the element's own tap action does not also run.
 */
export function createLongPressHandlers(
  onLongPress: (point: LongPressPoint) => void,
  holdMs = HOLD_MS
): LongPressHandlers {
  let timer: ReturnType<typeof setTimeout> | null = null;
  let origin: LongPressPoint | null = null;
  let fired = false;

  const clear = () => {
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
  };

  return {
    onTouchStart(event) {
      const touch = event.touches[0];
      if (!touch) return;
      origin = { x: touch.clientX, y: touch.clientY };
      fired = false;
      clear();
      timer = setTimeout(() => {
        timer = null;
        const point = origin;
        if (!point) return;
        fired = true;
        onLongPress(point);
      }, holdMs);
    },
    onTouchMove(event) {
      const touch = event.touches[0];
      const point = origin;
      if (!touch || !point) return;
      if (
        Math.abs(touch.clientX - point.x) > MOVE_TOLERANCE_PX ||
        Math.abs(touch.clientY - point.y) > MOVE_TOLERANCE_PX
      ) {
        origin = null;
        clear();
      }
    },
    onTouchEnd(event) {
      clear();
      origin = null;
      if (!fired) return;
      fired = false;
      event.preventDefault();
      event.stopPropagation();
    },
    onTouchCancel() {
      clear();
      origin = null;
      fired = false;
    },
  };
}

/** {@link createLongPressHandlers} held across renders: a timer that started before a re-render survives it. */
export function useLongPress(onLongPress: (point: LongPressPoint) => void, holdMs = HOLD_MS): LongPressHandlers {
  const callback = useRef(onLongPress);
  useEffect(() => {
    callback.current = onLongPress;
  }, [onLongPress]);
  const handlers = useRef<LongPressHandlers | null>(null);
  if (handlers.current === null) {
    handlers.current = createLongPressHandlers((point) => callback.current(point), holdMs);
  }
  return handlers.current;
}
