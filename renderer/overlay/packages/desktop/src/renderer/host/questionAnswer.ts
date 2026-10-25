/**
 * @license
 * Copyright 2026 rmAgentMa contributors
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The question card's own reading of what an answer may be. The host validates the answer again with the
 * projection the Compose conversation form uses, so nothing here can widen what reaches the Agent; it mirrors
 * that projection (a single string form takes the text, a multi-field form takes one JSON object whose values
 * match the types the Agent stated) so the card offers an answer the host is able to accept, and it reads the
 * host's own verdict as the final word.
 */

export type QuestionField = {
  name: string;
  label: string;
  type: string;
  required: boolean;
  choices: string[];
};

export type QuestionOption = { id: string; label: string };

const recordOf = (value: unknown): Record<string, unknown> =>
  value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {};

const stringOf = (value: unknown): string => (typeof value === 'string' ? value : '');

/** The fields the host projected from the schema the Agent stated; an unreadable row is no field at all. */
export function readQuestionFields(value: unknown): QuestionField[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((entry) => {
    const row = recordOf(entry);
    const name = stringOf(row.name);
    if (!name) return [];
    return [
      {
        name,
        label: stringOf(row.label) || name,
        type: stringOf(row.type),
        required: row.required === true,
        choices: Array.isArray(row.choices)
          ? row.choices.filter((item): item is string => typeof item === 'string')
          : [],
      },
    ];
  });
}

/** The options the Agent offered beside the schema; they name an answer, they are never the answer itself. */
export function readQuestionOptions(value: unknown): QuestionOption[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((entry) => {
    const row = recordOf(entry);
    const id = stringOf(row.option_id);
    if (!id) return [];
    return [{ id, label: stringOf(row.name) || id }];
  });
}

/** The schema an answer is validated against, exactly as the Agent stated it; null when it was unusable. */
export function readQuestionSchema(value: unknown): Record<string, unknown> | null {
  const schema = recordOf(value);
  return Object.keys(schema).length > 0 ? schema : null;
}

/**
 * Whether the host can read this question at all. A question without a schema the host could project has no
 * answerable structure, so the card states that instead of offering an answer that would fail as unsupported.
 */
export function questionAnswerable(schema: Record<string, unknown> | null, fields: QuestionField[]): boolean {
  return schema !== null && fields.length > 0;
}

/** The answers one tap can send: the schema's own choices, or the options the Agent offered when it stated none. */
export function singleStringChoices(
  fields: QuestionField[],
  options: QuestionOption[]
): { answer: string; label: string }[] {
  const choices = fields.length === 1 ? fields[0].choices : [];
  if (choices.length > 0) return choices.map((choice) => ({ answer: choice, label: choice }));
  return options.map((option) => ({ answer: option.id, label: option.label }));
}

const isPrimitive = (value: unknown): boolean =>
  value === null || typeof value === 'string' || typeof value === 'boolean' || typeof value === 'number';

/** One value against one field's schema, with the types the projection accepts and nothing looser. */
function matchesQuestionValue(value: unknown, schema: Record<string, unknown>): boolean {
  if (value === null) return false;
  const type = stringOf(schema.type);
  const matches = (): boolean => {
    switch (type) {
      case 'string':
        return typeof value === 'string';
      case 'boolean':
        return typeof value === 'boolean';
      case 'number':
        return typeof value === 'number' && Number.isFinite(value);
      case 'integer':
        return typeof value === 'number' && Number.isSafeInteger(value);
      case 'array': {
        if (!Array.isArray(value)) return false;
        const items = recordOf(schema.items);
        return value.every((item) =>
          Object.keys(items).length === 0 ? isPrimitive(item) && item !== null : matchesQuestionValue(item, items)
        );
      }
      default:
        return false;
    }
  };
  if (!matches()) return false;
  const choices = schema.enum;
  if (!Array.isArray(choices)) return true;
  // A choice is compared as the value it is: a string answer answers a string choice alone.
  return choices.some((choice) => choice === value);
}

/**
 * The host's own answer rule, read here so the card can say "this is not answerable" before a request is sent.
 * A single-string form takes non-blank text that matches the one field, a multi-field form takes a JSON object
 * that carries every required field and matches each field's type, with unknown fields only when the schema
 * allows them.
 */
export function validQuestionAnswer(
  schema: Record<string, unknown> | null,
  singleString: boolean,
  answer: string
): boolean {
  if (!schema) return false;
  // A schema that states no properties at all has nothing an answer could be matched against; one that states
  // an empty set still carries its own `required` and `additionalProperties` rules, exactly as the host reads it.
  if (!schema.properties || typeof schema.properties !== 'object' || Array.isArray(schema.properties)) return false;
  const properties = schema.properties as Record<string, unknown>;
  if (singleString) {
    const values = Object.values(properties);
    const field = recordOf(values[0]);
    return values.length === 1 && answer.trim().length > 0 && matchesQuestionValue(answer, field);
  }
  let response: unknown;
  try {
    response = JSON.parse(answer) as unknown;
  } catch {
    return false;
  }
  const fields = recordOf(response);
  if (Array.isArray(response) || typeof response !== 'object' || response === null) return false;
  if (Object.values(fields).some((value) => !isPrimitive(value) && !Array.isArray(value))) return false;
  const required = Array.isArray(schema.required)
    ? schema.required.filter((name): name is string => typeof name === 'string')
    : [];
  if (required.some((name) => !(name in fields))) return false;
  return Object.entries(fields).every(([name, value]) => {
    const field = Object.keys(properties).includes(name) ? recordOf(properties[name]) : null;
    if (field === null) return schema.additionalProperties !== false;
    return matchesQuestionValue(value, field);
  });
}
