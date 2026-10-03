import { Injectable, computed, inject, signal } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { HorecaOSApiError } from '../core/api/problem-details';

/** `QrEntryController.AdmissionResponse`, transcribed. */
export interface DineInAdmission {
  readonly guestToken: string;
  readonly expiresAt: string;
  /** `VIEW_ONLY` (menu only, no cart) | `ORDER_AND_PAY` (a cart bound to the
   * table) | `SETTLE_OPEN_TICKET` (declared, never selectable today -- see
   * `QrMode`'s own doc; this client never receives it). */
  readonly mode: 'VIEW_ONLY' | 'ORDER_AND_PAY' | (string & {});
  readonly tenantId: string;
  readonly brandId: string;
  readonly locationId: string;
  readonly tableCode: string;
  /** The table's own live session, if a host has already seated it. Null in
   * `VIEW_ONLY` (HorecaOS creates nothing there) and in `ORDER_AND_PAY` at a
   * table nobody has seated yet -- see `DineInTableComponent`'s own doc on
   * why this client does not open one itself. */
  readonly openSessionId: string | null;
  /** The tenant's own `QR_TABLE` channel code -- null when the tenant has
   * registered zero or more than one, in which case this client falls back
   * to whatever channel it would otherwise browse under. */
  readonly channelCode: string | null;
  /**
   * Whether this guest could seat themselves right now (ADR 0143): the branch has turned
   * self-seating on, the table is free and no confirmed booking holds it soon. A hint for
   * the screen -- the platform decides again when the guest asks. Absent on an admission
   * stored by an earlier build, which reads as false.
   */
  readonly walkInAvailable?: boolean;
}

/** `QrEntryController.GuestBillResponse`, transcribed. */
export interface DineInBill {
  readonly sessionId: string;
  readonly status: string;
  readonly currency: string;
  readonly totalMinor: number;
  readonly roundCount: number;
  readonly orderIds: readonly string[];
  /** `STAFF` or `GUEST_QR` (ADR 0143). Absent from a platform that predates it, which reads as staff. */
  readonly origin?: string;
  /**
   * When an unconfirmed claim gives the table back to the room; null once an order the
   * restaurant accepted is on it (or a member of staff took charge), and for a session a
   * host opened.
   */
  readonly claimExpiresAt?: string | null;
  /** Whether this is an ordinary session. False only for a guest's claim nothing has confirmed yet. */
  readonly confirmed?: boolean;
}

/** `QrEntryController.GuestSeatingResponse`: the bill plus whether this very call opened the session. */
export interface DineInSeating extends DineInBill {
  /** False when somebody already sat here and this is their session, handed back rather than a second one. */
  readonly created: boolean;
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

const GUEST_TOKEN_HEADER = 'X-Dine-In-Token';
const STORAGE_KEY = 'horecaos_dinein_admission';
const PENDING_ROUNDS_KEY = 'horecaos_dinein_pending_rounds';
/** A table's evening is over long before this; an older entry is stale, not pending. */
const PENDING_ROUND_TTL_MS = 24 * 60 * 60 * 1000;
const PENDING_ROUND_LIMIT = 20;

/**
 * The guest side of ADR 0047's QR dine-in flow: exchanging a scanned table
 * code for a short-lived guest token, and every call that token authorises
 * afterwards.
 *
 * <h2>A second, separate identity from {@link Session}</h2>
 *
 * The storefront's own {@link Session} is the signed-in customer's bearer,
 * minted by phone + OTP, and it is what a cart and a checkout still need --
 * dine-in does not replace it, because `POST /carts` has no anonymous path
 * (see `app.routes.ts`'s own doc on the cart route). The token this class
 * holds is a *different*, table-scoped credential: it proves "this device
 * scanned table 7", not "this device is Aziz", and the platform's dine-in
 * endpoints (`QrEntryController`) take it as `X-Dine-In-Token` instead of
 * `Authorization: Bearer`, resolved to a row with no capability check at all
 * -- see that controller's own doc for why. A guest orders by holding
 * *both*: this token to reach the table's bill, and an ordinary signed-in
 * session to open a cart and check out.
 *
 * <h2>Where the admission lives</h2>
 *
 * `localStorage`, one entry, replaced on every scan. A photographed code is
 * the guest's own device scanning its own table, so there is exactly one
 * table visit worth remembering at a time -- scanning a second table's code
 * (a different visit, or a mistaken scan) simply replaces it, the same way
 * `QrEntryService.exchange` mints a fresh token every time regardless of
 * what came before.
 */
@Injectable({ providedIn: 'root' })
export class DineInService {
  private readonly api = inject(ApiClient);

  private readonly admissionSignal = signal<DineInAdmission | null>(this.restore());
  private readonly pendingRoundsSignal = signal<readonly PendingRound[]>(
    this.restorePendingRounds(),
  );
  private readonly flushesInFlight = new Map<string, Promise<RoundFlush>>();

  /** The current table's admission, or null once it has expired or nothing
   * has been scanned this visit. A plain read of the clock on every call,
   * like `Session.accessToken` -- see that method's own doc on why a
   * `computed()` would not notice a deadline passing on its own. */
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
   * Exchanges a printed table token for a guest token.
   *
   * Every failure here (unknown, rotated, archived, a branch that takes no
   * QR orders) answers identically at the platform, by design -- see
   * `QrEntryController.exchange`'s own doc -- so this surfaces only that
   * "this code is not in service" rather than trying to distinguish them.
   */
  async exchange(tableToken: string): Promise<DineInAdmission> {
    const response = await this.api.mutate<DineInAdmission>(
      'POST',
      '/storefront/dine-in/qr/token-exchanges',
      { body: { tableToken }, anonymous: true },
    );
    this.admissionSignal.set(response);
    this.persist(response);
    return response;
  }

  /**
   * Seats the guest at the table they scanned (ADR 0143): opens a provisional session --
   * a claim -- without waiting for staff. An explicit act, never a side effect of
   * scanning, and it needs both credentials a round does: the table's guest token and the
   * signed-in customer session, so this call is deliberately not `anonymous`.
   *
   * The platform decides again under its locks (a booking may have taken the table since
   * the scan, a cap may be reached) and answers every "no" with one `TABLE_NOT_AVAILABLE`
   * conflict. When somebody already sits here it answers with their session and
   * `created: false`, not a second one.
   *
   * On success the stored admission follows: it now has an open session, so a reload
   * lands on the seated table rather than the invitation to sit.
   */
  async seat(partySize: number): Promise<DineInSeating> {
    const seating = await this.api.mutate<DineInSeating>('POST', '/storefront/dine-in/sessions', {
      body: { partySize },
      headers: this.tokenHeader(),
    });
    this.updateAdmission({ openSessionId: seating.sessionId, walkInAvailable: false });
    return seating;
  }

  /**
   * The platform said this table cannot be taken from here. Stop offering it: the guest is
   * told to ask a member of staff instead of being invited to try again.
   */
  markWalkInUnavailable(): void {
    this.updateAdmission({ walkInAvailable: false });
  }

  /**
   * The session this device was seated at is no longer the table's live one, yet the guest
   * token is still accepted. The table may be free again, so the invitation to sit is
   * offered once more; the platform re-decides when the guest asks.
   *
   * Not how a lapsed claim ends: closing a session revokes every guest token minted at its
   * table, so a claimant whose hold ran out gets a dead token (401) and the visit is
   * cleared with the reason said (see `DineInTableComponent.endVisit`). A token cannot be
   * renewed -- the printed code that mints one is not kept.
   */
  sessionEnded(): void {
    this.updateAdmission({ openSessionId: null, walkInAvailable: true });
  }

  private updateAdmission(change: Partial<DineInAdmission>): void {
    const current = this.admissionSignal();
    if (!current) {
      return;
    }
    const next = { ...current, ...change };
    this.admissionSignal.set(next);
    this.persist(next);
  }

  /** The running bill at the guest's own table. Refused (404) for a
   * `VIEW_ONLY` code or a session that is not this table's own live one. */
  async bill(sessionId: string): Promise<DineInBill> {
    return this.api.get<DineInBill>(`/storefront/dine-in/sessions/${sessionId}`, {
      anonymous: true,
      headers: this.tokenHeader(),
    });
  }

  /** Asks for the bill -- moves the session to `BILL_REQUESTED`. Idempotent
   * on the platform side: asking twice reads as one request, not an error. */
  async requestBill(sessionId: string): Promise<DineInBill> {
    return this.api.mutate<DineInBill>(
      'POST',
      `/storefront/dine-in/sessions/${sessionId}/bill-requests`,
      { anonymous: true, headers: this.tokenHeader() },
    );
  }

  /**
   * Attaches a just-checked-out order to the guest's own table's bill.
   *
   * Closes the gap `QrEntryController.addRound`'s own doc names: checkout
   * does not bind a cart to a table on the platform today, so this is the
   * call that makes an `ORDER_AND_PAY` checkout actually show up on the
   * table's running total. Safe to call again with the same order id if a
   * response was lost -- the platform answers the second call with the bill
   * unchanged rather than a conflict.
   *
   * Deliberately *not* `anonymous`, unlike every other call in this class.
   * The guest token proves this device is at this table; it proves nothing
   * about which order the caller is allowed to attach, so the platform also
   * checks the order against the caller's own signed-in session -- the same
   * one `checkout()` just placed it under, still held by `Session` at this
   * point in the flow (see `QrEntryController.addRound`'s own doc). Passing
   * `anonymous: true` here would send the guest token with no way to prove
   * the order is the caller's, which is exactly the gap that let a table
   * attach a neighbouring table's bill.
   */
  async attachRound(sessionId: string, orderId: string): Promise<DineInBill> {
    return this.api.mutate<DineInBill>('POST', `/storefront/dine-in/sessions/${sessionId}/rounds`, {
      body: { orderId },
      headers: this.tokenHeader(),
    });
  }

  /**
   * Remembers, on this device, that `orderId` was placed at the table of
   * `sessionId` and still has to be attached to its bill.
   *
   * Called *before* the attach request, straight after checkout succeeded.
   * A guest order reaches `dinein.session_orders` -- the row the kitchen
   * ticket's table chip, the order board and the running bill all read --
   * only through {@link attachRound}, and checkout gives that order no table
   * of its own. So the order id is the one thing that ties this order to its
   * table: it is held here, where a lost response, a dropped connection or a
   * reload cannot discard it, until the platform has confirmed the attach.
   */
  queueRound(sessionId: string, orderId: string): void {
    const kept = this.pendingRoundsSignal().filter(
      (round) => !(round.sessionId === sessionId && round.orderId === orderId),
    );
    this.setPendingRounds(
      [...kept, { sessionId, orderId, queuedAt: Date.now() }].slice(-PENDING_ROUND_LIMIT),
    );
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
   * idempotent -- a round already on the bill answers with the bill) or
   * refuses it for good (a 4xx that a second attempt cannot change: the
   * session closed, the order is not this table's). Anything that might
   * succeed later keeps it queued: no connection, a 5xx, a rate limit, or a
   * 401 -- the guest token ended or the customer session must be renewed,
   * and both are things the guest can fix. The pass stops at the first such
   * failure rather than hammering an unreachable platform once per round.
   *
   * A call that arrives while a pass is running waits for it and then makes
   * its own, so a round queued after that pass began is not left behind.
   */
  flushPendingRounds(sessionId: string): Promise<RoundFlush> {
    const running = this.flushesInFlight.get(sessionId);
    if (running) {
      return running.then(() => this.flushPendingRounds(sessionId));
    }
    const pass = this.attachQueuedRounds(sessionId).finally(() =>
      this.flushesInFlight.delete(sessionId),
    );
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

  /** True for the one refusal every dine-in endpoint gives a token the
   * platform no longer recognises (expired, or the table's code was
   * rotated) -- the caller's cue to send the guest back to scanning. */
  isGuestSessionEnded(failure: unknown): boolean {
    return failure instanceof HorecaOSApiError && failure.code === 'UNAUTHENTICATED';
  }

  /** Forgets the current table visit -- the session closed, or the guest
   * token was refused as no longer live. */
  clear(): void {
    this.admissionSignal.set(null);
    safely(() => localStorage.removeItem(STORAGE_KEY));
  }

  private tokenHeader(): Record<string, string> {
    const token = this.admission()?.guestToken;
    if (!token) {
      throw new Error('No table has been scanned this visit.');
    }
    return { [GUEST_TOKEN_HEADER]: token };
  }

  private persist(admission: DineInAdmission): void {
    safely(() => localStorage.setItem(STORAGE_KEY, JSON.stringify(admission)));
  }

  private restore(): DineInAdmission | null {
    const raw = safely(() => localStorage.getItem(STORAGE_KEY));
    if (!raw) {
      return null;
    }
    try {
      const parsed = JSON.parse(raw) as DineInAdmission;
      return Date.parse(parsed.expiresAt) > Date.now() ? parsed : null;
    } catch {
      return null;
    }
  }
}

/**
 * True when the platform answered and a second attempt cannot change its
 * answer: a 4xx other than the three that are about the moment rather than
 * the request -- 401 (a token to renew, see {@link DineInService.flushPendingRounds}),
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
 * `localStorage` throws rather than returning null in a WebView with site
 * data disabled, and in a private window in some browsers -- see
 * `Session`'s own copy of this helper. A scanned table must still render.
 */
function safely<T>(read: () => T): T | null {
  try {
    return read();
  } catch {
    return null;
  }
}
