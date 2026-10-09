import { TestBed } from '@angular/core/testing';

import { ApiClient } from '../api/api-client';
import { APP_CONFIG } from '../config/app-config';
import { AttributionLanding, refToken } from './attribution-landing';

class FakeApiClient {
  mutate = vi.fn().mockResolvedValue(undefined);
}

function setUp(api = new FakeApiClient()): { landing: AttributionLanding; api: FakeApiClient } {
  sessionStorage.clear();
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiClient, useValue: api },
      {
        provide: APP_CONFIG,
        useValue: { tenantId: 'tenant-1', brandId: 'brand-1', apiBaseUrl: '/api/v1' },
      },
    ],
  });
  return { landing: TestBed.inject(AttributionLanding), api };
}

describe('refToken', () => {
  it('reads a ref that has the shape of a token', () => {
    expect(refToken('?ref=abcd1234')).toBe('abcd1234');
    expect(refToken('?utm=x&ref=Ab_-9zZ')).toBe('Ab_-9zZ');
  });

  it('ignores a missing, short, long or oddly-shaped ref', () => {
    expect(refToken('')).toBeNull();
    expect(refToken('?ref=')).toBeNull();
    expect(refToken('?ref=abc')).toBeNull();
    expect(refToken('?ref=' + 'x'.repeat(65))).toBeNull();
    expect(refToken('?ref=has space')).toBeNull();
    expect(refToken('?ref=semi%3Bcolon')).toBeNull();
  });
});

describe('AttributionLanding', () => {
  it('counts one click on a ?ref= landing, anonymously, at the brand and token in the path', async () => {
    const { landing, api } = setUp();

    await landing.recordLanding('?ref=liveToken01');

    expect(api.mutate).toHaveBeenCalledTimes(1);
    expect(api.mutate).toHaveBeenCalledWith(
      'POST',
      '/storefront/tenants/tenant-1/brands/brand-1/attribution-links/liveToken01/clicks',
      { anonymous: true },
    );
  });

  it('sends nothing at all when there is no ref, so an ordinary visit is measured by nothing', async () => {
    const { landing, api } = setUp();

    await landing.recordLanding('');
    await landing.recordLanding('?other=value');

    expect(api.mutate).not.toHaveBeenCalled();
  });

  it('sends nothing for a ref that is not shaped like a token, so a crafted URL cannot make a request', async () => {
    const { landing, api } = setUp();

    await landing.recordLanding('?ref=../../admin');
    await landing.recordLanding('?ref=ab');

    expect(api.mutate).not.toHaveBeenCalled();
  });

  it('counts a reload of the same landing once, per tab session', async () => {
    const { landing, api } = setUp();

    await landing.recordLanding('?ref=liveToken01');
    await landing.recordLanding('?ref=liveToken01');

    expect(api.mutate).toHaveBeenCalledTimes(1);
  });

  it('counts a different token separately', async () => {
    const { landing, api } = setUp();

    await landing.recordLanding('?ref=liveToken01');
    await landing.recordLanding('?ref=otherToken2');

    expect(api.mutate).toHaveBeenCalledTimes(2);
  });

  it('swallows every failure: a refused or rate-limited counter never reaches the customer', async () => {
    const api = new FakeApiClient();
    api.mutate.mockRejectedValue(new Error('429'));
    const { landing } = setUp(api);

    await expect(landing.recordLanding('?ref=liveToken01')).resolves.toBeUndefined();
  });
});
