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
}

/** `QrEntryController.GuestBillResponse`, transcribed. */
export interface DineInBill {
  readonly sessionId: string;
  readonly status: string;
  readonly currency: string;
  readonly totalMinor: number;
  readonly roundCount: number;
  readonly orderIds: readonly string[];
}

const GUEST_TOKEN_HEADER = 'X-Dine-In-Token';
const STORAGE_KEY = 'horecaos_dinein_admission';

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
   */
  async attachRound(sessionId: string, orderId: string): Promise<DineInBill> {
    return this.api.mutate<DineInBill>(
      'POST',
      `/storefront/dine-in/sessions/${sessionId}/rounds`,
      { body: { orderId }, anonymous: true, headers: this.tokenHeader() },
    );
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
