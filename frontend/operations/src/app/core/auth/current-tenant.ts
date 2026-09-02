import { Injectable, Signal, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../api/api-client';
import { ScopeGrant, SessionContext } from './session-context';

/**
 * The signed-in operator's tenant and their own scopes within it — for the
 * Staff section (staff-and-access.md §9.1–9.4), which is tenant-wide by
 * nature (a manager sees the whole company grouped by branch, not one
 * branch's queue) and so has no use for {@link CurrentLocation}'s
 * single-location resolution.
 *
 * Unlike {@link CurrentBrand}/{@link CurrentLocation}, the tenant does not
 * need to be *derived* from `scopes` — `CapabilityView.activeTenantId`
 * already names it directly (`GrantController.sessionContext`'s own doc:
 * resolved from the token's signed organization claim). This exists anyway,
 * as a sibling rather than a fourth copy of the same fetch-once pattern, so
 * the Staff section does not reach into `CurrentLocation`'s location-shaped
 * cache for a fact that is not about a location.
 */
@Injectable({ providedIn: 'root' })
export class CurrentTenant {
  private readonly api = inject(ApiClient);

  private readonly context = signal<SessionContext | null>(null);
  private readonly hasLoaded = signal(false);

  /** The operator's tenant id, or null before load and when the token names none (a platform-scope session). */
  readonly tenantId: Signal<string | null> = computed(() => this.context()?.activeTenantId ?? null);

  /** Every scope the operator holds, for {@link ../../features/staff/scope-coverage.ts}'s coverage checks. */
  readonly scopes: Signal<readonly ScopeGrant[]> = computed(() => this.context()?.scopes ?? []);

  /** See {@link CurrentLocation.denied} for why this is false, not unknown, before load settles. */
  readonly denied: Signal<boolean> = computed(() => this.hasLoaded() && this.tenantId() === null);

  private loadPromise: Promise<void> | null = null;

  /** Fetches the session context once; every later call replays the same promise. */
  ensureLoaded(): Promise<void> {
    if (this.loadPromise === null) {
      this.loadPromise = this.load();
    }
    return this.loadPromise;
  }

  private async load(): Promise<void> {
    try {
      const result = await firstValueFrom(this.api.get<SessionContext>('/api/v1/session/context'));
      this.context.set(result.value);
    } catch {
      this.context.set(null);
    } finally {
      this.hasLoaded.set(true);
    }
  }
}
