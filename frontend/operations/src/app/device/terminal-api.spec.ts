import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../core/api/operations-paths';
import { DeviceSession } from './device-session';
import { DeviceTerminalApi, TerminalError } from './terminal-api';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const BASE = '/api/v1/tenants/t1/brands/b1/locations/l1/terminal-session';

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('DeviceTerminalApi (ADR 0155)', () => {
  let api: DeviceTerminalApi;
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    TestBed.configureTestingModule({
      providers: [
        {
          provide: DeviceSession,
          useValue: { accessToken: () => Promise.resolve('device-token') },
        },
      ],
    });
    api = TestBed.inject(DeviceTerminalApi);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    TestBed.resetTestingModule();
  });

  function lastCall(): { url: string; init: RequestInit; headers: Record<string, string> } {
    const [url, init] = fetchMock.mock.calls.at(-1) as [string, RequestInit];
    return { url, init, headers: init.headers as Record<string, string> };
  }

  it('reads the roster with only the device’s own bearer and its build label', async () => {
    fetchMock.mockResolvedValue(
      json({
        attributionMode: 'PERSON_OPTIONAL',
        idleSeconds: 300,
        sessionMaxHours: 12,
        pinLength: 6,
        members: [],
      }),
    );

    await api.roster(SCOPE);

    const call = lastCall();
    expect(call.url).toBe(`${BASE}/roster`);
    expect(call.headers['Authorization']).toBe('Bearer device-token');
    expect(call.headers['X-Device-Build']).toBeTruthy();
    expect(call.headers['X-Terminal-Session']).toBeUndefined();
  });

  it('sends a PIN in a body and nowhere else, with no idempotency key', async () => {
    fetchMock.mockResolvedValue(
      json({
        sessionToken: 'tok',
        member: { memberId: 'm1', label: 'Aziza K.', pinState: 'ACTIVE' },
        idleSeconds: 300,
        hardExpiresAt: '2026-10-08T20:00:00Z',
      }),
    );

    await api.unlock(SCOPE, 'm1', '482915');

    const call = lastCall();
    expect(call.url).toBe(`${BASE}/unlocks`);
    expect(call.url).not.toContain('482915');
    expect(JSON.parse(call.init.body as string)).toEqual({ memberId: 'm1', pin: '482915' });
    expect(Object.keys(call.headers).join(' ')).not.toContain('482915');
    expect(call.headers['Idempotency-Key']).toBeUndefined();
  });

  it('presents the session token as X-Terminal-Session on a PIN change, a touch and a lock', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await api.changePin(SCOPE, 'tok', '482915', '735914');
    expect(lastCall().url).toBe(`${BASE}/pin-changes`);
    expect(lastCall().headers['X-Terminal-Session']).toBe('tok');

    await api.touch(SCOPE, 'tok');
    expect(lastCall().url).toBe(`${BASE}/touch`);
    expect(lastCall().headers['X-Terminal-Session']).toBe('tok');

    await api.lock(SCOPE, 'tok');
    expect(lastCall().url).toBe(`${BASE}/locks`);
    expect(lastCall().headers['X-Terminal-Session']).toBe('tok');
  });

  it('reads the code, the time a lock lifts and the PIN rule from a refusal', async () => {
    fetchMock.mockResolvedValueOnce(
      json({ code: 'TERMINAL_PIN_LOCKED', retryAfterSeconds: 840, status: 423 }, 423),
    );
    await expect(api.unlock(SCOPE, 'm1', '000000')).rejects.toMatchObject({
      status: 423,
      code: 'TERMINAL_PIN_LOCKED',
      retryAfterSeconds: 840,
    });

    fetchMock.mockResolvedValueOnce(
      json({ code: 'TERMINAL_ACTIVATION_REFUSED', reason: 'SEQUENCE', status: 422 }, 422),
    );
    const error = (await api
      .activate(SCOPE, 'm1', '12345678', '123456')
      .catch((e: unknown) => e)) as TerminalError;
    expect(error).toBeInstanceOf(TerminalError);
    expect(error.reason).toBe('SEQUENCE');
    expect(error.message).not.toContain('123456');
  });

  it('survives a refusal with no readable body', async () => {
    fetchMock.mockResolvedValue(new Response('gateway', { status: 502 }));

    await expect(api.roster(SCOPE)).rejects.toMatchObject({ status: 502, code: null });
  });
});
