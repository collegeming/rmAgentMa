/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { readConnectionChallenge, type ConnectionChallenge } from './hostInteractions';
import { useCallback, useEffect, useState } from 'react';

/**
 * The connection that waits for the user, read from the host. The host publishes no event when a host key,
 * a password or a key passphrase is asked for, so this is the one surface that polls: the read is repeated
 * while the document is visible, and a challenge that was answered or dropped elsewhere disappears with the
 * next read instead of being kept alive here. The surface that mounts this hook decides where it may run.
 */
export const CHALLENGE_POLL_MILLIS = 3000;

export type ConnectionChallengeState = {
  challenge: ConnectionChallenge | null;
  /** The failure of the last read, or null. A host that asks for nothing reports no failure at all. */
  failure: unknown;
  /** Reads the pending challenge once: the retry behind a failed read, and the settle after an answer. */
  refresh: () => void;
};

export function useConnectionChallenge(intervalMillis = CHALLENGE_POLL_MILLIS): ConnectionChallengeState {
  const [challenge, setChallenge] = useState<ConnectionChallenge | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const [revision, setRevision] = useState(0);
  const refresh = useCallback(() => setRevision((current) => current + 1), []);

  useEffect(() => {
    let active = true;
    let reading = false;
    const read = () => {
      if (reading || (typeof document !== 'undefined' && document.hidden)) return;
      reading = true;
      readConnectionChallenge()
        .then((current) => {
          if (!active) return;
          setChallenge(current);
          setFailure(null);
        })
        .catch((error: unknown) => {
          if (active) setFailure(error);
        })
        .finally(() => {
          reading = false;
        });
    };
    read();
    const timer = setInterval(read, intervalMillis);
    return () => {
      active = false;
      clearInterval(timer);
    };
  }, [intervalMillis, revision]);

  return { challenge, failure, refresh };
}
