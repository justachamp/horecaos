import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { StaffTokenStore } from '../core/auth/staff-token-store';
import { LocationScope } from '../core/api/operations-paths';
import { DeviceBoardApi } from './device-board-api';
import { DeviceSession } from './device-session';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/**
 * Row `X/X.2`'s own named test: the device-token auth path rejecting a
 * staff session. A kitchen KDS and a manager's own browser tab can share one
 * profile — a shared till, a manager's own laptop used once to approve a
 * device — so it is not enough that `device/` *usually* uses its own token;
 * it must never fall back to, be overridden by, or even consult a live
 * staff session sitting in the very same injector.
 */
describe('DeviceBoardApi — the device-token auth path', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  function seedEnrolledDevice(): void {
    localStorage.setItem(
      'horecaos.kds.credential',
      JSON.stringify({
        tokenEndpoint: 'https://auth.example.uz/realms/horecaos/protocol/openid-connect/token',
        clientId: 'kds-device-abc',
        clientSecret: 'device-secret',
      }),
    );
  }

  /** A live staff session in the very same injector — the scenario this test exists for. */
  function seedActiveStaffSession(): StaffTokenStore {
    const staffTokens = new StaffTokenStore();
    staffTokens.set('staff-access-token-XYZ', 'staff-refresh-token-XYZ');
    return staffTokens;
  }

  it('reads the branch board with only the device bearer, never the staff session’s, even though one is live', async () => {
    seedEnrolledDevice();
    const staffTokens = seedActiveStaffSession();

    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ access_token: 'device-access-token', expires_in: 300 }))
      .mockResolvedValueOnce(jsonResponse({ tickets: [], warnings: [] }));
    vi.stubGlobal('fetch', fetchMock);

    TestBed.configureTestingModule({
      providers: [{ provide: StaffTokenStore, useValue: staffTokens }],
    });
    const boardApi = TestBed.inject(DeviceBoardApi);

    await boardApi.board(SCOPE);

    expect(fetchMock).toHaveBeenCalledTimes(2);

    // Second call is the board read — assert its Authorization header directly.
    const [boardUrl, boardInit] = fetchMock.mock.calls[1];
    expect(boardUrl).toContain('/tenants/t1/brands/b1/locations/l1/kitchen/tickets');
    const headers = boardInit.headers as Record<string, string>;
    expect(headers['Authorization']).toBe('Bearer device-access-token');
    expect(headers['Authorization']).not.toBe(`Bearer ${staffTokens.accessToken()}`);
    expect(headers['Authorization']).not.toContain('staff-access-token-XYZ');
  });

  it('marks a line ready with only the device bearer and an Idempotency-Key, never the staff session', async () => {
    seedEnrolledDevice();
    const staffTokens = seedActiveStaffSession();

    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ access_token: 'device-access-token', expires_in: 300 }))
      .mockResolvedValueOnce(
        jsonResponse({
          applied: true,
          item: {
            itemId: 'item-1',
            orderLineId: 'l1',
            stationId: 's1',
            quantity: 1,
            routedBy: 'FALLBACK',
            status: 'READY',
            version: 2,
          },
          ticketStatus: 'READY',
          ticketVersion: 3,
        }),
      );
    vi.stubGlobal('fetch', fetchMock);

    TestBed.configureTestingModule({
      providers: [{ provide: StaffTokenStore, useValue: staffTokens }],
    });
    const boardApi = TestBed.inject(DeviceBoardApi);

    await boardApi.ready(SCOPE, 'item-1');

    const [readyUrl, readyInit] = fetchMock.mock.calls[1];
    expect(readyUrl).toContain('/kitchen/ticket-items/item-1/ready');
    const headers = readyInit.headers as Record<string, string>;
    expect(headers['Authorization']).toBe('Bearer device-access-token');
    expect(headers['Authorization']).not.toContain('staff-access-token-XYZ');
    expect(headers['Idempotency-Key']).toBeTruthy();
  });

  it('mints its own token even when DeviceSession is constructed alongside a StaffTokenStore that holds a token — no fallback path exists between them', async () => {
    seedEnrolledDevice();
    seedActiveStaffSession();

    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({ access_token: 'device-access-token', expires_in: 300 }),
      );
    vi.stubGlobal('fetch', fetchMock);

    const session = TestBed.inject(DeviceSession);
    const token = await session.accessToken();

    // The device session never asked Keycloak for the staff token, and never
    // read StaffTokenStore at all — its own mint is the only source.
    expect(token).toBe('device-access-token');
    expect(token).not.toBe('staff-access-token-XYZ');
  });
});

/**
 * ADR 0151: a wall display holds one capability, so the two reads it makes are the whole of what it may
 * ask: its own record, and the projection. Both go with the device's bearer and nothing else, and
 * neither names a station: the server holds it.
 */
describe('DeviceBoardApi — the wall display’s two reads', () => {
  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem(
      'horecaos.kds.credential',
      JSON.stringify({
        tokenEndpoint: 'https://auth.example.uz/realms/horecaos/protocol/openid-connect/token',
        clientId: 'kds-device-abc',
        clientSecret: 'device-secret',
      }),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('asks who it is at /api/v1/devices/me with the device bearer, and parses the record', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ access_token: 'device-access-token', expires_in: 300 }))
      .mockResolvedValueOnce(
        jsonResponse({
          deviceId: 'd1',
          deviceClass: 'KITCHEN_VDU',
          displayName: 'Grill TV',
          tenantId: 't1',
          brandId: 'b1',
          locationId: 'l1',
          locationName: 'Chilanzar',
          timezone: 'Asia/Tashkent',
          station: null,
        }),
      );
    vi.stubGlobal('fetch', fetchMock);

    const profile = await TestBed.inject(DeviceBoardApi).me();

    const [url, init] = fetchMock.mock.calls[1];
    expect(url).toContain('/api/v1/devices/me');
    expect((init.headers as Record<string, string>)['Authorization']).toBe(
      'Bearer device-access-token',
    );
    expect(init.method).toBe('GET');
    expect(profile.deviceClass).toBe('KITCHEN_VDU');
    expect(profile.timezone).toBe('Asia/Tashkent');
  });

  it('refuses an answer that is not the record rather than starting a device from it', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(jsonResponse({ access_token: 't', expires_in: 300 }))
        .mockResolvedValueOnce(jsonResponse({ nonsense: true })),
    );

    await expect(TestBed.inject(DeviceBoardApi).me()).rejects.toThrow(/not understood/);
  });

  it('reads the projection without naming a station: a wall’s station is the server’s to apply', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ access_token: 't', expires_in: 300 }))
      .mockResolvedValueOnce(jsonResponse({ tickets: [] }));
    vi.stubGlobal('fetch', fetchMock);

    await TestBed.inject(DeviceBoardApi).vdu(SCOPE);

    const [url, init] = fetchMock.mock.calls[1];
    expect(url).toContain('/tenants/t1/brands/b1/locations/l1/kitchen/vdu');
    expect(String(url)).not.toContain('station=');
    expect(init.method).toBe('GET');
  });
});
