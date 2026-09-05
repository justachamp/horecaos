import { Injectable, Signal, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { BrandScope } from '../api/catalog-paths';
import { ApiClient } from '../api/api-client';
import { settingsPaths } from '../api/settings-paths';
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
 *
 * **The TENANT case (wave 50).** A `TENANT_OWNER`/`TENANT_FINANCE`-style
 * bundle (see `current-tenant.ts`'s doc comment) is a `TENANT`-scoped grant
 * with no `BRAND` or `LOCATION` grant beside it, so the two rules above both
 * read `null` — this used to be the end of resolution, and it is exactly
 * backwards: `dev-personas.md`'s `manager@horecaos.uz` is a tenant-admin, the
 * persona running a multi-branch restaurant, and this class told every
 * Catalog screen it had no brand. ADR 0025 scopes cover downwards, so a
 * TENANT grant already authorizes reading every brand under it
 * (`OperationsBrandController.list` requires `BRAND_READ` at `TENANT` scope,
 * confirmed by reading the controller) — {@link resolveBrandForTenant} asks
 * for that list and takes the first entry, the same "simplest correct thing
 * available today" stance `current-location.ts`'s own doc comment takes for
 * picking a first `LOCATION` grant. A real brand picker is `settings.md`
 * §1.1 work this wave did not build (see `current-location.ts`, which does
 * build the location half); a TENANT-scoped actor with several brands is
 * silently pinned to whichever one the platform lists first until it exists.
 */
@Injectable({ providedIn: 'root' })
export class CurrentBrand {
  private readonly api = inject(ApiClient);

  private readonly context = signal<SessionContext | null>(null);
  private readonly tenantFallback = signal<BrandScope | null>(null);
  private readonly hasLoaded = signal(false);

  /** The operator's brand, or null before load and when no grant covers one. */
  readonly scope: Signal<BrandScope | null> = computed(
    () => firstBrandScope(this.context()) ?? this.tenantFallback(),
  );

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
      if (!firstBrandScope(result.value)) {
        this.tenantFallback.set(await resolveBrandForTenant(this.api, result.value));
      }
    } catch {
      this.context.set(null);
    } finally {
      this.hasLoaded.set(true);
    }
  }
}

/**
 * The synchronous half of resolution — a direct `BRAND` grant, or a
 * `LOCATION` grant's own brand. Exported so `current-location.ts` can resolve
 * "which brand does this operator's location board belong to" from the exact
 * same rule without a second implementation, and without instantiating this
 * class (which would mean a second, redundant `/session/context` fetch — see
 * that file's own doc comment on why each of these singletons fetches its own
 * copy rather than sharing one).
 */
export function firstBrandScope(context: SessionContext | null): BrandScope | null {
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

/**
 * The TENANT-covers-brands fallback described above. Returns `null` rather
 * than throwing for every way this can fail to produce an answer — no TENANT
 * grant, a TENANT grant but zero brands provisioned, or the list call itself
 * failing (a genuine 403 included) — so a caller can treat all of them the
 * same way: nothing resolved, fall through to denied. Never called when a
 * direct grant already answered the question, so an actor who already has a
 * BRAND or LOCATION grant never pays for this extra round trip.
 */
export async function resolveBrandForTenant(
  api: ApiClient,
  context: SessionContext | null,
): Promise<BrandScope | null> {
  const tenantId = firstTenantId(context);
  if (!tenantId) {
    return null;
  }
  try {
    const result = await firstValueFrom(
      api.get<readonly BrandSummary[]>(
        settingsPaths.brands({ tenantId, brandId: '', locationId: '' }),
      ),
    );
    const first = (result.value ?? [])[0];
    return first ? { tenantId, brandId: first.id } : null;
  } catch {
    return null;
  }
}

/** Only the field this module reads from `OperationsBrandController.list`'s `BrandView`. */
interface BrandSummary {
  readonly id: string;
}

function firstTenantId(context: SessionContext | null): string | null {
  const grant = context?.scopes.find(
    (candidate) => candidate.scope.type === 'TENANT' && candidate.scope.tenantId !== null,
  );
  // The `find` predicate already proved this is non-null; see `toBrandScope`
  // below for why TypeScript cannot see across the closure.
  return grant ? (grant.scope.tenantId as string) : null;
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
