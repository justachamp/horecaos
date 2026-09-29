import { Injectable } from '@angular/core';

const STORAGE_KEY = 'horecaos_sign_in_return_to';

/**
 * How long a remembered destination stays honoured. Sign-in is a phone number,
 * an SMS and six digits -- minutes, not hours. A tab left open on the login
 * screen overnight and signed into the next morning should land on the ordinary
 * home screen, not on a table the guest walked away from.
 */
const MAX_AGE_MS = 30 * 60 * 1000;

/**
 * The only routes a sign-in may hand a customer back to, matched exactly.
 *
 * An allow-list rather than a shape check (`startsWith('/')`, no `//`): this is
 * the one value in the app that decides where the browser goes after a
 * credential is accepted, and a rule that has to be argued correct against every
 * string is worse than a list that has to be extended on purpose. Two things are
 * deliberately not on it:
 *
 *  - `/dine-in/:tableToken`, the scan route. Its path *is* the table's printed
 *    bearer credential (ADR 0047), and nothing that outlives the scan may hold
 *    it -- `DineInScanComponent` spends it once and replaces the URL.
 *  - anything with a query or a fragment, which is where a caller would try to
 *    smuggle a second destination through.
 *
 * `/dine-in/table` is the token-free screen the scan lands on: it resumes from
 * the guest token `DineInService` already persisted, so a guest who signs in
 * from it needs nothing but the path to come back to their table.
 */
const RETURNABLE_PATHS: ReadonlySet<string> = new Set(['/dine-in/table']);

interface Remembered {
  readonly path: string;
  readonly at: number;
}

/**
 * Where to send a customer once they have signed in, when they did not start at
 * the front door.
 *
 * A guest who scanned a table's QR code and taps «sign in to order» has to come
 * back to the table, not to `/locations` (the destination every other sign-in
 * gets). The path travels in `sessionStorage`, not in the router state the login
 * and code screens pass to each other, because `hardReloadTelegramEntryPage`
 * reloads the login screen inside Telegram's WebView and a reload discards
 * `history.state` but not `sessionStorage`. Not `localStorage`: a destination is
 * one tab's intent, and must not follow the customer to another tab or outlive
 * the browser session.
 *
 * Never holds a table token. The token in a scan URL is the one value that
 * must not reach the auth flow, its logs or a `Referer`; the table is remembered
 * by the guest token `DineInService` keeps, and this class stores a path.
 */
@Injectable({ providedIn: 'root' })
export class ReturnDestination {
  /**
   * Remembers where to send the customer after sign-in. A path outside the
   * allow-list is ignored, so a caller cannot make the login screen an open
   * redirect however it obtained the string.
   */
  remember(path: string): void {
    if (!RETURNABLE_PATHS.has(path)) {
      return;
    }
    const entry: Remembered = { path, at: Date.now() };
    safely(() => sessionStorage.setItem(STORAGE_KEY, JSON.stringify(entry)));
  }

  /**
   * The remembered destination, cleared as it is read -- a destination is spent
   * by the sign-in that used it. Null when none was remembered, it has aged out,
   * or the stored value is not on the allow-list (a tampered entry).
   */
  consume(): string | null {
    const raw = safely(() => sessionStorage.getItem(STORAGE_KEY));
    this.clear();
    if (!raw) {
      return null;
    }
    try {
      const parsed = JSON.parse(raw) as Partial<Remembered>;
      if (
        typeof parsed.path !== 'string' ||
        typeof parsed.at !== 'number' ||
        !RETURNABLE_PATHS.has(parsed.path) ||
        Date.now() - parsed.at > MAX_AGE_MS
      ) {
        return null;
      }
      return parsed.path;
    } catch {
      return null;
    }
  }

  /** Forgets any remembered destination. */
  clear(): void {
    safely(() => sessionStorage.removeItem(STORAGE_KEY));
  }
}

/**
 * `sessionStorage` throws rather than returning null in a WebView with site data
 * disabled and in some private windows -- see `Session`'s own copy of this
 * helper. Remembering where to return to is a courtesy; sign-in must still work
 * without it.
 */
function safely<T>(read: () => T): T | null {
  try {
    return read();
  } catch {
    return null;
  }
}
