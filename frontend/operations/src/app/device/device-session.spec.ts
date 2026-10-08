import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { DeviceAuthError, DeviceSession } from './device-session';

const CREDENTIAL_KEY = 'horecaos.kds.credential';
const SETUP_KEY = 'horecaos.kds.setup';
const PROFILE_KEY = 'horecaos.kds.profile';

function tokenResponse(accessToken: string, expiresIn = 300): Response {
  return new Response(JSON.stringify({ access_token: accessToken, expires_in: expiresIn }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('DeviceSession', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({});
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('persists setup locally and reflects it as isSetUp', () => {
    const session = TestBed.inject(DeviceSession);
    expect(session.isSetUp()).toBe(false);

    session.saveSetup({ tenantId: 't1', brandId: 'b1', locationId: 'l1' });

    expect(session.isSetUp()).toBe(true);
    expect(session.setup()).toEqual({ tenantId: 't1', brandId: 'b1', locationId: 'l1' });
    expect(JSON.parse(localStorage.getItem(SETUP_KEY) ?? 'null')).toEqual({
      tenantId: 't1',
      brandId: 'b1',
      locationId: 'l1',
    });
  });

  // ------------------------------------------------ ADR 0151: the server says what this device is

  const WALL_PROFILE = {
    deviceId: 'dev-1',
    deviceClass: 'KITCHEN_VDU' as const,
    displayName: 'Grill TV',
    tenantId: 't1',
    brandId: 'b1',
    locationId: 'l1',
    locationName: 'Chilanzar',
    timezone: 'Asia/Tashkent',
    station: null,
  };

  it('keeps the record the server gave, and makes its branch the branch every later read uses', () => {
    const session = TestBed.inject(DeviceSession);
    session.saveSetup({ tenantId: 'typed', brandId: 'typed', locationId: 'typed' });

    session.saveProfile(WALL_PROFILE);

    expect(session.profile()?.deviceClass).toBe('KITCHEN_VDU');
    expect(session.setup()).toEqual({ tenantId: 't1', brandId: 'b1', locationId: 'l1' });
    expect(JSON.parse(localStorage.getItem(PROFILE_KEY) ?? 'null').timezone).toBe('Asia/Tashkent');
  });

  it('survives a reload: a restarted wall still knows what it is before the server has answered', () => {
    localStorage.setItem(PROFILE_KEY, JSON.stringify(WALL_PROFILE));

    expect(TestBed.inject(DeviceSession).profile()?.locationName).toBe('Chilanzar');
  });

  it('forgets the record with the credential: a revoked device is nothing, whatever it was', () => {
    const session = TestBed.inject(DeviceSession);
    session.saveProfile(WALL_PROFILE);

    session.forgetCredential();

    expect(session.profile()).toBeNull();
    expect(localStorage.getItem(PROFILE_KEY)).toBeNull();
  });

  it('begins an enrolment as the class the installer chose, a touch board by default', async () => {
    const fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve(
        new Response(
          JSON.stringify({
            deviceCode: 'c',
            userCode: 'u',
            expiresAt: 'x',
            pollIntervalSeconds: 4,
          }),
          { status: 200 },
        ),
      ),
    );
    vi.stubGlobal('fetch', fetchMock);
    const session = TestBed.inject(DeviceSession);

    await session.beginEnrolment(null, 'KITCHEN_VDU');
    await session.beginEnrolment(null);

    expect(JSON.parse(fetchMock.mock.calls[0][1].body as string).deviceClass).toBe('KITCHEN_VDU');
    expect(JSON.parse(fetchMock.mock.calls[1][1].body as string).deviceClass).toBe('KITCHEN_KDS');
  });

  it('survives a reload: a fresh injector reads the same locally stored setup and credential', () => {
    localStorage.setItem(
      SETUP_KEY,
      JSON.stringify({ tenantId: 't1', brandId: 'b1', locationId: 'l1' }),
    );
    localStorage.setItem(
      CREDENTIAL_KEY,
      JSON.stringify({
        tokenEndpoint: 'https://auth.example.uz/realms/horecaos/protocol/openid-connect/token',
        clientId: 'kds-device-abc',
        clientSecret: 'shh',
      }),
    );

    const session = TestBed.inject(DeviceSession);

    expect(session.isSetUp()).toBe(true);
    expect(session.isEnrolled()).toBe(true);
  });

  it('mints an access token via client_credentials straight against Keycloak’s own tokenEndpoint, not the platform backend', async () => {
    localStorage.setItem(
      CREDENTIAL_KEY,
      JSON.stringify({
        tokenEndpoint: 'https://auth.example.uz/realms/horecaos/protocol/openid-connect/token',
        clientId: 'kds-device-abc',
        clientSecret: 'shh',
      }),
    );
    const fetchMock = vi.fn().mockResolvedValue(tokenResponse('device-access-token'));
    vi.stubGlobal('fetch', fetchMock);

    const session = TestBed.inject(DeviceSession);
    const token = await session.accessToken();

    expect(token).toBe('device-access-token');
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('https://auth.example.uz/realms/horecaos/protocol/openid-connect/token');
    expect(init.method).toBe('POST');
    const body = new URLSearchParams(init.body as string);
    expect(body.get('grant_type')).toBe('client_credentials');
    expect(body.get('client_id')).toBe('kds-device-abc');
    expect(body.get('client_secret')).toBe('shh');
  });

  it('caches a minted token rather than minting one on every call', async () => {
    localStorage.setItem(
      CREDENTIAL_KEY,
      JSON.stringify({
        tokenEndpoint: 'https://auth.example.uz/token',
        clientId: 'c',
        clientSecret: 's',
      }),
    );
    const fetchMock = vi.fn().mockResolvedValue(tokenResponse('device-access-token', 300));
    vi.stubGlobal('fetch', fetchMock);

    const session = TestBed.inject(DeviceSession);
    await session.accessToken();
    await session.accessToken();
    await session.accessToken();

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('throws rather than minting when the device holds no credential yet', async () => {
    const session = TestBed.inject(DeviceSession);
    await expect(session.accessToken()).rejects.toThrow(DeviceAuthError);
  });

  it('forgets the credential locally when Keycloak refuses the client (401/403) — the disabled-client half of an ADR 0079 revoke', async () => {
    localStorage.setItem(
      CREDENTIAL_KEY,
      JSON.stringify({
        tokenEndpoint: 'https://auth.example.uz/token',
        clientId: 'c',
        clientSecret: 's',
      }),
    );
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 401 })));

    const session = TestBed.inject(DeviceSession);
    expect(session.isEnrolled()).toBe(true);

    await expect(session.accessToken()).rejects.toThrow(DeviceAuthError);

    expect(session.isEnrolled()).toBe(false);
    expect(localStorage.getItem(CREDENTIAL_KEY)).toBeNull();
  });

  it('stores the credential the first time a poll observes APPROVED with one, and never again re-requests it', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            status: 'APPROVED',
            credential: {
              tokenEndpoint: 'https://auth.example.uz/token',
              clientId: 'c',
              clientSecret: 's',
            },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    );

    const session = TestBed.inject(DeviceSession);
    expect(session.isEnrolled()).toBe(false);

    const result = await session.pollOnce('device-code-1');

    expect(result.status).toBe('APPROVED');
    expect(session.isEnrolled()).toBe(true);
    expect(session.credential()).toEqual({
      tokenEndpoint: 'https://auth.example.uz/token',
      clientId: 'c',
      clientSecret: 's',
    });
  });
});
