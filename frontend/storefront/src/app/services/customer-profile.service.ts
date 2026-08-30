import { Injectable, computed, inject, signal } from '@angular/core';

import { CustomerApi, type CustomerProfile } from '../core/api/customer-api';
import { isNotFound } from '../core/api/problem-details';
import { newIdempotencyKey } from '../core/api/idempotency';

/**
 * The customer's own account, as the profile screens need it.
 *
 * Replaces the legacy `AuthService`'s `/customers/accounts/me` calls. Three
 * things about the platform's surface change how this has to work, and none of
 * them is optional.
 *
 * **The PATCH replaces all three fields it owns.** The server writes
 * `displayName`, `preferredLocale` and `preferredTimezone` unconditionally, so a
 * field left out of the body is a field set to null. Sending only the language
 * -- which is exactly what the language screen used to do -- would wipe the
 * customer's name. Every write here therefore echoes back the whole profile,
 * which is why the last read is cached rather than re-fetched per screen.
 *
 * **A write needs the version it is replacing.** It travels as `If-Match`, and a
 * second tab that got there first loses with `STALE_VERSION` instead of quietly
 * overwriting. That means a write is only possible after a read.
 *
 * **A guest is answered not-found, not forbidden.** A signed-in caller with no
 * account at this brand gets 404 from every method, and that is a state to
 * render -- "you have not ordered here yet" -- rather than a failure to report.
 */
@Injectable({ providedIn: 'root' })
export class CustomerProfileService {
  private readonly api = inject(CustomerApi);

  private readonly current = signal<CustomerProfile | null>(null);
  private readonly loaded = signal(false);

  /** The last profile read, or null for a guest or before the first read. */
  readonly profile = computed(() => this.current());

  /** True once a read has completed, whatever it found. */
  readonly isLoaded = computed(() => this.loaded());

  /**
   * The display name split into the two fields the legacy screens show.
   *
   * The platform stores one `displayName`; the legacy profile form has a first
   * and a last name. The split is on the first space, which is a lossy guess for
   * a two-word surname and is the honest cost of mapping one field onto two. It
   * round-trips: what is split here is rejoined on save, so a name is never
   * silently rewritten by opening the screen.
   */
  readonly firstName = computed(() => splitName(this.current()?.displayName).first);
  readonly lastName = computed(() => splitName(this.current()?.displayName).last);

  /**
   * Reads the account, treating not-found as "no account yet".
   *
   * @returns null for a guest. A caller must not render that as an error: the
   *          customer is signed in, they simply have no record at this brand
   *          until they order.
   */
  async load(): Promise<CustomerProfile | null> {
    try {
      const profile = await this.api.profile();
      this.current.set(profile);
      return profile;
    } catch (failure) {
      if (isNotFound(failure)) {
        this.current.set(null);
        return null;
      }
      throw failure;
    } finally {
      this.loaded.set(true);
    }
  }

  /**
   * Changes what the customer owns, sending all three fields every time.
   *
   * @param changes only the fields being changed. Everything else is echoed from
   *        the cached profile, because an omitted field is a cleared field.
   * @throws when there is no profile to write over. A create is `POST
   *         /identity/sessions`' job, not this one.
   */
  async update(changes: {
    firstName?: string;
    lastName?: string;
    locale?: string;
  }): Promise<CustomerProfile> {
    const existing = this.current();
    if (!existing) {
      throw new Error('There is no profile to update; read one first.');
    }

    const names = splitName(existing.displayName);
    const first = changes.firstName ?? names.first;
    const last = changes.lastName ?? names.last;
    const displayName = [first, last].map((part) => part.trim()).filter(Boolean).join(' ');

    const updated = await this.api.updateProfile({
      expectedVersion: existing.version,
      displayName: displayName || null,
      preferredLocale: changes.locale ?? existing.preferredLocale,
      // Echoed and never guessed. Overwriting it with the browser's zone would
      // move a customer's notification times whenever they travelled.
      preferredTimezone: existing.preferredTimezone,
      idempotencyKey: newIdempotencyKey(),
    });
    this.current.set(updated);
    return updated;
  }

  /** Drops the cached profile. Called when the session ends. */
  forget(): void {
    this.current.set(null);
    this.loaded.set(false);
  }
}

function splitName(displayName: string | null | undefined): { first: string; last: string } {
  const trimmed = (displayName ?? '').trim();
  if (!trimmed) {
    return { first: '', last: '' };
  }
  const boundary = trimmed.indexOf(' ');
  return boundary === -1
    ? { first: trimmed, last: '' }
    : { first: trimmed.slice(0, boundary), last: trimmed.slice(boundary + 1).trim() };
}
