/**
 * ADR 0161: the shape of a marking code, shared in spirit with the platform.
 *
 * The same normalisation runs here (instant feedback at the scanner) and on the server
 * (`ordering.domain.MarkingCodes`, the authority), and both are held to one vector file --
 * `testing/marking-normalisation-vectors.json` here, `src/test/resources/marking/
 * normalisation-vectors.json` there, byte-identical copies that `tools/checks/repo_hygiene.py`
 * fails to let drift. The rules, in order:
 *
 *  1. strip a leading AIM symbology identifier (`]d2` or `]d1`);
 *  2. strip trailing CR, LF, TAB and spaces, and leading spaces;
 *  3. refuse unless every character is U+0020 to U+007E or the GS control character (U+001D) and the
 *     length is 19 to 300.
 *
 * Nothing cryptographic is verified and nothing here keeps what it is given: these are pure
 * functions of their argument, with no storage, no logging and no network.
 */

/** The GS1 group separator (FNC1 as a scanner sends it): part of a code, never a terminator. */
export const GROUP_SEPARATOR = '\u001d';

const MIN_LENGTH = 19;
const MAX_LENGTH = 300;
const TERMINATORS = new Set(['\r', '\n', '\t', ' ']);

/** The canonical string -- the one that is hashed, stored and sent -- or `null` when the input is not a marking code. */
export function normaliseMarkingCode(input: string): string | null {
  let value = input;
  if (value.startsWith(']d2') || value.startsWith(']d1')) {
    value = value.slice(3);
  }
  let end = value.length;
  while (end > 0 && TERMINATORS.has(value[end - 1])) {
    end--;
  }
  let start = 0;
  while (start < end && value[start] === ' ') {
    start++;
  }
  value = value.slice(start, end);
  if (value.length < MIN_LENGTH || value.length > MAX_LENGTH) {
    return null;
  }
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index);
    const printable = code >= 0x20 && code <= 0x7e;
    if (!printable && code !== 0x1d) {
      return null;
    }
  }
  return value;
}

export interface Gs1Structure {
  readonly valid: boolean;
  /** The GTIN-14 when the structure is valid, else `null`. */
  readonly gtin: string | null;
}

/**
 * `01` + a GTIN-14 with a valid GS1 mod-10 check digit + `21` + a serial of 1 to 20 characters (up to the first GS or the
 * end), then optionally GS and further elements carried verbatim. This is the Asl Belgisi code
 * shape ADR 0161 assumes behind the `fiscal.marking.structure_check` policy key; the browser uses
 * it only to explain a refusal (a scanner that drops the separator), never to decide one.
 */
export function checkGs1Structure(canonical: string): Gs1Structure {
  const invalid: Gs1Structure = { valid: false, gtin: null };
  if (!canonical.startsWith('01') || canonical.length < 2 + 14 + 2 + 1) {
    return invalid;
  }
  const gtin = canonical.slice(2, 16);
  if (!/^\d{14}$/.test(gtin) || !hasValidCheckDigit(gtin)) {
    return invalid;
  }
  if (canonical.slice(16, 18) !== '21') {
    return invalid;
  }
  const rest = canonical.slice(18);
  const separator = rest.indexOf(GROUP_SEPARATOR);
  const serialLength = separator === -1 ? rest.length : separator;
  if (serialLength < 1 || serialLength > 20) {
    return invalid;
  }
  return { valid: true, gtin };
}

/** GS1 mod-10: weights 3,1,3,1... from the digit left of the check digit. */
function hasValidCheckDigit(gtin14: string): boolean {
  let total = 0;
  for (let index = 12; index >= 0; index--) {
    const weight = (12 - index) % 2 === 0 ? 3 : 1;
    total += Number(gtin14[index]) * weight;
  }
  return (10 - (total % 10)) % 10 === Number(gtin14[13]);
}

/** What a scan looked like, never what it said: lengths and counts only, safe to show on screen. */
export interface ScanDiagnostic {
  readonly length: number;
  readonly groupSeparators: number;
  readonly aimIdentifier: boolean;
  readonly gs1Shaped: boolean;
}

export function diagnoseScan(raw: string): ScanDiagnostic {
  const canonical = normaliseMarkingCode(raw);
  return {
    length: raw.length,
    groupSeparators: [...raw].filter((character) => character === GROUP_SEPARATOR).length,
    aimIdentifier: raw.startsWith(']d2') || raw.startsWith(']d1'),
    gs1Shaped: canonical !== null && checkGs1Structure(canonical).valid,
  };
}
