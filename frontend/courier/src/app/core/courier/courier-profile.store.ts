import { Injectable, computed, inject, signal } from '@angular/core';

import { ApiError, toApiError } from '../api/problem-details';
import { CourierApi } from './courier-api';
import type { CourierProfile } from './courier-profile';

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
