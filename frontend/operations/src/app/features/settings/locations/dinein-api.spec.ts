import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../../environments/environment';
import { DineInApi, DineInSettingsView } from './dinein-api';

const SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const URL = `${environment.apiBaseUrl}/api/v1/tenants/t1/brands/b1/locations/l1/dine-in/settings`;

const SETTINGS: DineInSettingsView = {
  locationId: 'l1',
  qrMode: 'ORDER_AND_PAY',
  turnaroundMinutes: 15,
  guestSessionTtlMinutes: 240,
  serviceChargeRateBp: 0,
  version: 2,
  walkInSelfSeat: true,
  walkInClaimTtlMinutes: 20,
  walkInHorizonMinutes: 90,
  walkInMaxUnconfirmed: 5,
  walkInDailyClaimsPerAccount: 3,
  walkInPaymentDeferMinutes: 30,
  sessionCurrency: 'UZS',
};

describe('DineInApi settings (ADR 0143)', () => {
  let api: DineInApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), DineInApi],
    });
    api = TestBed.inject(DineInApi);
    http = TestBed.inject(HttpTestingController);
  });

  it('writes the settings against the version the screen read, with an Idempotency-Key', async () => {
    const saved = api.configure(
      SCOPE,
      {
        qrMode: 'ORDER_AND_PAY',
        turnaroundMinutes: 15,
        guestSessionTtlMinutes: 240,
        serviceChargeRateBp: 0,
        walkInSelfSeat: true,
        walkInClaimTtlMinutes: 20,
        reason: 'Pilot at this branch',
      },
      1,
    );
    const request = http.expectOne(URL);

    expect(request.request.method).toBe('PUT');
    expect(request.request.headers.get('If-Match')).toContain('1');
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    expect(request.request.body).toMatchObject({
      walkInSelfSeat: true,
      walkInClaimTtlMinutes: 20,
      reason: 'Pilot at this branch',
    });
    request.flush(SETTINGS);
    expect((await saved).version).toBe(2);
  });

  it('creates the settings of a never-configured branch against version 0', async () => {
    const saved = api.configure(
      SCOPE,
      {
        qrMode: 'VIEW_ONLY',
        turnaroundMinutes: 15,
        guestSessionTtlMinutes: 240,
        serviceChargeRateBp: 0,
        reason: 'First configuration',
      },
      0,
    );
    const request = http.expectOne(URL);

    // A version of zero is a version: it must not be dropped as "no precondition".
    expect(request.request.headers.get('If-Match')).toContain('0');
    request.flush({ ...SETTINGS, version: 1, walkInSelfSeat: false });
    await saved;
  });

  it('reads the self-seating switch and its numbers back', async () => {
    const read = api.settings(SCOPE);
    http.expectOne(URL).flush(SETTINGS);

    const view = await read;
    expect(view.walkInSelfSeat).toBe(true);
    expect(view.walkInMaxUnconfirmed).toBe(5);
    expect(view.sessionCurrency).toBe('UZS');
  });
});
