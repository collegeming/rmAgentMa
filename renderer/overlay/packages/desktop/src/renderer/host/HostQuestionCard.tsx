/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import type { TMessage } from '@/common/chat/chatLib';
import { submitQuestionAnswer } from '@renderer/host/hostInteractions';
import {
  questionAnswerable,
  readQuestionFields,
  readQuestionOptions,
  readQuestionSchema,
  singleStringChoices,
  validQuestionAnswer,
} from '@renderer/host/questionAnswer';
import { hostFailureText } from '@renderer/pages/hosts/hostsApi';
import { Button, Input } from '@arco-design/web-react';
import React, { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';

const recordOf = (value: unknown): Record<string, unknown> =>
  value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {};

const textOf = (row: Record<string, unknown>, name: string): string => (typeof row[name] === 'string' ? row[name] : '');

const statusOf = (error: unknown): number | undefined =>
  error && typeof error === 'object' && 'status' in error ? (error as { status?: number }).status : undefined;

/**
 * The host's own question card. The Agent asked with a schema, and an answer has to satisfy exactly that schema:
 * a single-string question takes the text and a multi-field one takes one JSON object. Whether the card is still
 * the pending question of the open conversation is the host's decision, taken when the answer arrives — a card
 * that is no longer pending is refused with its own code, never answered from here.
 */
const HostQuestionCard: React.FC<{ message: TMessage }> = React.memo(({ message }) => {
  const { t } = useTranslation();
  const content = recordOf(message.content);
  const fields = useMemo(() => readQuestionFields(content.fields), [content.fields]);
  const options = useMemo(() => readQuestionOptions(content.options), [content.options]);
  const schema = useMemo(() => readQuestionSchema(content.requested_schema), [content.requested_schema]);
  const singleString = content.single_string === true;
  const answerable = questionAnswerable(schema, fields);
  const requestRef = textOf(content, 'request_id') || textOf(content, 'question_id');
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [answered, setAnswered] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  // A question the host refused as no longer pending stays visible, and it is never offered again.
  const [stale, setStale] = useState(false);

  const send = async (answer: string) => {
    if (!requestRef) return;
    setBusy(true);
    setFailure(null);
    try {
      await submitQuestionAnswer(message.conversation_id, requestRef, answer, content.epoch as number | string);
      setAnswered(true);
    } catch (error) {
      setFailure(error);
      if (statusOf(error) === 409) setStale(true);
    } finally {
      setBusy(false);
    }
  };

  if (answered) {
    return (
      <div role='status' data-testid='host-question-answered' className='text-13px leading-20px text-t-tertiary'>
        {t('messages.askAnswered')}
      </div>
    );
  }

  const choices = singleString ? singleStringChoices(fields, options) : [];
  const blocked = stale || busy;
  const canSubmit = answerable && !blocked && Boolean(requestRef) && validQuestionAnswer(schema, singleString, draft);
  const title = textOf(content, 'title');
  const text = textOf(content, 'message');

  return (
    <div
      data-testid='host-question-card'
      className='w-full min-w-0 flex flex-col gap-8px rounded-12px bg-fill-2 p-12px text-13px leading-20px'
    >
      {title ? <div className='break-words font-[600] text-t-primary'>{title}</div> : null}
      {text ? <div className='break-words text-t-secondary'>{text}</div> : null}
      {!answerable ? (
        <div role='alert' data-testid='host-question-unsupported' className='break-words text-t-tertiary'>
          {t('messages.hostQuestionUnsupported')}
        </div>
      ) : (
        <>
          {!singleString && (
            <ul
              data-testid='host-question-fields'
              className='m-0 flex list-none flex-col gap-4px p-0 text-12px leading-18px text-t-secondary'
            >
              {fields.map((field) => (
                <li key={field.name} className='break-words'>
                  <span>{field.label}</span>
                  {field.type ? <span className='text-t-tertiary'>（{field.type}）</span> : null}
                  {field.required ? <span className='text-danger-6'> {t('messages.hostQuestionRequired')}</span> : null}
                  {field.choices.length > 0 ? (
                    <span className='text-t-tertiary'> {field.choices.join('、')}</span>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
          {choices.length > 0 && (
            <div className='flex flex-wrap gap-8px'>
              {choices.map((choice, index) => (
                <Button
                  key={`${choice.answer}:${index}`}
                  size='mini'
                  disabled={blocked || !validQuestionAnswer(schema, true, choice.answer)}
                  data-testid={`host-question-choice-${index}`}
                  onClick={() => void send(choice.answer)}
                >
                  {choice.label}
                </Button>
              ))}
            </div>
          )}
          <label className='text-12px leading-18px text-t-secondary' htmlFor='host-question-answer'>
            {t(singleString ? 'messages.hostQuestionAnswer' : 'messages.hostQuestionJson')}
          </label>
          <Input.TextArea
            id='host-question-answer'
            value={draft}
            disabled={blocked}
            autoSize={{ minRows: singleString ? 1 : 3, maxRows: 8 }}
            placeholder={singleString ? undefined : t('messages.hostQuestionJsonHint')}
            onChange={setDraft}
          />
          <Button
            type='primary'
            size='small'
            className='self-start'
            loading={busy}
            disabled={!canSubmit}
            data-testid='host-question-submit'
            onClick={() => void send(draft)}
          >
            {t('messages.askSubmit')}
          </Button>
        </>
      )}
      {failure ? (
        <p role='alert' data-testid='host-question-failure' className='m-0 break-words text-danger-6'>
          {hostFailureText(failure, t)}
        </p>
      ) : null}
    </div>
  );
});

export default HostQuestionCard;
