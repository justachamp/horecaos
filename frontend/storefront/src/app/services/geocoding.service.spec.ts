import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { PLATFORM_API_REQUEST, QUIET_FAILURE } from '../core/api/api-client';
import { APP_CONFIG, type AppConfig } from '../core/config/app-config';
import { GeocodingService } from './geocoding.service';

const CONFIG: AppConfig = {
  apiBaseUrl: '/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
  channel: 'STOREFRONT',
  brand: { displayName: 'Test Brand', theme: { accent: '#000000', accentDeep: '#000000' } },
};

const BASE =
  '/api/v1/storefront/tenants/10000000-0000-0000-0000-000000000001' +
  '/brands/10000000-0000-0000-0000-000000000002/geocode';

function setUp() {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: APP_CONFIG, useValue: CONFIG },
    ],
  });
  return { service: TestBed.inject(GeocodingService), http: TestBed.inject(HttpTestingController) };
}

/** The microtask hop between a call and its request reaching the testing backend. */
const tick = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

describe('GeocodingService (ADR 0145: the platform asks the geocoder, the browser does not)', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('suggests through the platform, with the text in the body and never in the URL, as the customer', async () => {
    const { service, http } = setUp();

    const pending = service.suggest('  amir temur 15 ');
    await tick();
    const request = http.expectOne(`${BASE}/suggestions`);

    expect(request.request.method).toBe('POST');
    expect(request.request.url).not.toContain('amir');
    expect(request.request.body).toEqual({ text: 'amir temur 15' });
    expect(request.request.headers.has('Idempotency-Key')).toBe(false);
    expect(request.request.context.get(PLATFORM_API_REQUEST)).toBe(true);
    expect(request.request.context.get(QUIET_FAILURE)).toBe(true);
    request.flush({
      status: 'ANSWERED',
      suggestions: [
        {
          title: 'Amir Temur',
          subtitle: 'Tashkent',
          fullText: 'Tashkent, Amir Temur 15',
          providerReference: 'x',
        },
      ],
    });

    expect(await pending).toEqual({
      available: true,
      items: [{ title: 'Amir Temur', subtitle: 'Tashkent', text: 'Tashkent, Amir Temur 15' }],
    });
  });

  it('asks nothing of the platform for fewer than two characters', async () => {
    const { service, http } = setUp();

    expect(await service.suggest(' a ')).toEqual({ available: true, items: [] });
    expect(await service.resolve('')).toEqual({ available: true, items: [] });
    http.expectNone(() => true);
  });

  it('resolves a chosen line to places with points, flagging the ones the platform flagged', async () => {
    const { service, http } = setUp();

    const pending = service.resolve('Tashkent, Amir Temur 15');
    await tick();
    const request = http.expectOne(`${BASE}/resolutions`);
    request.flush({
      status: 'ANSWERED',
      results: [
        {
          latitude: 41.3111,
          longitude: 69.2797,
          components: { formatted: 'Tashkent, Amir Temur 15' },
          confidence: 'HIGH',
        },
        {
          latitude: 42.87,
          longitude: 74.59,
          components: { formatted: 'Bishkek, Amir Temur 15' },
          confidence: 'LOW_CONFIDENCE',
        },
        { latitude: 41.2, longitude: 69.2, components: { formatted: '' }, confidence: 'HIGH' },
      ],
    });

    expect(await pending).toEqual({
      available: true,
      items: [
        { address: 'Tashkent, Amir Temur 15', lat: 41.3111, lng: 69.2797, lowConfidence: false },
        { address: 'Bishkek, Amir Temur 15', lat: 42.87, lng: 74.59, lowConfidence: true },
      ],
    });
  });

  it('names the address at a pin without swapping latitude and longitude', async () => {
    const { service, http } = setUp();

    const pending = service.describe(41.3111, 69.2797);
    await tick();
    const request = http.expectOne(`${BASE}/reverse-resolutions`);

    expect(request.request.body).toEqual({ point: { latitude: 41.3111, longitude: 69.2797 } });
    request.flush({
      status: 'ANSWERED',
      result: {
        latitude: 41.3111,
        longitude: 69.2797,
        components: { formatted: 'Navoi ko‘chasi 28' },
        confidence: 'HIGH',
      },
    });

    expect(await pending).toBe('Navoi ko‘chasi 28');
  });

  it('answers an empty string for a pin the platform knows nothing about', async () => {
    const { service, http } = setUp();

    const pending = service.describe(41.2, 69.1);
    await tick();
    http.expectOne(`${BASE}/reverse-resolutions`).flush({ status: 'ANSWERED', result: null });

    expect(await pending).toBe('');
  });

  it('reads the platform’s outage as unavailable, which is not the same as nothing found', async () => {
    const { service, http } = setUp();

    const pending = service.suggest('navoi');
    await tick();
    http.expectOne(`${BASE}/suggestions`).flush({
      status: 'UNAVAILABLE',
      reason: 'NOT_CONFIGURED',
      suggestions: [],
    });

    expect(await pending).toEqual({ available: false, items: [] });
  });

  it('reads a rate limit, a refusal and an unreachable platform the same way, without throwing', async () => {
    const { service, http } = setUp();

    for (const status of [429, 403, 500, 0]) {
      const pending = service.resolve('navoi');
      await tick();
      const request = http.expectOne(`${BASE}/resolutions`);
      if (status === 0) {
        request.error(new ProgressEvent('error'));
      } else {
        request.flush({ code: 'X' }, { status, statusText: 'x' });
      }
      expect(await pending, `status ${status}`).toEqual({ available: false, items: [] });
    }
  });

  it('never leaves the platform: every request goes to the platform’s own API base', async () => {
    const { service, http } = setUp();

    void service.suggest('navoi');
    void service.resolve('navoi');
    void service.describe(41.3, 69.2);
    await tick();
    const requests = http.match(() => true);

    expect(requests).toHaveLength(3);
    for (const request of requests) {
      expect(request.request.url.startsWith('/api/v1/storefront/')).toBe(true);
      expect(request.request.url).not.toMatch(/yandex|geocode-maps|api-maps/i);
      request.flush({ status: 'UNAVAILABLE' });
    }
  });
});
