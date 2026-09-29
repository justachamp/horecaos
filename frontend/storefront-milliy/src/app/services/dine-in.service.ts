import { Injectable, computed, inject, signal } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { HorecaOSApiError } from '../core/api/problem-details';

/** `QrEntryController.AdmissionResponse`, transcribed -- guest token included. */
interface AdmissionResponse {
  readonly guestToken: string;
  readonly expiresAt: string;
  /**
   * `VIEW_ONLY` (menu only, no cart) | `ORDER_AND_PAY` (a cart bound to the
   * table's open session) | `SETTLE_OPEN_TICKET` (declared, never selectable
   * today -- see `QrMode`'s own doc; this client never receives it).
   */
  readonly mode: 'VIEW_ONLY' | 'ORDER_AND_PAY' | (string & {});
  readonly tenantId: string;
  readonly brandId: string;
  readonly locationId: string;
  readonly tableCode: string;
  /**
   * The table's own live session, if a host has already seated it. Null in
   * `VIEW_ONLY` (HorecaOS creates nothing there) and in `ORDER_AND_PAY` at a
   * table nobody has seated yet -- see `DineInTableComponent`'s own doc on why
   * this client does not open one itself.
   */
  readonly openSessionId: string | null;
  /**
   * The tenant's own `QR_TABLE` channel code -- null when the tenant has
   * registered zero or more than one, in which case the menu is read under this
   * build's own channel.
   */
  readonly channelCode: string | null;
}

/**
 * What a screen may read of an admission: everything but the guest token.
 *
 * The token (`X-Dine-In-Token`) is this service's own. A component or template
 * that could read it could render it, log it or build a URL from it; nothing
 * has a reason to, because every call it authorises is a method of this class.
 */
export type DineInAdmission = Omit<AdmissionResponse, 'guestToken'>;

/** `QrEntryController.GuestBillResponse`, transcribed. */
export interface DineInBill {
  readonly sessionId: string;
  readonly status: string;
  readonly currency: string;
  readonly totalMinor: number;
  readonly roundCount: number;
  readonly orderIds: readonly string[];
}

/**
 * A placed order that has not yet been confirmed onto its table's bill.
 *
 * Only ids and a clock reading -- no name, phone, address or token -- so it is
 * safe to keep in `localStorage` (ADR 0029).
 */
export interface PendingRound {
  readonly sessionId: string;
  readonly orderId: string;
  /** Epoch milliseconds when checkout succeeded on this device. */
  readonly queuedAt: number;
}

/** What one pass over a session's pending rounds achieved. */
export interface RoundFlush {
  /** The bill as of the last round that landed, or null if none did. */
  readonly bill: DineInBill | null;
  /** Rounds still queued: the platform could not be reached, or the guest must sign in again. */
  readonly pending: number;
  /** Rounds the platform refused for good in this pass -- they are dropped, not retried. */
  readonly abandoned: number;
}

/** What is persisted for a visit: the admission, plus the token only when it authorises something. */
type StoredVisit = DineInAdmission & { readonly guestToken?: string };

const GUEST_TOKEN_HEADER = 'X-Dine-In-Token';
const STORAGE_KEY = 'horecaos_dinein_admission';
const PENDING_ROUNDS_KEY = 'horecaos_dinein_pending_rounds';
/** A table's evening is over long before this; an older entry is stale, not pending. */
const PENDING_ROUND_TTL_MS = 24 * 60 * 60 * 1000;
const PENDING_ROUND_LIMIT = 20;

/**
 * The guest side of ADR 0047's QR dine-in flow: exchanging a scanned table code
 * for a short-lived guest token, and every call that token authorises
 * afterwards.
 *
 * Ported from `frontend/storefront`'s service of the same name -- same
 * endpoints, same storage keys, same admission shape, same pending-round queue
 * -- and it keeps that service's isolation of the table's credentials exactly.
 *
 * <h2>Three credentials, kept apart</h2>
 *
 * **The printed table token** is the value in the scan URL. It is a permanent
 * bearer credential printed on a card in a public room, so it is spent by one
 * request (`DineInScanComponent`), never stored, and never handed to anything
 * that outlives the scan -- not this service's storage, not the sign-in
 * destination (`ReturnDestination` refuses it by construction).
 *
 * **The guest token** is what the exchange returns: "this device scanned table
 * 7", short-lived and table-scoped. It travels only as `X-Dine-In-Token` on the
 * calls in this class -- never in a URL, a query or a body, never as a bearer --
 * and is not exposed on {@link DineInAdmission}. It is held only for an
 * `ORDER_AND_PAY` table, the one mode whose bill, bill-request and round calls
 * it authorises; a `VIEW_ONLY` table has nothing to spend it on (the platform
 * refuses every one of these calls to it), so an app that kept it would be
 * holding a credential in `localStorage`, readable by any script on the page,
 * for no benefit.
 *
 * **The customer's session** ({@link Session}) is a different identity again:
 * "this device is Aziz", minted by phone and SMS code, and what a cart and a
 * checkout need -- there is no anonymous cart. A guest orders by holding both.
 * {@link bill} and {@link requestBill} are the table's own and go out
 * `anonymous`; {@link attachRound} is not, because the platform checks the
 * order against the customer who placed it as well.
 *
 * <h2>Where the visit lives</h2>
 *
 * `localStorage`, one entry, replaced on every scan: there is exactly one table
 * visit worth remembering at a time. A visit stored by an earlier build of this
 * app, which dropped the token, is not resumed for an `ORDER_AND_PAY` table --
 * it could show ordering controls it cannot honour -- so that guest scans
 * again.
 */
@Injectable({ providedIn: 'root' })
export class DineInService {
  private readonly api = inject(ApiClient);

  private guestToken: string | null = null;
  private readonly admissionSignal = signal<DineInAdmission | null>(null);
  private readonly pendingRoundsSignal = signal<readonly PendingRound[]>(this.restorePendingRounds());
  private readonly flushesInFlight = new Map<string, Promise<RoundFlush>>();

  constructor() {
    const visit = this.restore();
    if (visit) {
      const { guestToken, ...admission } = visit;
      this.guestToken = guestToken ?? null;
      this.admissionSignal.set(admission);
    }
  }

  /**
   * The current table's admission, or null once it has expired or nothing has
   * been scanned this visit. A plain read of the clock on every call, like
   * `Session.accessToken` -- a `computed()` would not notice a deadline passing
   * on its own.
   */
  admission(): DineInAdmission | null {
    const value = this.admissionSignal();
    if (!value) {
      return null;
    }
    if (Date.parse(value.expiresAt) > Date.now()) {
      return value;
    }
    this.clear();
    return null;
  }

  /** Reactive: `true` once a live admission exists. For a guard or a template. */
  readonly hasAdmission = computed(() => {
    const value = this.admissionSignal();
    return !!value && Date.parse(value.expiresAt) > Date.now();
  });

  /**
   * Exchanges a printed table token for a guest admission.
   *
   * Every failure (unknown, rotated, archived, a branch that takes no QR
   * orders) answers identically at the platform, by design -- see
   * `QrEntryController.exchange` -- so a caller can only say "this code is not
   * in service". A refused scan leaves the previous visit, if any, untouched.
   */
  async exchange(tableToken: string): Promise<DineInAdmission> {
    const { guestToken, ...admission } = await this.api.mutate<AdmissionResponse>(
      'POST',
      '/storefront/dine-in/qr/token-exchanges',
      { body: { tableToken }, anonymous: true },
    );
    this.guestToken = admission.mode === 'ORDER_AND_PAY' && guestToken ? guestToken : null;
    this.admissionSignal.set(admission);
    this.persist(admission, this.guestToken);
    return admission;
  }

  /**
   * The running bill at the guest's own table. Refused (404) for a `VIEW_ONLY`
   * code or a session that is not this table's own live one.
   */
  async bill(sessionId: string): Promise<DineInBill> {
    return this.api.get<DineInBill>(this.sessionPath(sessionId), {
      anonymous: true,
      headers: this.tokenHeader(),
    });
  }

  /**
   * Asks for the bill -- moves the session to `BILL_REQUESTED`. Idempotent on
   * the platform side: asking twice reads as one request, not an error.
   */
  async requestBill(sessionId: string): Promise<DineInBill> {
    return this.api.mutate<DineInBill>('POST', `${this.sessionPath(sessionId)}/bill-requests`, {
      anonymous: true,
      headers: this.tokenHeader(),
    });
  }

  /**
   * Binds the guest's basket to their own table (`PUT .../carts/{id}/table`).
   *
   * The cart service is handed the guest token as a header and nothing else, so
   * the token stays here: a screen that wants a bound cart calls this with its
   * cart service and never holds the token itself. A bound cart is put on the
   * table's bill by checkout, in the transaction that creates the order, and is
   * refused before anything is written when nobody is seated -- which is what a
   * later {@link attachRound} cannot promise. Refuses, like every call in this
   * class, when no table has been scanned.
   */
  async bindCartToTable<T>(carts: {
    bindTable(headers: Readonly<Record<string, string>>): Promise<T>;
  }): Promise<T> {
    return carts.bindTable(this.tokenHeader());
  }

  /**
   * Attaches a just-checked-out order to the guest's own table's bill.
   *
   * The safety net under {@link bindCartToTable}: a basket that was bound is put
   * on the bill by checkout itself and this only answers with the bill; a basket
   * that was never bound (one opened before the table screen bound baskets, or a
   * bind that could not be made) reaches its table's running total only through
   * this call. Safe to call again with the same order id if a response was lost --
   * the platform answers the second call with the bill unchanged rather than a
   * conflict.
   *
   * Deliberately *not* `anonymous`, unlike {@link bill} and {@link
   * requestBill}. The guest token proves this device is at this table; it
   * proves nothing about which order the caller is allowed to attach, so the
   * platform also checks the order against the caller's own signed-in session
   * -- the same one checkout just placed it under (see `QrEntryController.
   * addRound`). Passing `anonymous: true` here would send the guest token with
   * no way to prove the order is the caller's, which is exactly the gap that
   * let one table attach a neighbouring table's order to its bill.
   */
  async attachRound(sessionId: string, orderId: string): Promise<DineInBill> {
    return this.api.mutate<DineInBill>('POST', `${this.sessionPath(sessionId)}/rounds`, {
      body: { orderId },
      headers: this.tokenHeader(),
    });
  }

  /**
   * Remembers, on this device, that `orderId` was placed at the table of
   * `sessionId` and still has to be attached to its bill.
   *
   * Called *before* the attach request, straight after checkout succeeded. A
   * guest order reaches `dinein.session_orders` -- the row the kitchen ticket's
   * table chip, the order board and the running bill all read -- in checkout
   * itself when its cart was bound to the table ({@link bindCartToTable}), and
   * otherwise only through {@link attachRound}. This is the net under the second
   * case: the order id is then the one thing that ties the order to its table, so
   * it is held here, where a lost response, a dropped connection or a reload
   * cannot discard it, until the platform has confirmed the attach.
   */
  queueRound(sessionId: string, orderId: string): void {
    const kept = this.pendingRoundsSignal().filter(
      (round) => !(round.sessionId === sessionId && round.orderId === orderId),
    );
    this.setPendingRounds([...kept, { sessionId, orderId, queuedAt: Date.now() }].slice(-PENDING_ROUND_LIMIT));
  }

  /**
   * How many placed orders at `sessionId` are still waiting to be confirmed
   * onto the bill. Reactive: reads the signal, so a `computed()` follows it.
   */
  pendingRoundCount(sessionId: string): number {
    const now = Date.now();
    return this.pendingRoundsSignal().filter(
      (round) => round.sessionId === sessionId && now - round.queuedAt < PENDING_ROUND_TTL_MS,
    ).length;
  }

  /**
   * Tries to attach every queued round of `sessionId`, oldest first.
   *
   * A round leaves the queue when the platform confirms it (the call is
   * idempotent -- a round already on the bill answers with the bill) or refuses
   * it for good (a 4xx that a second attempt cannot change: the session closed,
   * the order is not this table's). Anything that might succeed later keeps it
   * queued: no connection, a 5xx, a rate limit, or a 401 -- the guest token
   * ended or the customer session must be renewed, and both are things the
   * guest can fix. The pass stops at the first such failure rather than
   * hammering an unreachable platform once per round.
   *
   * A call that arrives while a pass is running waits for it and then makes its
   * own, so a round queued after that pass began is not left behind.
   */
  flushPendingRounds(sessionId: string): Promise<RoundFlush> {
    const running = this.flushesInFlight.get(sessionId);
    if (running) {
      return running.then(() => this.flushPendingRounds(sessionId));
    }
    const pass = this.attachQueuedRounds(sessionId).finally(() => this.flushesInFlight.delete(sessionId));
    this.flushesInFlight.set(sessionId, pass);
    return pass;
  }

  private async attachQueuedRounds(sessionId: string): Promise<RoundFlush> {
    const now = Date.now();
    let bill: DineInBill | null = null;
    let abandoned = 0;
    for (const round of this.pendingRoundsSignal()) {
      if (round.sessionId !== sessionId) {
        continue;
      }
      if (now - round.queuedAt >= PENDING_ROUND_TTL_MS) {
        this.dropPendingRound(round);
        continue;
      }
      try {
        bill = await this.attachRound(round.sessionId, round.orderId);
        this.dropPendingRound(round);
      } catch (failure) {
        if (isFinalRefusal(failure)) {
          this.dropPendingRound(round);
          abandoned++;
          continue;
        }
        break;
      }
    }
    return { bill, pending: this.pendingRoundCount(sessionId), abandoned };
  }

  private dropPendingRound(round: PendingRound): void {
    this.setPendingRounds(
      this.pendingRoundsSignal().filter(
        (other) => !(other.sessionId === round.sessionId && other.orderId === round.orderId),
      ),
    );
  }

  private setPendingRounds(rounds: readonly PendingRound[]): void {
    this.pendingRoundsSignal.set(rounds);
    safely(() =>
      rounds.length === 0
        ? localStorage.removeItem(PENDING_ROUNDS_KEY)
        : localStorage.setItem(PENDING_ROUNDS_KEY, JSON.stringify(rounds)),
    );
  }

  private restorePendingRounds(): readonly PendingRound[] {
    const raw = safely(() => localStorage.getItem(PENDING_ROUNDS_KEY));
    if (!raw) {
      return [];
    }
    try {
      const parsed = JSON.parse(raw) as unknown;
      if (!Array.isArray(parsed)) {
        return [];
      }
      const now = Date.now();
      return parsed
        .filter(
          (entry): entry is PendingRound =>
            !!entry &&
            typeof entry.sessionId === 'string' &&
            typeof entry.orderId === 'string' &&
            typeof entry.queuedAt === 'number' &&
            now - entry.queuedAt < PENDING_ROUND_TTL_MS,
        )
        .slice(-PENDING_ROUND_LIMIT);
    } catch {
      return [];
    }
  }

  /**
   * True for the one refusal every dine-in endpoint gives a token the platform
   * no longer recognises (expired, or the table's code was rotated) -- the
   * caller's cue to send the guest back to scanning.
   */
  isGuestSessionEnded(failure: unknown): boolean {
    return failure instanceof HorecaOSApiError && failure.code === 'UNAUTHENTICATED';
  }

  /**
   * Forgets the current table visit and its token -- the session closed, or the
   * guest token was refused as no longer live. Rounds still queued are kept: a
   * re-scan can still attach them.
   */
  clear(): void {
    this.guestToken = null;
    this.admissionSignal.set(null);
    safely(() => localStorage.removeItem(STORAGE_KEY));
  }

  private sessionPath(sessionId: string): string {
    return `/storefront/dine-in/sessions/${encodeURIComponent(sessionId)}`;
  }

  /** The one place the guest token becomes a header; refuses when there is none to send. */
  private tokenHeader(): Record<string, string> {
    const token = this.admission() ? this.guestToken : null;
    if (!token) {
      throw new Error('No table has been scanned this visit.');
    }
    return { [GUEST_TOKEN_HEADER]: token };
  }

  private persist(admission: DineInAdmission, guestToken: string | null): void {
    const stored: StoredVisit = guestToken ? { ...admission, guestToken } : admission;
    safely(() => localStorage.setItem(STORAGE_KEY, JSON.stringify(stored)));
  }

  private restore(): StoredVisit | null {
    const raw = safely(() => localStorage.getItem(STORAGE_KEY));
    if (!raw) {
      return null;
    }
    try {
      const parsed = JSON.parse(raw) as StoredVisit;
      if (!(Date.parse(parsed.expiresAt) > Date.now())) {
        return null;
      }
      if (parsed.mode !== 'ORDER_AND_PAY') {
        // Whatever a stored menu-only visit carries, it has no use for a token.
        const { guestToken: _unused, ...admission } = parsed;
        return admission;
      }
      return typeof parsed.guestToken === 'string' && parsed.guestToken ? parsed : null;
    } catch {
      return null;
    }
  }
}

/**
 * True when the platform answered and a second attempt cannot change its
 * answer: a 4xx other than the three that are about the moment rather than the
 * request -- 401 (a token to renew, see {@link DineInService.flushPendingRounds}),
 * 408 and 429.
 */
function isFinalRefusal(failure: unknown): boolean {
  return (
    failure instanceof HorecaOSApiError &&
    failure.status >= 400 &&
    failure.status < 500 &&
    failure.status !== 401 &&
    failure.status !== 408 &&
    failure.status !== 429
  );
}

/**
 * `localStorage` throws rather than returning null in a WebView with site data
 * disabled, and in a private window in some browsers. A scanned table must
 * still render.
 */
function safely<T>(read: () => T): T | null {
  try {
    return read();
  } catch {
    return null;
  }
}
