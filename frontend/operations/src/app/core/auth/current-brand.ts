import { Injectable, Signal, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { BrandScope } from '../api/catalog-paths';
import { ApiClient } from '../api/api-client';
import { SessionContext, ScopeGrant } from './session-context';

/**
 * Which brand the operator is authoring Catalog against, until a real brand
 * switcher exists.
 *
 * Catalog authoring (`docs/operations-spec/catalog.md` §"Shared conventions")
 * is brand-scoped, not location-scoped — Products, the Product editor and
 * Categories all carry a brand selector in their header, never a location
 * one. That is a different resolution question from {@link CurrentLocation},
 * which exists to answer "which branch is the order board asking about" — so
 * this is a sibling service, not a reuse of it, mirroring the same pattern
 * `current-location.ts` documents (fetch `GET /api/v1/session/context` once,
 * derive the narrowest fact this feature needs, leave capability decisions to
 * the server).
 *
 * **Resolution order.** A `BRAND`-scoped grant is preferred — it is the
 * direct answer. Failing that, a `LOCATION`-scoped grant still names its own
 * `tenantId`/`brandId` (a location cannot exist without a brand), so its
 * brand is used to build a URL. This is deliberately *not* an authorization
 * claim: ADR 0025 scopes cover downwards and never up, so a location-scoped
 * actor asking a brand-scoped endpoint about their own brand still gets
 * whatever the server decides — same non-enforcement stance
 * `session-context.ts` documents for `CurrentLocation`.
 */
@Injectable({ providedIn: 'root' })
export class CurrentBrand {
  private readonly api = inject(ApiClient);

  private readonly context = signal<SessionContext | null>(null);
  private readonly hasLoaded = signal(false);

  /** The operator's brand, or null before load and when no grant names one. */
  readonly scope: Signal<BrandScope | null> = computed(() => firstBrandScope(this.context()));

  /** See {@link CurrentLocation.denied} for why this is false, not unknown, before load settles. */
  readonly denied: Signal<boolean> = computed(() => this.hasLoaded() && this.scope() === null);

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

function firstBrandScope(context: SessionContext | null): BrandScope | null {
  if (!context) {
    return null;
  }
  const brandGrant = context.scopes.find(
    (candidate) => candidate.scope.type === 'BRAND' && hasBrand(candidate),
  );
  if (brandGrant) {
    return toBrandScope(brandGrant);
  }
  const locationGrant = context.scopes.find(
    (candidate) => candidate.scope.type === 'LOCATION' && hasBrand(candidate),
  );
  return locationGrant ? toBrandScope(locationGrant) : null;
}

function hasBrand(grant: ScopeGrant): boolean {
  return grant.scope.tenantId !== null && grant.scope.brandId !== null;
}

function toBrandScope(grant: ScopeGrant): BrandScope {
  // `hasBrand` already proved these are non-null; see `current-location.ts`'s
  // identical assertion for why TypeScript cannot see across the closure.
  return {
    tenantId: grant.scope.tenantId as string,
    brandId: grant.scope.brandId as string,
  };
}
