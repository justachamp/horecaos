/**
 * A phone number, the way this brand writes it (Settings 10.12).
 *
 * The platform sends a number two ways: E.164 (`+998901234567`) where the
 * operator may see it whole, and masked (`+998 90 ••• •• 42`) where they may not
 * (ADR 0029). A brand's display pattern re-writes either: `+### (##) ###-##-##`
 * turns the first into `+998 (90) 123-45-67` and the second into
 * `+998 (90) •••-••-42`, because a mask bullet counts as a digit slot exactly as
 * a digit does.
 *
 * A number that does not fill the pattern — a local nine-digit form, a foreign
 * number, a half-typed one — is returned exactly as it arrived, never padded and
 * never truncated: showing a number in the wrong shape is a nuisance, showing a
 * wrong number is a wrong call.
 */

import { activeRegionalFormats } from './regional-format';

/** A digit, or the bullet the platform masks one with. */
const SLOT_CHARACTER = /[0-9•]/u;

/** Writes `raw` in `pattern` (one `#` per digit); `null` shows it as it arrived. */
export function formatPhone(
  raw: string | null | undefined,
  pattern: string | null = activeRegionalFormats().phoneDisplayPattern,
): string {
  if (raw === null || raw === undefined || pattern === null || pattern === '') {
    return raw ?? '';
  }
  const characters = Array.from(raw).filter((character) => SLOT_CHARACTER.test(character));
  const slots = Array.from(pattern).filter((character) => character === '#').length;
  if (slots === 0 || characters.length !== slots) {
    return raw;
  }
  let next = 0;
  return Array.from(pattern)
    .map((character) => (character === '#' ? characters[next++] : character))
    .join('');
}
