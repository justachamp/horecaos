import { TestBed } from '@angular/core/testing';

import { PaymentSessionService } from './payment-session.service';
import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG, type AppConfig } from '../core/config/app-config';
import { LangService } from './lang.service';

const CONFIG: AppConfig = {
  apiBaseUrl: '/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
  defaultLocationId: '10000000-0000-0000-0000-000000000003',
  channel: 'STOREFRONT',
  yandexMapsApiKey: '',
};

class FakeApiClient {
  get = vi.fn();
  mutate = vi.fn();
}

class FakeLangService {
  langId = vi.fn(() => 'ru');
}

function setUp(): { service: PaymentSessionService; api: FakeApiClient } {
  const api = new FakeApiClient();
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiClient, useValue: api },
      { provide: APP_CONFIG, useValue: CONFIG },
      { provide: LangService, useClass: FakeLangService },
    ],
  });
  return { service: TestBed.inject(PaymentSessionService), api };
}

describe('PaymentSessionService.requiresOnlineSession', () => {
  it('is true for CLICK and PAYME', () => {
    expect(PaymentSessionService.requiresOnlineSession('CLICK')).toBe(true);
    expect(PaymentSessionService.requiresOnlineSession('PAYME')).toBe(true);
  });

  it('CASH never opens a session', () => {
    expect(PaymentSessionService.requiresOnlineSession('CASH')).toBe(false);
  });

  it('is false for an unrecognised code, rather than assuming it is online', () => {
    expect(PaymentSessionService.requiresOnlineSession('SOME_FUTURE_METHOD')).toBe(false);
  });
});

describe('PaymentSessionService.open', () => {
  it('sends a returnUrl derived from window.location.origin, carrying the order id', async () => {
    const { service, api } = setUp();
    api.mutate.mockResolvedValue({
      attemptId: 'a1',
      merchantTransId: 'm1',
      provider: 'CLICK',
      presentation: 'PAYMENT_LINK',
      checkoutUrl: 'https://click.example/pay',
      qrPayload: null,
      expiresAt: new Date().toISOString(),
      amountMinor: 10_000,
      currency: 'UZS',
      rePresented: false,
      presentationCount: 1,
    });

    await service.open('order-42');

    const [method, path, options] = api.mutate.mock.calls[0];
    expect(method).toBe('POST');
    expect(path).toContain('/orders/order-42/payment-sessions');
    const body = options.body as Record<string, unknown>;
    expect(body['returnUrl']).toBe(`${window.location.origin}/cart/payment-return/order-42`);
  });

  it('sends the customer\'s current language and a PAYMENT_LINK presentation', async () => {
    const { service, api } = setUp();
    api.mutate.mockResolvedValue({
      attemptId: 'a1',
      merchantTransId: 'm1',
      provider: 'PAYME',
      presentation: 'PAYMENT_LINK',
      checkoutUrl: null,
      qrPayload: 'payload',
      expiresAt: new Date().toISOString(),
      amountMinor: 5_000,
      currency: 'UZS',
      rePresented: false,
      presentationCount: 1,
    });

    await service.open('order-7');

    const body = api.mutate.mock.calls[0][2].body as Record<string, unknown>;
    expect(body['language']).toBe('ru');
    expect(body['presentation']).toBe('PAYMENT_LINK');
  });

  it('generates a fresh idempotency key per open() call', async () => {
    const { service, api } = setUp();
    api.mutate.mockResolvedValue({
      attemptId: 'a1',
      merchantTransId: 'm1',
      provider: 'CLICK',
      presentation: 'PAYMENT_LINK',
      checkoutUrl: null,
      qrPayload: null,
      expiresAt: new Date().toISOString(),
      amountMinor: 1,
      currency: 'UZS',
      rePresented: false,
      presentationCount: 1,
    });

    await service.open('order-1');
    await service.open('order-1');

    const key1 = api.mutate.mock.calls[0][2].idempotencyKey;
    const key2 = api.mutate.mock.calls[1][2].idempotencyKey;
    expect(key1).toBeTruthy();
    expect(key1).not.toBe(key2);
  });

  it('returns the platform response unchanged, including a null checkoutUrl for a push-only presentation', async () => {
    const { service, api } = setUp();
    const response = {
      attemptId: 'a1',
      merchantTransId: 'm1',
      provider: 'PAYME',
      presentation: 'QR',
      checkoutUrl: null,
      qrPayload: 'abc123',
      expiresAt: new Date().toISOString(),
      amountMinor: 5_000,
      currency: 'UZS',
      rePresented: true,
      presentationCount: 2,
    };
    api.mutate.mockResolvedValue(response);

    const result = await service.open('order-9');

    expect(result).toEqual(response);
  });
});
