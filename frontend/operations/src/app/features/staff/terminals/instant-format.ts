/**
 * `DD.MM.YYYY HH:mm` in UTC, as the kitchen's device registry shows it: an administrative table, not an order timeline.
 * The terminals' pages and the PIN panels share it so a time reads the same on the registry, the person card and «Мой
 * профиль».
 */
export function formatInstantUtc(value: string): string {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return value;
  }
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${pad(parsed.getUTCDate())}.${pad(parsed.getUTCMonth() + 1)}.${parsed.getUTCFullYear()} ` +
    `${pad(parsed.getUTCHours())}:${pad(parsed.getUTCMinutes())}`
  );
}

/** `HH:mm` in UTC, for «valid until». */
export function formatTimeUtc(value: string): string {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return value;
  }
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(parsed.getUTCHours())}:${pad(parsed.getUTCMinutes())}`;
}

/** An eight-digit activation code, grouped for reading aloud: `1234 5678`. */
export function groupCode(code: string): string {
  return code.replace(/(\d{4})(?=\d)/g, '$1 ');
}
