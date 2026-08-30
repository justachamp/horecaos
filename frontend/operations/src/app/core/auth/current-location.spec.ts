import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { ApiClient } from '../api/api-client';
import { CurrentLocation } from './current-location';
import { SessionContext } from './session-context';

/** The absolute URL `ApiClient` actually requests — see `api-client.spec.ts`'s own helper of the same shape. */
function url(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
}

describe('CurrentLocation', () => {
  let http: HttpTestingController;
  let location: CurrentLocation;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ApiClient, CurrentLocation],
    });
    location = TestBed.inject(CurrentLocation);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('is unresolved and not yet denied before the session context arrives', () => {
    expect(location.scope()).toBeNull();
    expect(location.denied()).toBe(false);
  });

  it('resolves the first LOCATION-scoped grant', async () => {
    const promise = location.ensureLoaded();
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

    expect(location.scope()).toEqual({ tenantId: 't1', brandId: 'b1', locationId: 'l1' });
    expect(location.denied()).toBe(false);
  });

  it('is denied once loaded with no LOCATION grant, never before', async () => {
    const promise = location.ensureLoaded();
    http.expectOne(url('/api/v1/session/context')).flush(
      context([
        {
          scope: { type: 'TENANT', tenantId: 't1', brandId: null, locationId: null },
          roleCode: 'OWNER',
        },
      ]),
    );
    await promise;

    expect(location.scope()).toBeNull();
    expect(location.denied()).toBe(true);
  });

  it('treats an unreachable session-context call as denied rather than hanging forever', async () => {
    const promise = location.ensureLoaded();
    http
      .expectOne(url('/api/v1/session/context'))
      .flush('boom', { status: 500, statusText: 'Server Error' });
    await promise;

    expect(location.scope()).toBeNull();
    expect(location.denied()).toBe(true);
  });

  it('fetches the session context exactly once no matter how many callers await it', async () => {
    const first = location.ensureLoaded();
    const second = location.ensureLoaded();

    http.expectOne(url('/api/v1/session/context')).flush(context([]));
    await Promise.all([first, second]);

    // A third call after settling replays the same resolved promise rather
    // than issuing a second request.
    await location.ensureLoaded();
  });
});

function context(scopes: SessionContext['scopes']): SessionContext {
  return { subject: 'operator-1', activeTenantId: 't1', scopes };
}
