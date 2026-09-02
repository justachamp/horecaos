import { Injectable, Signal, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../api/api-client';
import { SessionContext } from './session-context';

/**
 * The operator's own tenant, for the screens under Finance that ask a
 * question no brand or location narrows — an order's payment, or the
 * tenant-wide fiscal worklist (`operations-spec/finance.md` §8.1, §8.2).
 *
 * **Why this is not `CurrentBrand` or `CurrentLocation`.** Both of those
 * derive their answer from a `BRAND`- or `LOCATION`-scoped grant in
 * `scopes`, which is right for Catalog and the order board — those
 * endpoints require exactly that path segment. `TENANT_FINANCE` and
 * `TENANT_OWNER` (`PlatformRole`) are `TENANT`-scoped bundles: an operator
 * holding only one of them has no `BRAND` or `LOCATION` grant at all, so
 * `CurrentBrand.scope()` and `CurrentLocation.scope()` would both read
 * `null` for exactly the people Finance exists for. `SessionContext` already
 * carries `activeTenantId` at the top level regardless of grant
 * granularity, so this reads that instead of walking `scopes`.
 *
 * Same non-enforcement stance as its siblings (see `session-context.ts`):
 * this resolves *which tenant to ask*, never *what the operator may do*.
 */
@Injectable({ providedIn: 'root' })
export class CurrentTenant {
  private readonly api = inject(ApiClient);

  private readonly context = signal<SessionContext | null>(null);
  private readonly hasLoaded = signal(false);

  /** The operator's tenant id, or null before load and when the session names none. */
  readonly tenantId: Signal<string | null> = computed(() => this.context()?.activeTenantId ?? null);

  /** See `CurrentLocation.denied` for why this is false, not unknown, before load settles. */
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
