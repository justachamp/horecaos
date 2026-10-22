import { Injectable, computed, inject, signal } from '@angular/core';

import { ApiError, toApiError } from '../api/problem-details';
import { CourierApi } from './courier-api';
import type { CourierProfile } from './courier-profile';
import type { BranchRef } from './courier-shift';

/**
 * Where the courier's own record is in its life on this screen.
 *
 * `not-registered` is its own state and not an error: a 404 from `/me` means the sign-in worked
 * and the account is simply not a courier of this tenant, which is a sentence for the courier to
 * read, not a failure to retry.
 */
export type ProfileState = 'idle' | 'loading' | 'ready' | 'not-registered' | 'error';

@Injectable({ providedIn: 'root' })
export class CourierProfileStore {
  private readonly api = inject(CourierApi);

  private readonly current = signal<CourierProfile | null>(null);
  private readonly status = signal<ProfileState>('idle');
  private readonly failure = signal<ApiError | null>(null);

  readonly profile = this.current.asReadonly();
  readonly state = this.status.asReadonly();
  readonly error = this.failure.asReadonly();

  readonly primaryBranch = computed(
    () => this.current()?.branches.find((branch) => branch.primary) ?? null,
  );

  /**
   * The branch an open shift is at. Offers, duty and the shift's own calls belong to it, and it is
   * nothing when no shift is open.
   */
  readonly shiftBranch = computed<BranchRef | null>(() => {
    const shift = this.current()?.openShift;
    return shift ? { brandId: shift.brandId, locationId: shift.locationId } : null;
  });

  /**
   * The branch the courier's work is read at: the open shift's, else the main branch -- a courier
   * who has closed a shift still finishes (and sees) what they already carry there.
   */
  readonly workBranch = computed<BranchRef | null>(() => {
    const open = this.shiftBranch();
    if (open !== null) {
      return open;
    }
    const main = this.primaryBranch() ?? this.current()?.branches[0] ?? null;
    return main ? { brandId: main.brandId, locationId: main.locationId } : null;
  });

  /** For a screen that only needs the record to be there: loads it the first time and then leaves it. */
  async ensureLoaded(): Promise<void> {
    if (this.status() === 'idle') {
      await this.load();
    }
  }

  /** Loads (or reloads) the record. A reload keeps showing the old one until the new one arrives. */
  async load(): Promise<void> {
    if (this.current() === null) {
      this.status.set('loading');
    }
    try {
      this.current.set(await this.api.me());
      this.failure.set(null);
      this.status.set('ready');
    } catch (cause) {
      const error = toApiError(cause);
      this.failure.set(error);
      if (error.isNotFound) {
        this.current.set(null);
        this.status.set('not-registered');
      } else if (this.current() === null) {
        this.status.set('error');
      }
      // A failed reload of a record already shown leaves it on screen; the caller reads `error()`.
    }
  }
}
