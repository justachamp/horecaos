import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { LiveOperators } from './live-operators';

const SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const BRAND_BAND = '/api/v1/operations/tenants/t1/brands/b1/orders/operators/today';
const LOCATION_BAND = '/api/v1/operations/tenants/t1/brands/b1/locations/l1/orders/operators/today';

const ROWS = [
  { operatorPrincipalId: 's1', displayName: 'Aziza Karimova', createdCount: 4, acceptedCount: 9 },
];

const denied = () =>
  throwError(() => new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null));
const broken = () => throwError(() => new ApiError('INTERNAL_ERROR', 500, null, 'corr-1'));

type Reply = () => Observable<{ readonly value: unknown; readonly version: number | null }>;

function ok(rows: unknown = ROWS): Reply {
  return () =>
    of({
      value: {
        rows,
        businessDayFrom: '2026-09-11T19:00:00Z',
        businessDayTo: '2026-09-12T19:00:00Z',
      },
      version: null,
    });
}

function configure(byPath: Readonly<Record<string, Reply>>) {
  const get = vi.fn().mockImplementation((path: string) => {
    const reply = byPath[path];
    if (!reply) {
      throw new Error(`unstubbed path: ${path}`);
    }
    return reply();
  });
  TestBed.configureTestingModule({ providers: [{ provide: ApiClient, useValue: { get } }] });
  return { operators: TestBed.inject(LiveOperators), get };
}

afterEach(() => vi.useRealTimers());

describe('LiveOperators', () => {
  it('reads the brand-wide band first and returns the rows the server sent, names included', async () => {
    const { operators, get } = configure({ [BRAND_BAND]: ok() });

    const band = await operators.load(SCOPE);

    expect(band).toEqual({ available: true, rows: ROWS });
    expect(get).toHaveBeenCalledTimes(1);
    expect(get).toHaveBeenCalledWith(BRAND_BAND);
  });

  it("falls back to the operator's own branch when the brand-wide band answers 403", async () => {
    const { operators, get } = configure({ [BRAND_BAND]: denied, [LOCATION_BAND]: ok() });

    const band = await operators.load(SCOPE);

    expect(band.available).toBe(true);
    expect(band.rows).toEqual(ROWS);
    expect(get.mock.calls.map((call) => call[0])).toEqual([BRAND_BAND, LOCATION_BAND]);
  });

  it('stops re-asking the brand route once it has answered 403, so the fallback costs one call a tick', async () => {
    const { operators, get } = configure({ [BRAND_BAND]: denied, [LOCATION_BAND]: ok() });

    await operators.load(SCOPE);
    await operators.load(SCOPE);
    await operators.load(SCOPE);

    expect(get.mock.calls.filter((call) => call[0] === BRAND_BAND)).toHaveLength(1);
    expect(get.mock.calls.filter((call) => call[0] === LOCATION_BAND)).toHaveLength(3);
  });

  it('tries the brand route again once the refusal is stale, so a grant widened mid-shift is not stuck', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-09-12T08:00:00Z'));
    const { operators, get } = configure({ [BRAND_BAND]: denied, [LOCATION_BAND]: ok() });

    await operators.load(SCOPE);
    vi.setSystemTime(new Date('2026-09-12T08:06:00Z'));
    await operators.load(SCOPE);

    expect(get.mock.calls.filter((call) => call[0] === BRAND_BAND)).toHaveLength(2);
  });

  it('reports the band unavailable, never an empty table, when even the branch route is refused', async () => {
    const { operators } = configure({ [BRAND_BAND]: denied, [LOCATION_BAND]: denied });

    expect(await operators.load(SCOPE)).toEqual({ available: false, rows: [] });
  });

  it('reports the band unavailable, and does not throw, on any other failure', async () => {
    const { operators } = configure({ [BRAND_BAND]: broken });

    expect(await operators.load(SCOPE)).toEqual({ available: false, rows: [] });
  });

  it('keeps an empty roster apart from an unreadable one', async () => {
    const { operators } = configure({ [BRAND_BAND]: ok([]) });

    expect(await operators.load(SCOPE)).toEqual({ available: true, rows: [] });
  });
});
