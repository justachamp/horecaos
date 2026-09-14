import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { DeviceAuthError, DeviceSession } from './device-session';

const CREDENTIAL_KEY = 'horecaos.kds.credential';
const SETUP_KEY = 'horecaos.kds.setup';

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
