import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { I18n } from '../core/i18n/i18n';
import { DeviceShell } from './device-shell';

const TOKEN_ENDPOINT = 'https://auth.example.uz/realms/horecaos/protocol/openid-connect/token';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/**
 * ADR 0151: a wall display holds exactly one capability, `kitchen.display.read`. The strongest way to
 * say that in a spec is against the wire, with the real session and the real API client: whatever the
 * wall asks the platform for, it asks through `fetch`, so every URL it ever requests is on this list.
 * A read added to wall mode later (the station list, the lateness policy, the order board, a stream)
 * fails here, by name, instead of failing quietly on a 403 in a kitchen and falling back to a default.
 */
describe('a wall display’s requests, against the real clients', () => {
  const requested: string[] = [];
  let eventSource: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    requested.length = 0;
    localStorage.clear();
    localStorage.setItem(
      'horecaos.kds.credential',
      JSON.stringify({
        tokenEndpoint: TOKEN_ENDPOINT,
        clientId: 'kds-device-abc',
        clientSecret: 'shh',
      }),
    );
    eventSource = vi.fn();
    vi.stubGlobal('EventSource', eventSource);
    vi.useFakeTimers();
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: string, init?: RequestInit) => {
        const url = String(input);
        requested.push(`${init?.method ?? 'GET'} ${url}`);
        if (url === TOKEN_ENDPOINT) {
          return jsonResponse({ access_token: 'device-token', expires_in: 300 });
        }
        if (url.endsWith('/api/v1/devices/me')) {
          return jsonResponse({
            deviceId: 'dev-vdu',
            deviceClass: 'KITCHEN_VDU',
            displayName: 'Grill TV',
            tenantId: 't1',
            brandId: 'b1',
            locationId: 'l1',
            locationName: 'Chilanzar',
            timezone: 'Asia/Tashkent',
            station: null,
          });
        }
        if (url.endsWith('/kitchen/vdu')) {
          return jsonResponse({
            tickets: [],
            lateness: {
              delivery: {
                atRiskBeforeSeconds: 300,
                lateAfterSeconds: 0,
                noPromiseFallbackSeconds: 2700,
              },
              pickup: {
                atRiskBeforeSeconds: 300,
                lateAfterSeconds: 0,
                noPromiseFallbackSeconds: 2700,
              },
              dineIn: {
                atRiskBeforeSeconds: 300,
                lateAfterSeconds: 0,
                noPromiseFallbackSeconds: 2700,
              },
              isPlatformDefault: true,
            },
          });
        }
        return jsonResponse({ unexpected: url }, 500);
      }),
    );
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('requests its own record and the projection and nothing else, and opens no stream', async () => {
    await TestBed.configureTestingModule({ imports: [DeviceShell] }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(DeviceShell);
    fixture.detectChanges();

    // Five minutes of a wall's life: thirty projection polls and a handful of re-reads of its record.
    await vi.advanceTimersByTimeAsync(5 * 60_000);
    fixture.detectChanges();

    const platformRequests = requested.filter((request) => !request.endsWith(TOKEN_ENDPOINT));
    const distinct = [...new Set(platformRequests)].map((request) =>
      request.replace(/^GET https?:\/\/[^/]+/, 'GET'),
    );
    expect(distinct.sort()).toEqual([
      'GET /api/v1/devices/me',
      'GET /api/v1/tenants/t1/brands/b1/locations/l1/kitchen/vdu',
    ]);
    expect(
      platformRequests.filter((request) => request.includes('/kitchen/vdu')).length,
    ).toBeGreaterThan(20);
    expect(eventSource).not.toHaveBeenCalled();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="device-wall"]'),
    ).not.toBeNull();
    fixture.destroy();
  });

  it('names no station in the projection request: the server decides which one a wall shows', async () => {
    await TestBed.configureTestingModule({ imports: [DeviceShell] }).compileComponents();
    const fixture = TestBed.createComponent(DeviceShell);
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(30_000);

    expect(
      requested
        .filter((request) => request.includes('/kitchen/vdu'))
        .every((request) => !request.includes('station')),
    ).toBe(true);
    fixture.destroy();
  });
});
