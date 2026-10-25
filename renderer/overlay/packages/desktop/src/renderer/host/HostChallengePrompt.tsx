/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { answerConnectionChallenge, type ConnectionChallenge } from '@renderer/host/hostInteractions';
import { useConnectionChallenge } from '@renderer/host/useConnectionChallenge';
import { hostFailureText } from '@renderer/pages/hosts/hostsApi';
import { Button, Input } from '@arco-design/web-react';
import React, { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

/**
 * How one challenge is answered: a host key and the confirmation of a key's use continue on `accept` and cancel
 * on anything else, a password or a key passphrase are the credential itself, and a kind this build does not
 * know is stated as unsupported instead of being answered blind.
 */
function modeOf(challenge: ConnectionChallenge): 'accept' | 'secret' | 'unsupported' {
  if (challenge.kind === 'host_key') return 'accept';
  if (challenge.kind === 'key_passphrase') return challenge.secret ? 'secret' : 'accept';
  if (challenge.kind === 'password') return 'secret';
  return 'unsupported';
}

/**
 * The prompt behind a connection that waits for the user: the host's own key confirmation, login password or key
 * passphrase. The credential is typed into a secret field, handed to the pending request alone and dropped from
 * this surface afterwards — it is never stored, logged, echoed or put into a URL. A read that failed, and a
 * challenge kind this build cannot answer, are stated as themselves with a retry, so a connection waiting for
 * the user is never silently stuck.
 */
const HostChallengePrompt: React.FC = () => {
  const { t } = useTranslation();
  const { challenge, failure, refresh } = useConnectionChallenge();
  const [secret, setSecret] = useState('');
  const [busy, setBusy] = useState(false);
  const [answerFailure, setAnswerFailure] = useState<unknown>(null);

  // A new prompt never carries the previous one's credential, and nothing ever writes it anywhere else.
  useEffect(() => {
    setSecret('');
    setAnswerFailure(null);
  }, [challenge?.id]);

  const submit = async (answer: string | null) => {
    if (!challenge) return;
    setBusy(true);
    setAnswerFailure(null);
    try {
      await answerConnectionChallenge(challenge.id, answer);
      setSecret('');
      refresh();
    } catch (error) {
      setAnswerFailure(error);
    } finally {
      setBusy(false);
    }
  };

  if (!challenge) {
    if (!failure) return null;
    return (
      <div
        role='alert'
        data-testid='host-challenge-unavailable'
        className='fixed inset-x-0 bottom-0 z-[999] flex flex-wrap items-center gap-8px border-t border-solid bg-1 p-12px text-13px leading-18px text-danger-6'
      >
        <span className='min-w-0 break-words'>
          {t('conversation.hostTools.challengeUnavailable', { reason: hostFailureText(failure, t) })}
        </span>
        <Button size='mini' data-testid='host-challenge-retry' onClick={() => refresh()}>
          {t('conversation.hostTools.retry')}
        </Button>
      </div>
    );
  }

  const mode = modeOf(challenge);
  const canSubmit = !busy && (mode !== 'secret' || secret.length > 0);

  return (
    <div
      role='dialog'
      aria-label={t('conversation.hostTools.challengeTitle')}
      data-testid='host-challenge-prompt'
      className='fixed inset-x-0 bottom-0 z-[999] flex flex-col gap-8px border-t border-solid bg-1 p-12px text-13px leading-20px'
    >
      <div className='flex flex-col gap-4px'>
        <span className='font-[600] text-t-primary'>{t('conversation.hostTools.challengeTitle')}</span>
        {challenge.hostName ? <span className='break-words text-t-secondary'>{challenge.hostName}</span> : null}
        {challenge.message ? <span className='break-words text-t-secondary'>{challenge.message}</span> : null}
      </div>
      {mode === 'unsupported' && (
        <div role='alert' className='break-words text-t-tertiary'>
          {t('conversation.hostTools.challengeUnknown', { kind: challenge.kind })}
        </div>
      )}
      {mode === 'secret' && (
        <div className='flex flex-col gap-4px'>
          <label className='text-12px leading-18px text-t-secondary' htmlFor='host-challenge-secret'>
            {t('conversation.hostTools.challengeSecret')}
          </label>
          <Input
            id='host-challenge-secret'
            type='password'
            autoComplete='new-password'
            autoFocus
            value={secret}
            disabled={busy}
            onChange={setSecret}
            onPressEnter={() => {
              if (canSubmit) void submit(secret);
            }}
          />
        </div>
      )}
      {answerFailure ? (
        <p role='alert' data-testid='host-challenge-failure' className='m-0 break-words text-danger-6'>
          {hostFailureText(answerFailure, t)}
        </p>
      ) : null}
      <div className='flex flex-wrap gap-8px'>
        {mode !== 'unsupported' && (
          <Button
            type='primary'
            size='small'
            loading={busy}
            disabled={!canSubmit}
            data-testid='host-challenge-confirm'
            onClick={() => void submit(mode === 'accept' ? 'accept' : secret)}
          >
            {t(
              mode === 'accept' ? 'conversation.hostTools.challengeAccept' : 'conversation.hostTools.challengeConfirm'
            )}
          </Button>
        )}
        <Button size='small' disabled={busy} data-testid='host-challenge-cancel' onClick={() => void submit(null)}>
          {t(mode === 'accept' ? 'conversation.hostTools.challengeReject' : 'common.cancel')}
        </Button>
      </div>
    </div>
  );
};

export default HostChallengePrompt;
