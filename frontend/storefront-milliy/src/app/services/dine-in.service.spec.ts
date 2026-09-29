import { TestBed } from '@angular/core/testing';

import { ApiClient } from '../core/api/api-client';
import { DineInService, type DineInAdmission } from './dine-in.service';

/** The wire response: the admission plus the guest token this app must not keep. */
type AdmissionResponse = DineInAdmission & { guestToken: string };

class FakeApiClient {
  get = vi.fn();
  mutate = vi.fn();
}

function admission(overrides: Partial<AdmissionResponse> = {}): AdmissionResponse {
  return {
    guestToken: 'guest-token-1',
    expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
    mode: 'VIEW_ONLY',
    tenantId: 'tenant-1',
    brandId: 'brand-1',
    locationId: 'location-1',
    tableCode: 'T1',
    openSessionId: null,
    channelCode: 'QRTABLE',
    ...overrides,
  };
}

function setUp(): { service: DineInService; api: FakeApiClient } {
  const api = new FakeApiClient();
  TestBed.configureTestingModule({ providers: [{ provide: ApiClient, useValue: api }] });
  return { service: TestBed.inject(DineInService), api };
}

describe('DineInService (the table-scan half: ADR 0047)', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('posts the printed token unauthenticated and keeps the admission it gets back', async () => {
    const { service, api } = setUp();
    const response = admission();
    api.mutate.mockResolvedValue(response);

    const result = await service.exchange('printed-table-token');

    expect(api.mutate).toHaveBeenCalledWith(
      'POST',
      '/storefront/dine-in/qr/token-exchanges',
      expect.objectContaining({ body: { tableToken: 'printed-table-token' }, anonymous: true }),
    );
    const { guestToken: _token, ...kept } = response;
    expect(result).toEqual(kept);
    expect(service.admission()).toEqual(kept);
    expect(service.hasAdmission()).toBe(true);
  });

  it('does not keep the guest token -- nothing in this app uses it, so nothing should hold it', async () => {
    const { service, api } = setUp();
    api.mutate.mockResolvedValue(admission({ guestToken: 'secret-guest-token' }));

    const result = await service.exchange('printed-table-token');

    expect(result).not.toHaveProperty('guestToken');
    expect(service.admission()).not.toHaveProperty('guestToken');
    expect(JSON.stringify(localStorage)).not.toContain('secret-guest-token');
  });

  it('never puts the printed token anywhere it could outlive the one request that spends it', async () => {
    const { service, api } = setUp();
    api.mutate.mockResolvedValue(admission());

    await service.exchange('printed-table-token');

    expect(JSON.stringify(localStorage)).not.toContain('printed-table-token');
  });

  it('survives a reload: a fresh instance resumes from what was persisted', async () => {
    const { service, api } = setUp();
    api.mutate.mockResolvedValue(admission());
    await service.exchange('printed-table-token');

    TestBed.resetTestingModule();
    const { service: resumed } = setUp();

    expect(resumed.admission()?.tableCode).toBe('T1');
  });

  it('reports no admission once the stored deadline has passed, without asking the platform', () => {
    localStorage.setItem(
      'horecaos_dinein_admission',
      JSON.stringify(admission({ expiresAt: new Date(Date.now() - 1000).toISOString() })),
    );

    const { service, api } = setUp();

    expect(service.admission()).toBeNull();
    expect(service.hasAdmission()).toBe(false);
    expect(api.mutate).not.toHaveBeenCalled();
  });

  it('treats a corrupt stored value as nothing scanned, not as a crash', () => {
    localStorage.setItem('horecaos_dinein_admission', '{not json');

    const { service } = setUp();

    expect(service.admission()).toBeNull();
  });

  it('a refused scan leaves the previous table visit untouched', async () => {
    const { service, api } = setUp();
    api.mutate.mockResolvedValueOnce(admission({ tableCode: 'T1' }));
    await service.exchange('good-token');
    api.mutate.mockRejectedValueOnce(new Error('refused'));

    await expect(service.exchange('rotated-token')).rejects.toThrow('refused');

    expect(service.admission()?.tableCode).toBe('T1');
  });

  it('a second scan replaces the first -- there is exactly one table visit worth remembering', async () => {
    const { service, api } = setUp();
    api.mutate.mockResolvedValueOnce(admission({ tableCode: 'T1' }));
    api.mutate.mockResolvedValueOnce(admission({ tableCode: 'T2', guestToken: 'guest-token-2' }));

    await service.exchange('t1');
    await service.exchange('t2');

    expect(service.admission()?.tableCode).toBe('T2');
  });

  it('clear() forgets the visit in memory and in storage', async () => {
    const { service, api } = setUp();
    api.mutate.mockResolvedValue(admission());
    await service.exchange('printed-table-token');

    service.clear();

    expect(service.admission()).toBeNull();
    expect(localStorage.getItem('horecaos_dinein_admission')).toBeNull();
  });
});
