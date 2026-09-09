import { TestBed } from '@angular/core/testing';

import { ReferralService } from './referral.service';
import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG, type AppConfig } from '../core/config/app-config';

const CONFIG: AppConfig = {
  apiBaseUrl: '/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
  defaultLocationId: '10000000-0000-0000-0000-000000000003',
  channel: 'STOREFRONT',
  yandexMapsApiKey: '',
  brand: { displayName: 'Test Brand', theme: { accent: '#000000', accentDeep: '#000000' } },
};

class FakeApiClient {
  get = vi.fn();
  list = vi.fn();
  mutate = vi.fn();
}

function setUp(): { service: ReferralService; api: FakeApiClient } {
  const api = new FakeApiClient();
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiClient, useValue: api },
      { provide: APP_CONFIG, useValue: CONFIG },
    ],
  });
  return { service: TestBed.inject(ReferralService), api };
}

describe('ReferralService.myReferral', () => {
  it('reads the brand-scoped referrals/me resource', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue({ code: 'ABCD1234', redeemedAs: null });

    const result = await service.myReferral();

    expect(api.get).toHaveBeenCalledWith(expect.stringContaining('/referrals/me'));
    expect(result).toEqual({ code: 'ABCD1234', redeemedAs: null });
  });
});

describe('ReferralService.redeem', () => {
  it('posts the trimmed code, at the redemptions path', async () => {
    const { service, api } = setUp();
    api.mutate.mockResolvedValue({
      status: 'PENDING',
      redeemedAt: new Date().toISOString(),
      expiresAt: new Date().toISOString(),
      rewardedAt: null,
    });

    await service.redeem('  ABCD1234  ');

    expect(api.mutate).toHaveBeenCalledWith(
      'POST',
      expect.stringContaining('/referrals/redemptions'),
      expect.objectContaining({ body: { code: 'ABCD1234' } }),
    );
  });

  it('generates an idempotency key', async () => {
    const { service, api } = setUp();
    api.mutate.mockResolvedValue({
      status: 'PENDING',
      redeemedAt: new Date().toISOString(),
      expiresAt: new Date().toISOString(),
      rewardedAt: null,
    });

    await service.redeem('ABCD1234');

    expect(api.mutate.mock.calls[0][2]?.idempotencyKey).toBeTruthy();
  });
});
