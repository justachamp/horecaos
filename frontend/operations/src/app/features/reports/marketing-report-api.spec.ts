import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { MarketingReportApi } from './marketing-report-api';

describe('MarketingReportApi.revealRedemptionCustomer (row 7.9, who redeemed it)', () => {
  function apiAnswering(answer: unknown): {
    api: MarketingReportApi;
    post: ReturnType<typeof vi.fn>;
  } {
    const post = vi.fn().mockReturnValue(of(answer));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [{ provide: ApiClient, useValue: { post } }] });
    return { api: TestBed.inject(MarketingReportApi), post };
  }

  it('posts the purpose, and only the purpose, to the redemption’s own reveal under an idempotency key', async () => {
    const answer = {
      redemptionId: 'r1',
      promotionId: 'p1',
      sourceKind: 'COUPON',
      orderId: 'o1',
      customerAccountId: 'a1',
    };
    const { api, post } = apiAnswering(answer);

    const revealed = await api.revealRedemptionCustomer(
      { tenantId: 't1', brandId: 'b1' },
      'p1',
      'r1',
      'Operations console: marketing report, who redeemed it',
    );

    expect(revealed).toEqual(answer);
    expect(post).toHaveBeenCalledTimes(1);
    const [path, intent] = post.mock.calls[0];
    expect(path).toBe(
      '/api/v1/operations/tenants/t1/brands/b1/promotions/p1/redemptions/r1/customer-reveal',
    );
    expect(intent.body).toEqual({
      purpose: 'Operations console: marketing report, who redeemed it',
    });
    expect(typeof intent.key).toBe('string');
    expect(intent.key.length).toBeGreaterThan(0);
  });

  it('keeps a guest order’s missing account as null, for the screen to say so', async () => {
    const { api } = apiAnswering({
      redemptionId: 'r1',
      promotionId: 'p1',
      sourceKind: 'AUTOMATIC',
      orderId: 'o1',
      customerAccountId: null,
    });

    const revealed = await api.revealRedemptionCustomer(
      { tenantId: 't1', brandId: 'b1' },
      'p1',
      'r1',
      'x',
    );

    expect(revealed.customerAccountId).toBeNull();
  });
});
