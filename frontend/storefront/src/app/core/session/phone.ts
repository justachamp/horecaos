/**
 * Uzbek mobile numbers, as a customer types them and as an API must receive them.
 *
 * One country only, and that is a decision rather than an omission: this
 * storefront sells in Uzbekistan, an SMS code goes to an Uzbek network, and a
 * permissive international parser would accept numbers no code could ever reach
 * and fail at the far end with a worse message.
 *
 * The canonical form is E.164 — `+998901234567`, thirteen characters, no spaces
 * — because that is what a messaging gateway takes. The spaced form is only ever
 * for reading.
 */

const DIGITS = /\d/g;

/** `+998 90 123 45 67`, for showing back to the person who typed it. */
export function formatUzPhone(input: string): string {
  const national = nationalDigits(input);
  if (national.length === 0) {
    return '';
  }
  const groups = [
    national.slice(0, 2),
    national.slice(2, 5),
    national.slice(5, 7),
    national.slice(7, 9),
  ].filter((group) => group.length > 0);
  return `+998 ${groups.join(' ')}`.trimEnd();
}

/** `+998901234567`, or null when the input is not a complete Uzbek number. */
export function toE164(input: string): string | null {
  const national = nationalDigits(input);
  return national.length === 9 ? `+998${national}` : null;
}

export function isCompleteUzPhone(input: string): boolean {
  return toE164(input) !== null;
}

/**
 * The nine national digits, with the country code stripped however it arrived.
 *
 * A customer may type `+998 90 …`, `998 90 …`, `8 90 …` or just `90 …`, and all
 * four mean the same number. Only the leading `998` is removed, and only when
 * what remains is a plausible national number — otherwise a nine-digit number
 * that happens to begin `998` would lose its own first three digits.
 */
function nationalDigits(input: string): string {
  // An explicit `+998` is a country code and never national digits, and stripping
  // it here is what makes formatting idempotent over its own output.
  //
  // Without this the field ate the number it was displaying. `formatUzPhone`
  // returns `+998 …`, the input shows that, and the next keystroke re-parses a
  // string that now begins with 998 — which the length test below only treats as
  // a country code once there are more than nine digits, so early on it was kept
  // as national digits and `+998` was prefixed again. Typing 901234567 one key at
  // a time produced `+998 99 899 89 01`, and the sign-in that followed was for a
  // number nobody has.
  const withoutCountryCode = input.trimStart().startsWith('+998')
    ? input.trimStart().slice('+998'.length)
    : input;

  const digits = (withoutCountryCode.match(DIGITS) ?? []).join('');
  if (digits.startsWith('998') && digits.length > 9) {
    return digits.slice(3, 12);
  }
  return digits.slice(0, 9);
}
