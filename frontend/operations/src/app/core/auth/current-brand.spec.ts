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

  it('is denied once loaded with no BRAND or LOCATION grant, never before', async () => {
    const promise = brand.ensureLoaded();
    http.expectOne(url('/api/v1/session/context')).flush(
      context([
        {
          scope: { type: 'TENANT', tenantId: 't1', brandId: null, locationId: null },
          roleCode: 'OWNER',
        },
      ]),
    );
    await promise;

    expect(brand.scope()).toBeNull();
    expect(brand.denied()).toBe(true);
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
