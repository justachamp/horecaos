import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { ApiClient } from '../api/api-client';
import { CurrentLocation } from './current-location';
import { SessionContext } from './session-context';

const STORAGE_KEY = 'horecaos.operations.locationId';

/** The absolute URL `ApiClient` actually requests — see `api-client.spec.ts`'s own helper of the same shape. */
function url(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
}

/**
 * The brand/tenant resolution path this file exercises makes a second (or
 * third) dependent HTTP call, and the continuation that issues it runs
 * several promise hops after the prior `flush()` (`firstValueFrom`, this
 * class's own `await`s, `current-brand.ts`'s `resolveBrandForTenant`). A
 * `setTimeout` macrotask always runs after every microtask already queued,
 * however many hops deep, so this reliably waits for the next request to
 * actually be issued — the same `flushInterceptorChain` idiom `auth.spec.ts`
 * documents for the identical reason.
 */
function tick(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe('CurrentLocation', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    // Cleared, not just left alone: a remembered-location test in this file
    // writes real `localStorage`, and jsdom keeps it across tests in the same
    // file otherwise — see `core/i18n/i18n.spec.ts` for the same guard.
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ApiClient, CurrentLocation],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  /**
   * Not a fixture captured once in `beforeEach`: the "remembers across a
   * reload" tests below need `localStorage` populated *before* this class is
   * first constructed, since it reads storage in a field initializer.
   * Injecting lazily, per test, is what makes that orderable.
   */
  function location(): CurrentLocation {
    return TestBed.inject(CurrentLocation);
  }

  it('is unresolved and not yet denied before the session context arrives', () => {
    expect(location().scope()).toBeNull();
    expect(location().denied()).toBe(false);
  });

  it('resolves the first LOCATION-scoped grant, with no extra network call', async () => {
    const loc = location();
    const promise = loc.ensureLoaded();
    http.expectOne(url('/api/v1/session/context')).flush(
      context([
        {
          scope: { type: 'TENANT', tenantId: 't1', brandId: null, locationId: null },
          roleCode: 'OWNER',
        },
        {
          scope: { type: 'LOCATION', tenantId: 't1', brandId: 'b1', locationId: 'l1' },
          roleCode: 'MANAGER',
        },
      ]),
    );
    await promise;

    expect(loc.scope()).toEqual({ tenantId: 't1', brandId: 'b1', locationId: 'l1' });
    expect(loc.denied()).toBe(false);
    // No display name is fetched for this path — see this class's own doc
    // comment on `options` for why. The picker has nothing to show either
    // way, since one location is one location.
    expect(loc.options()).toEqual([]);
  });

  // The reproduced defect: a BRAND grant with no LOCATION grant beside it —
  // `manager@horecaos.uz` also holds one of these — used to resolve `null`.
  it('resolves a location via a BRAND-scoped grant, fetching the brand’s own locations', async () => {
    const loc = location();
    const promise = loc.ensureLoaded();
    http
      .expectOne(url('/api/v1/session/context'))
      .flush(
        context([
          {
            scope: { type: 'BRAND', tenantId: 't1', brandId: 'b1', locationId: null },
            roleCode: 'MANAGER',
          },
        ]),
      );
    await tick();

    http
      .expectOne(url('/api/v1/operations/tenants/t1/brands/b1/locations'))
      .flush([location_('l1', 'Chilanzar'), location_('l2', 'Yunusabad')]);
    await promise;

    expect(loc.scope()).toEqual({ tenantId: 't1', brandId: 'b1', locationId: 'l1' });
    expect(loc.denied()).toBe(false);
    expect(loc.options().map((option) => option.id)).toEqual(['l1', 'l2']);
  });

  // Mission-critical regression case: a TENANT grant with no BRAND or
  // LOCATION grant at all — `manager@horecaos.uz`'s tenant-admin bundle.
  // This is the scenario that showed "нет доступа" on the order board.
  it('resolves a location via a TENANT-scoped grant that covers brands, when no BRAND or LOCATION grant exists', async () => {
    const loc = location();
    const promise = loc.ensureLoaded();
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
    await tick();

    http
      .expectOne(url('/api/v1/operations/tenants/t1/brands/b1/locations'))
      .flush([location_('l1', 'Chilanzar')]);
    await promise;

    expect(loc.scope()).toEqual({ tenantId: 't1', brandId: 'b1', locationId: 'l1' });
    expect(loc.denied()).toBe(false);
  });

  it('is denied once loaded covering nothing at all, never before, with no fallback call attempted', async () => {
    const loc = location();
    const promise = loc.ensureLoaded();
    http.expectOne(url('/api/v1/session/context')).flush(context([]));
    await promise;

    expect(loc.scope()).toBeNull();
    expect(loc.denied()).toBe(true);
    expect(loc.options()).toEqual([]);
    // `http.verify()` in `afterEach` is the real assertion here: covering
    // nothing must never reach the brand or location list calls.
  });

  it('is denied when a resolved brand turns out to have zero locations', async () => {
    const loc = location();
    const promise = loc.ensureLoaded();
    http
      .expectOne(url('/api/v1/session/context'))
      .flush(
        context([
          {
            scope: { type: 'BRAND', tenantId: 't1', brandId: 'b1', locationId: null },
            roleCode: 'MANAGER',
          },
        ]),
      );
    await tick();

    http.expectOne(url('/api/v1/operations/tenants/t1/brands/b1/locations')).flush([]);
    await promise;

    expect(loc.scope()).toBeNull();
    expect(loc.denied()).toBe(true);
  });

  it('treats an unreachable session-context call as denied rather than hanging forever', async () => {
    const loc = location();
    const promise = loc.ensureLoaded();
    http
      .expectOne(url('/api/v1/session/context'))
      .flush('boom', { status: 500, statusText: 'Server Error' });
    await promise;

    expect(loc.scope()).toBeNull();
    expect(loc.denied()).toBe(true);
  });

  it('fetches the session context exactly once no matter how many callers await it', async () => {
    const loc = location();
    const first = loc.ensureLoaded();
    const second = loc.ensureLoaded();

    http.expectOne(url('/api/v1/session/context')).flush(context([]));
    await Promise.all([first, second]);

    // A third call after settling replays the same resolved promise rather
    // than issuing a second request.
    await loc.ensureLoaded();
  });

  it('remembers the operator’s chosen location across a reload', async () => {
    localStorage.setItem(STORAGE_KEY, 'l2');
    const loc = location(); // constructed only now, so it reads the value above

    const promise = loc.ensureLoaded();
    http
      .expectOne(url('/api/v1/session/context'))
      .flush(
        context([
          {
            scope: { type: 'BRAND', tenantId: 't1', brandId: 'b1', locationId: null },
            roleCode: 'MANAGER',
          },
        ]),
      );
    await tick();

    http
      .expectOne(url('/api/v1/operations/tenants/t1/brands/b1/locations'))
      .flush([location_('l1', 'Chilanzar'), location_('l2', 'Yunusabad')]);
    await promise;

    expect(loc.scope()).toEqual({ tenantId: 't1', brandId: 'b1', locationId: 'l2' });
  });

  it('discards a remembered location the operator no longer covers, falling back to the first option', async () => {
    localStorage.setItem(STORAGE_KEY, 'l-gone');
    const loc = location();

    const promise = loc.ensureLoaded();
    http
      .expectOne(url('/api/v1/session/context'))
      .flush(
        context([
          {
            scope: { type: 'BRAND', tenantId: 't1', brandId: 'b1', locationId: null },
            roleCode: 'MANAGER',
          },
        ]),
      );
    await tick();

    http
      .expectOne(url('/api/v1/operations/tenants/t1/brands/b1/locations'))
      .flush([location_('l1', 'Chilanzar'), location_('l2', 'Yunusabad')]);
    await promise;

    // Never the vanished 'l-gone', and never an error — the actor's own
    // brand still has locations, so this is not a "covers nothing" outcome.
    expect(loc.scope()).toEqual({ tenantId: 't1', brandId: 'b1', locationId: 'l1' });
  });

  it('switches the resolved location and remembers the choice for the next reload', async () => {
    const loc = location();
    const promise = loc.ensureLoaded();
    http
      .expectOne(url('/api/v1/session/context'))
      .flush(
        context([
          {
            scope: { type: 'BRAND', tenantId: 't1', brandId: 'b1', locationId: null },
            roleCode: 'MANAGER',
          },
        ]),
      );
    await tick();
    http
      .expectOne(url('/api/v1/operations/tenants/t1/brands/b1/locations'))
      .flush([location_('l1', 'Chilanzar'), location_('l2', 'Yunusabad')]);
    await promise;

    loc.selectLocation('l2');

    expect(loc.scope()).toEqual({ tenantId: 't1', brandId: 'b1', locationId: 'l2' });
    expect(localStorage.getItem(STORAGE_KEY)).toBe('l2');
  });

  it('offers exactly one option when the resolved brand has exactly one location', async () => {
    const loc = location();
    const promise = loc.ensureLoaded();
    http
      .expectOne(url('/api/v1/session/context'))
      .flush(
        context([
          {
            scope: { type: 'BRAND', tenantId: 't1', brandId: 'b1', locationId: null },
            roleCode: 'MANAGER',
          },
        ]),
      );
    await tick();
    http
      .expectOne(url('/api/v1/operations/tenants/t1/brands/b1/locations'))
      .flush([location_('l1', 'Chilanzar')]);
    await promise;

    expect(loc.options().length).toBe(1);
  });
});

function context(scopes: SessionContext['scopes']): SessionContext {
  return { subject: 'operator-1', activeTenantId: 't1', scopes };
}

function location_(
  id: string,
  displayName: string,
  status: 'DRAFT' | 'ACTIVE' | 'SUSPENDED' | 'ARCHIVED' = 'ACTIVE',
) {
  return { id, tenantId: 't1', brandId: 'b1', displayName, status };
}
