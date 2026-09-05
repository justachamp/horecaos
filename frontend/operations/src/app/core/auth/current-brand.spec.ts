import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { ApiClient } from '../api/api-client';
import { CurrentBrand } from './current-brand';
import { SessionContext } from './session-context';

function url(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
}

/**
 * Resolving a TENANT grant to a brand takes a second, dependent HTTP call —
 * `session/context` then the tenant's brand list — and the continuation that
 * issues that second call runs several promise hops after the first
 * `flush()` (`firstValueFrom`, this class's own `await`, `resolveBrandForTenant`'s
 * `await`). A `setTimeout` macrotask always runs after every microtask
 * already queued, however many hops deep, so this reliably waits for the
 * second request to actually be issued before asserting on it — the same
 * `flushInterceptorChain` idiom `auth.spec.ts` documents for the same reason.
 */
function tick(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('CurrentBrand', () => {
  let http: HttpTestingController;
  let brand: CurrentBrand;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ApiClient, CurrentBrand],
    });
    brand = TestBed.inject(CurrentBrand);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('is unresolved and not yet denied before the session context arrives', () => {
    expect(brand.scope()).toBeNull();
    expect(brand.denied()).toBe(false);
  });

  it('prefers a BRAND-scoped grant over a LOCATION-scoped one', async () => {
    const promise = brand.ensureLoaded();
    http.expectOne(url('/api/v1/session/context')).flush(
      context([
        {
          scope: { type: 'LOCATION', tenantId: 't1', brandId: 'b-location', locationId: 'l1' },
          roleCode: 'MANAGER',
        },
        {
          scope: { type: 'BRAND', tenantId: 't1', brandId: 'b-brand', locationId: null },
          roleCode: 'MANAGER',
        },
      ]),
    );
    await promise;

    expect(brand.scope()).toEqual({ tenantId: 't1', brandId: 'b-brand' });
    expect(brand.denied()).toBe(false);
  });

  it('falls back to a LOCATION-scoped grant’s own brand when no BRAND grant exists', async () => {
    const promise = brand.ensureLoaded();
    http.expectOne(url('/api/v1/session/context')).flush(
      context([
        {
          scope: { type: 'LOCATION', tenantId: 't1', brandId: 'b1', locationId: 'l1' },
          roleCode: 'MANAGER',
        },
      ]),
    );
    await promise;

    expect(brand.scope()).toEqual({ tenantId: 't1', brandId: 'b1' });
  });

  // A TENANT grant — `manager@horecaos.uz` in `dev-personas.md` — carries no
  // BRAND or LOCATION grant of its own. This used to resolve `null`; ADR 0025
  // scopes cover downwards, so a TENANT grant already authorizes reading the
  // tenant's own brand list, and this class now asks for it.
  it('resolves a brand via a TENANT grant that covers brands, when no BRAND or LOCATION grant exists', async () => {
    const promise = brand.ensureLoaded();
    http
      .expectOne(url('/api/v1/session/context'))
      .flush(
        context([
          {
            scope: { type: 'TENANT', tenantId: 't1', brandId: null, locationId: null },
            roleCode: 'OWNER',
          },
        ]),
      );
    await tick();

    http
      .expectOne(url('/api/v1/operations/tenants/t1/brands'))
      .flush([
        {
          id: 'b1',
          tenantId: 't1',
          code: 'MAIN',
          slug: 'main',
          displayName: 'Rayhon',
          status: 'ACTIVE',
        },
      ]);
    await promise;

    expect(brand.scope()).toEqual({ tenantId: 't1', brandId: 'b1' });
    expect(brand.denied()).toBe(false);
  });

  it('is denied when a TENANT grant resolves to a tenant with zero brands', async () => {
    const promise = brand.ensureLoaded();
    http
      .expectOne(url('/api/v1/session/context'))
      .flush(
        context([
          {
            scope: { type: 'TENANT', tenantId: 't1', brandId: null, locationId: null },
            roleCode: 'OWNER',
          },
        ]),
      );
    await tick();

    http.expectOne(url('/api/v1/operations/tenants/t1/brands')).flush([]);
    await promise;

    expect(brand.scope()).toBeNull();
    expect(brand.denied()).toBe(true);
  });

  it('is denied when the TENANT grant’s brand list call itself fails', async () => {
    const promise = brand.ensureLoaded();
    http
      .expectOne(url('/api/v1/session/context'))
      .flush(
        context([
          {
            scope: { type: 'TENANT', tenantId: 't1', brandId: null, locationId: null },
            roleCode: 'OWNER',
          },
        ]),
      );
    await tick();

    http
      .expectOne(url('/api/v1/operations/tenants/t1/brands'))
      .flush('boom', { status: 403, statusText: 'Forbidden' });
    await promise;

    expect(brand.scope()).toBeNull();
    expect(brand.denied()).toBe(true);
  });

  it('is denied once loaded covering nothing at all, never before, with no fallback call attempted', async () => {
    const promise = brand.ensureLoaded();
    http.expectOne(url('/api/v1/session/context')).flush(context([]));
    await promise;

    expect(brand.scope()).toBeNull();
    expect(brand.denied()).toBe(true);
    // `http.verify()` in `afterEach` is the real assertion here: a TENANT-less
    // context must never reach the brand-list fallback.
  });

  it('treats an unreachable session-context call as denied rather than hanging forever', async () => {
    const promise = brand.ensureLoaded();
    http
      .expectOne(url('/api/v1/session/context'))
      .flush('boom', { status: 500, statusText: 'Server Error' });
    await promise;

    expect(brand.scope()).toBeNull();
    expect(brand.denied()).toBe(true);
  });

  it('fetches the session context exactly once no matter how many callers await it', async () => {
    const first = brand.ensureLoaded();
    const second = brand.ensureLoaded();

    http.expectOne(url('/api/v1/session/context')).flush(context([]));
    await Promise.all([first, second]);

    await brand.ensureLoaded();
  });
});

function context(scopes: SessionContext['scopes']): SessionContext {
  return { subject: 'operator-1', activeTenantId: 't1', scopes };
}
