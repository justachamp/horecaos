/**
 * Small readers for a wire body that is not trusted to have the shape the documentation promises.
 * Shared by the content and editorial parsers: each returns the value only when it is exactly the
 * kind asked for, and `undefined` otherwise.
 */

export type UnknownRecord = Readonly<Record<string, unknown>>;

export function isRecord(value: unknown): value is UnknownRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function text(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

/** An optional text: absent unless it is text with something in it. */
export function optionalText(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() !== '' ? value : undefined;
}

export function positiveInt(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isInteger(value) && value > 0 ? value : undefined;
}
