/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */
import { describe, expect, it } from 'vitest';
import {
  questionAnswerable,
  readQuestionFields,
  readQuestionOptions,
  readQuestionSchema,
  singleStringChoices,
  validQuestionAnswer,
} from '@/renderer/host/questionAnswer';

const schema = (properties: Record<string, unknown>, extra: Record<string, unknown> = {}) => ({
  type: 'object',
  properties,
  ...extra,
});

const singleStringField = (choices: string[]) => ({
  name: 'value',
  label: 'value',
  type: 'string',
  required: false,
  choices,
});

describe('the answer rule the host validates with', () => {
  it('takes the text of a single-string question, and only a text the field accepts', () => {
    const field = schema({ value: { type: 'string' } });
    expect(validQuestionAnswer(field, true, ' hello ')).toBe(true);
    expect(validQuestionAnswer(field, true, '   ')).toBe(false);
    expect(validQuestionAnswer(field, true, '42')).toBe(true);

    const enumerated = schema({ value: { type: 'string', enum: ['alpha', 'beta'] } });
    expect(validQuestionAnswer(enumerated, true, 'alpha')).toBe(true);
    expect(validQuestionAnswer(enumerated, true, 'gamma')).toBe(false);
  });

  it('takes one JSON object for a multi-field question, with the types the Agent stated', () => {
    const form = schema(
      {
        name: { type: 'string' },
        count: { type: 'integer' },
        ratio: { type: 'number' },
        confirm: { type: 'boolean' },
        tags: { type: 'array', items: { type: 'string' } },
      },
      { required: ['name', 'count'] }
    );
    expect(validQuestionAnswer(form, false, '{"name":"a","count":2}')).toBe(true);
    expect(validQuestionAnswer(form, false, '{"name":"a"}')).toBe(false);
    expect(validQuestionAnswer(form, false, '{"name":"a","count":"2"}')).toBe(false);
    expect(validQuestionAnswer(form, false, '{"name":"a","count":2.5}')).toBe(false);
    expect(validQuestionAnswer(form, false, '{"name":"a","count":2,"confirm":true,"ratio":1.5}')).toBe(true);
    expect(validQuestionAnswer(form, false, '{"name":"a","count":2,"confirm":"yes"}')).toBe(false);
    expect(validQuestionAnswer(form, false, '{"name":"a","count":2,"tags":["x"]}')).toBe(true);
    expect(validQuestionAnswer(form, false, '{"name":"a","count":2,"tags":[1]}')).toBe(false);
    expect(validQuestionAnswer(form, false, '{"name":"a","count":2,"tags":{"x":1}}')).toBe(false);
  });

  it('refuses an answer that is not the JSON object it asked for', () => {
    const form = schema({ name: { type: 'string' } }, { required: ['name'] });
    expect(validQuestionAnswer(form, false, '')).toBe(false);
    expect(validQuestionAnswer(form, false, 'name')).toBe(false);
    expect(validQuestionAnswer(form, false, '["name"]')).toBe(false);
    expect(validQuestionAnswer(form, false, 'null')).toBe(false);
    expect(validQuestionAnswer(form, false, '{"name":{"nested":true}}')).toBe(false);
  });

  it('holds unknown fields to the schema, and a question without a schema to nothing at all', () => {
    const closed = schema({ name: { type: 'string' } }, { required: ['name'], additionalProperties: false });
    expect(validQuestionAnswer(closed, false, '{"name":"a","other":"b"}')).toBe(false);
    expect(validQuestionAnswer(schema({ name: { type: 'string' } }), false, '{"name":"a","other":"b"}')).toBe(true);
    expect(validQuestionAnswer(schema({ name: { type: 'string' } }), false, '{"name":"a","other":null}')).toBe(true);
    expect(validQuestionAnswer(schema({ name: { type: 'string' } }), false, '{"other":"b"}')).toBe(true);
    expect(validQuestionAnswer(null, true, 'anything')).toBe(false);
    expect(validQuestionAnswer(schema({}), false, '{"a":1}')).toBe(true);
    expect(validQuestionAnswer({ type: 'object' }, false, '{"a":1}')).toBe(false);
  });

  it('reads the fields, options and schema the host projected, and no more', () => {
    expect(
      readQuestionFields([
        { name: 'name', label: 'Name', type: 'string', required: true, choices: ['a', 2] },
        { label: 'unnamed' },
        'not a field',
      ])
    ).toEqual([{ name: 'name', label: 'Name', type: 'string', required: true, choices: ['a'] }]);
    expect(readQuestionFields(null)).toEqual([]);
    expect(readQuestionOptions([{ option_id: 'allow_once', name: 'Allow once' }, { name: 'no id' }])).toEqual([
      { id: 'allow_once', label: 'Allow once' },
    ]);
    expect(readQuestionSchema({ type: 'object' })).toEqual({ type: 'object' });
    expect(readQuestionSchema(null)).toBeNull();
    expect(readQuestionSchema({})).toBeNull();
    expect(questionAnswerable({ type: 'object' }, [])).toBe(false);
    expect(questionAnswerable(null, [{ name: 'a', label: 'a', type: 'string', required: false, choices: [] }])).toBe(
      false
    );
  });

  it('answers a single-string question with the schema choices, or with the offered options when it stated none', () => {
    expect(singleStringChoices([singleStringField(['alpha'])], [{ id: 'allow_once', label: 'Allow once' }])).toEqual([
      { answer: 'alpha', label: 'alpha' },
    ]);
    expect(singleStringChoices([singleStringField([])], [{ id: 'allow_once', label: 'Allow once' }])).toEqual([
      { answer: 'allow_once', label: 'Allow once' },
    ]);
  });
});
