import { Injectable, computed, inject, signal } from '@angular/core';

import { ApiClient } from '../core/api/api-client';

/** `QrEntryController.AdmissionResponse`, transcribed -- guest token included. */
interface AdmissionResponse {
  readonly guestToken: string;
  readonly expiresAt: string;
  /**
   * `VIEW_ONLY` (menu only, no cart) | `ORDER_AND_PAY` (a cart bound to the
   * table) | `SETTLE_OPEN_TICKET` (declared, never selectable today -- see
   * `QrMode`'s own doc). This app renders the menu for every mode and orders
   * for none of them: see {@link DineInTableComponent}.
   */
  readonly mode: 'VIEW_ONLY' | 'ORDER_AND_PAY' | (string & {});
  readonly tenantId: string;
  readonly brandId: string;
  readonly locationId: string;
  readonly tableCode: string;
  /** The table's own live session, if a host has already seated it. */
  readonly openSessionId: string | null;
  /**
   * The tenant's own `QR_TABLE` channel code -- null when the tenant has
   * registered zero or more than one, in which case the menu is read under this
   * build's own channel.
   */
  readonly channelCode: string | null;
}

/**
 * What this app keeps of an admission: everything but the guest token.
 *
 * The token (`X-Dine-In-Token`) authorises the table's bill, rounds and
 * bill-request calls, and nothing in this app makes those calls yet. Holding a
 * credential nothing uses -- in `localStorage`, readable by any script on the
 * page -- is exposure with no benefit, so it is dropped on arrival. The port
 * that adds ordering re-adds it here, with the calls that need it.
 */
export type DineInAdmission = Omit<AdmissionResponse, 'guestToken'>;

const STORAGE_KEY = 'horecaos_dinein_admission';

/**
 * The guest side of ADR 0047's QR dine-in flow, as far as this app takes it:
 * exchanging a scanned table code for a short-lived guest admission, and
 * remembering it so a reload of the table screen resumes.
 *
 * Ported from `frontend/storefront`'s service of the same name -- same
 * endpoint, same storage key, same admission shape -- without the calls that
 * only ordering needs (`bill`, `requestBill`, `attachRound`). Those authorise
 * with the guest token as `X-Dine-In-Token`, and nothing in this app calls them
 * yet; porting them here unused would be dead code with a security surface.
 *
 * <h2>A separate identity from the customer's session</h2>
 *
 * The guest token proves "this device scanned table 7", not "this device is
 * Aziz". It is never sent as a bearer and, since nothing here uses it, not kept
 * at all (see {@link DineInAdmission}).
 *
 * <h2>Where the admission lives</h2>
 *
 * `localStorage`, one entry, replaced on every scan: there is exactly one table
 * visit worth remembering at a time. The **printed** table token is never
 * stored -- it is spent by a single request (`DineInScanComponent`).
 */
@Injectable({ providedIn: 'root' })
export class DineInService {
  private readonly api = inject(ApiClient);

  private readonly admissionSignal = signal<DineInAdmission | null>(this.restore());

  /**
   * The current table's admission, or null once it has expired or nothing has
   * been scanned this visit. A plain read of the clock on every call -- a
   * `computed()` would not notice a deadline passing on its own.
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
    const { guestToken: _dropped, ...admission } = await this.api.mutate<AdmissionResponse>(
      'POST',
      '/storefront/dine-in/qr/token-exchanges',
      { body: { tableToken }, anonymous: true },
    );
    this.admissionSignal.set(admission);
    this.persist(admission);
    return admission;
  }

  /** Forgets the current table visit. */
  clear(): void {
    this.admissionSignal.set(null);
    safely(() => localStorage.removeItem(STORAGE_KEY));
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
