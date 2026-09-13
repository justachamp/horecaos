import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../../environments/environment';
import { BrandScope } from '../../../core/api/catalog-paths';
import { DraftPromoCodeRequest, PromoCodesApi } from './promo-codes-api';

function url(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
}

const SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

describe('PromoCodesApi', () => {
  let api: PromoCodesApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), PromoCodesApi],
    });
    api = TestBed.inject(PromoCodesApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  // The gap this wave closes: validFrom/validUntil/channels/locationIds were
  // accepted by the request type but no caller ever populated them, so every
  // code drafted from the console ran forever across every channel and
  // branch. This pins that the client actually carries them on the wire.
  it('drafts a code carrying validFrom, validUntil, channels and locationIds on the wire', async () => {
    const request: DraftPromoCodeRequest = {
      name: 'Weekend only',
      code: 'WEEKEND1',
      shape: 'PERCENTAGE_OFF_ORDER',
      value: 1_000,
      maximumDiscountMinor: null,
      currency: 'UZS',
      minBasketMinor: 0,
      channels: ['WEBSITE'],
      locationIds: ['loc-1'],
      totalLimit: null,
      perCustomerLimit: 1,
      validFrom: '2026-10-01T00:00:00.000Z',
      validUntil: '2026-10-31T23:59:59.999Z',
    };
    const promise = api.draft(SCOPE, request);
    const httpRequest = http.expectOne(url('/api/v1/operations/tenants/t1/brands/b1/promo-codes'));

    expect(httpRequest.request.body).toEqual(request);
    httpRequest.flush({
      couponId: 'coupon-1',
      name: request.name,
      plaintextCode: request.code,
      codeHint: 'END1',
      actionType: 'ORDER_PERCENTAGE_DISCOUNT',
      value: 1_000,
      minBasketMinor: 0,
      maximumDiscountMinor: null,
      currency: 'UZS',
      channels: ['WEBSITE'],
      locationIds: ['loc-1'],
      totalLimit: null,
      perCustomerLimit: 1,
      redeemedCount: 0,
      status: 'SUSPENDED',
      version: 1,
      validFrom: request.validFrom,
      validUntil: request.validUntil,
    });
    expect((await promise).channels).toEqual(['WEBSITE']);
  });

  it('reads the redemption ledger at the coupon’s own /redemptions path', async () => {
    const promise = api.listRedemptions(SCOPE, 'coupon-1');
    const request = http.expectOne(
      url('/api/v1/operations/tenants/t1/brands/b1/promo-codes/coupon-1/redemptions'),
    );
    expect(request.request.method).toBe('GET');
    request.flush([
      {
        redemptionId: 'r1',
        customerAccountId: 'acct-1',
        orderId: 'order-1',
        status: 'REDEEMED',
        amountMinor: 5_000,
        currency: 'UZS',
        reservedAt: '2026-09-05T10:00:00Z',
        redeemedAt: '2026-09-05T10:00:05Z',
        releasedAt: null,
      },
    ]);
    expect(await promise).toHaveLength(1);
  });

  it('defaults a null redemption-ledger envelope to an empty array rather than throwing', async () => {
    const promise = api.listRedemptions(SCOPE, 'coupon-1');
    http
      .expectOne(url('/api/v1/operations/tenants/t1/brands/b1/promo-codes/coupon-1/redemptions'))
      .flush(null);
    expect(await promise).toEqual([]);
  });
});
