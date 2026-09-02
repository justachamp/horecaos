/**
 * Turns a wire ISO-8601 instant into a `Date`, for `I18nService.day`/`dateTime`.
 *
 * A template cannot write `new Date(...)` directly, and every screen that
 * renders a server timestamp needs exactly this, so it lives here once
 * rather than as a private method repeated in each component.
 */
export function asDate(iso: string): Date {
  return new Date(iso);
}
