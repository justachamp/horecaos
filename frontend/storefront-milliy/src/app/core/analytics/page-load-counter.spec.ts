import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../api/api-client';
import { APP_CONFIG } from '../config/app-config';
import { PageLoadCounter } from './page-load-counter';

class FakeApiClient {
  get = vi.fn().mockResolvedValue({ enabled: false });
  report = vi.fn().mockResolvedValue(undefined);
}

function setUp(api = new FakeApiClient()): { counter: PageLoadCounter; api: FakeApiClient } {
  sessionStorage.clear();
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiClient, useValue: api },
      {
        provide: APP_CONFIG,
        useValue: {
          tenantId: 'tenant-1',
          brandId: 'brand-1',
          channel: 'WEB',
          apiBaseUrl: '/api/v1',
        },
      },
    ],
  });
  return { counter: TestBed.inject(PageLoadCounter), api };
}

const PATH = '/storefront/tenants/tenant-1/brands/brand-1/page-loads';

describe('PageLoadCounter (ADR 0183 D18)', () => {
  afterEach(() => sessionStorage.clear());

  it('asks whether the platform counts page loads, and sends nothing else while it does not', async () => {
    const { counter, api } = setUp();

    await counter.recordSession();

    expect(api.get).toHaveBeenCalledTimes(1);
    expect(api.get).toHaveBeenCalledWith(PATH, expect.objectContaining({ anonymous: true }));
    expect(api.report).not.toHaveBeenCalled();
  });

  it('sends one anonymous ping carrying only the channel code once the platform says it counts', async () => {
    const api = new FakeApiClient();
    api.get.mockResolvedValue({ enabled: true });
    const { counter } = setUp(api);

    await counter.recordSession();

    expect(api.report).toHaveBeenCalledTimes(1);
    expect(api.report).toHaveBeenCalledWith(PATH, { channel: 'WEB' });
  });

  it('asks once per tab session, however many times it is called or the page is reloaded', async () => {
    const api = new FakeApiClient();
    api.get.mockResolvedValue({ enabled: true });
    const { counter } = setUp(api);

    await counter.recordSession();
    await counter.recordSession();
    await counter.recordSession();

    expect(api.get).toHaveBeenCalledTimes(1);
    expect(api.report).toHaveBeenCalledTimes(1);
  });

  it('does not retry after a refusal: the decision is remembered before the call is made', async () => {
    const api = new FakeApiClient();
    api.get.mockRejectedValue(new Error('500'));
    const { counter } = setUp(api);

    await expect(counter.recordSession()).resolves.toBeUndefined();
    await counter.recordSession();

    expect(api.get).toHaveBeenCalledTimes(1);
    expect(api.report).not.toHaveBeenCalled();
  });

  it("swallows a rejected ping: a counter is never the customer's problem", async () => {
    const api = new FakeApiClient();
    api.get.mockResolvedValue({ enabled: true });
    api.report.mockRejectedValue(new Error('429'));
    const { counter } = setUp(api);

    await expect(counter.recordSession()).resolves.toBeUndefined();
  });
});
