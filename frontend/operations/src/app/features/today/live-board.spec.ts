import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { LiveBoard } from './live-board';

const SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const BRAND_COUNTS = '/api/v1/operations/tenants/t1/brands/b1/orders/counts';
const LOCATION_COUNTS = '/api/v1/tenants/t1/brands/b1/locations/l1/orders/counts';
const ROSTER = '/api/v1/operations/tenants/t1/brands/b1/locations';

function counts(overrides: Record<string, number> = {}) {
  return {
    newOrders: 0,
    awaitingApproval: 0,
    inKitchen: 0,
    ready: 0,
    fulfilling: 0,
    completed: 0,
    cancelled: 0,
    totalNonTerminal: 0,
    total: 0,
    ...overrides,
  };
}

function brandBoard(
  overrides: Partial<{
    period: string;
    periodFrom: string | null;
    periodTo: string | null;
    totals: ReturnType<typeof counts>;
    locations: ReadonlyArray<{ locationId: string; counts: ReturnType<typeof counts> }>;
    sourceMix: ReadonlyArray<{ key: string; orders: number }>;
    typeMix: ReadonlyArray<{ key: string; orders: number }>;
  }> = {},
) {
  return {
    period: 'BUSINESS_DAY',
    periodFrom: '2026-09-11T19:00:00Z',
    periodTo: '2026-09-12T19:00:00Z',
    totals: counts(),
    locations: [],
    sourceMix: [],
    typeMix: [],
    ...overrides,
  };
}

function locationBoard(overrides: Record<string, unknown> = {}) {
  return {
    ...counts(),
    period: 'BUSINESS_DAY',
    periodFrom: '2026-09-11T19:00:00Z',
    periodTo: '2026-09-12T19:00:00Z',
    sourceMix: [],
    typeMix: [],
    ...overrides,
  };
}

const denied = () =>
  throwError(() => new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null));
const broken = () => throwError(() => new ApiError('INTERNAL_ERROR', 500, null, 'corr-1'));

function ok<T>(value: T) {
  return () => of({ value, version: null });
}

type StubbedGet = () => Observable<{ readonly value: unknown; readonly version: number | null }>;

/**
 * Routes a stubbed `ApiClient.get` by the first matching path substring.
 * **Order the entries most-specific first**: `.find` returns the first match,
 * and the location counts path nests inside `/locations`.
 *
 * Returns the spy as well as the service, because several assertions below are
 * about *how many* calls were made rather than what came back — the whole point
 * of this wave.
 */
function configure(byPath: ReadonlyArray<{ match: string; response: StubbedGet }>): {
  board: LiveBoard;
  get: ReturnType<typeof vi.fn>;
} {
  const get = vi.fn().mockImplementation((path: string) => {
    const route = byPath.find((candidate) => path.includes(candidate.match));
    if (!route) {
      throw new Error(`unstubbed path: ${path}`);
    }
    return route.response();
  });
  TestBed.configureTestingModule({ providers: [{ provide: ApiClient, useValue: { get } }] });
  return { board: TestBed.inject(LiveBoard), get };
}

describe('LiveBoard: one request per tick', () => {
  it('reads the whole board — counters, mixes and every branch — in a single brand-scoped call', async () => {
    const { board, get } = configure([
      {
        match: BRAND_COUNTS,
        response: ok(
          brandBoard({
            totals: counts({ totalNonTerminal: 12, cancelled: 3 }),
            locations: [
              { locationId: 'l1', counts: counts({ totalNonTerminal: 3 }) },
              { locationId: 'l2', counts: counts({ totalNonTerminal: 9 }) },
            ],
          }),
        ),
      },
      {
        match: ROSTER,
        response: ok([
          { id: 'l1', displayName: 'Чиланзар', status: 'ACTIVE' },
          { id: 'l2', displayName: 'Юнусабад', status: 'ACTIVE' },
        ]),
      },
    ]);

    const snapshot = await board.load(SCOPE);

    expect(snapshot.counts.totalNonTerminal).toBe(12);
    expect(snapshot.branches).toEqual([
      { locationId: 'l2', displayName: 'Юнусабад', inProgress: 9 },
      { locationId: 'l1', displayName: 'Чиланзар', inProgress: 3 },
    ]);
    expect(get).toHaveBeenCalledTimes(2); // the board, and the roster's one-off read
  });

  it('costs one request on every tick after the first, however many branches there are', async () => {
    const { board, get } = configure([
      {
        match: BRAND_COUNTS,
        response: ok(
          brandBoard({
            locations: Array.from({ length: 10 }, (_, i) => ({
              locationId: `l${i}`,
              counts: counts({ totalNonTerminal: i }),
            })),
          }),
        ),
      },
      {
        match: ROSTER,
        response: ok(
          Array.from({ length: 10 }, (_, i) => ({
            id: `l${i}`,
            displayName: `Branch ${i}`,
            status: 'ACTIVE',
          })),
        ),
      },
    ]);

    await board.load(SCOPE);
    get.mockClear();

    const snapshot = await board.load(SCOPE);

    expect(get).toHaveBeenCalledTimes(1);
    expect(get.mock.calls[0][0]).toBe(BRAND_COUNTS);
    expect(snapshot.branches.length).toBe(10);
  });

  it('asks for the business day, never for the lifetime total', async () => {
    const { board, get } = configure([
      { match: BRAND_COUNTS, response: ok(brandBoard()) },
      { match: ROSTER, response: ok([]) },
    ]);

    await board.load(SCOPE);

    expect(get).toHaveBeenCalledWith(BRAND_COUNTS, { params: { period: 'BUSINESS_DAY' } });
  });

  it('stops re-asking the brand endpoint once it has answered 403, so the fallback also costs one call', async () => {
    const { board, get } = configure([
      { match: BRAND_COUNTS, response: denied },
      { match: LOCATION_COUNTS, response: ok(locationBoard({ totalNonTerminal: 4 })) },
      {
        match: '/locations/l1',
        response: ok({ id: 'l1', displayName: 'Own branch', status: 'ACTIVE' }),
      },
      { match: ROSTER, response: denied },
    ]);

    const first = await board.load(SCOPE);
    expect(first.counts.totalNonTerminal).toBe(4);

    get.mockClear();
    await board.load(SCOPE);

    expect(get).toHaveBeenCalledTimes(1);
    expect(get.mock.calls[0][0]).toBe(LOCATION_COUNTS);
  });

  it('retries the brand read once the refusal is stale, so a grant widened while the board is open is not stuck behind a reload', async () => {
    vi.useFakeTimers();
    try {
      let brandAttempts = 0;
      const { board, get } = configure([
        {
          match: BRAND_COUNTS,
          response: () => {
            const first = brandAttempts === 0;
            brandAttempts += 1;
            return first ? denied() : ok(brandBoard({ totals: counts({ totalNonTerminal: 9 }) }))();
          },
        },
        { match: LOCATION_COUNTS, response: ok(locationBoard({ totalNonTerminal: 4 })) },
        {
          match: '/locations/l1',
          response: ok({ id: 'l1', displayName: 'Own branch', status: 'ACTIVE' }),
        },
        { match: ROSTER, response: denied },
      ]);

      const first = await board.load(SCOPE);
      expect(first.counts.totalNonTerminal).toBe(4); // 403 on the brand read, fell back to the location one

      // Still well inside the TTL: the refusal is trusted and the brand
      // endpoint is not asked again.
      get.mockClear();
      await vi.advanceTimersByTimeAsync(4 * 60 * 1000);
      const stillRefused = await board.load(SCOPE);
      expect(get.mock.calls.map((call) => call[0])).not.toContain(BRAND_COUNTS);
      expect(stillRefused.counts.totalNonTerminal).toBe(4);

      // Past the TTL: the widened grant (or a lapsed misconfiguration) gets a
      // real chance to answer instead of being silently skipped forever.
      get.mockClear();
      await vi.advanceTimersByTimeAsync(2 * 60 * 1000);
      const recovered = await board.load(SCOPE);
      expect(get.mock.calls[0][0]).toBe(BRAND_COUNTS);
      expect(recovered.counts.totalNonTerminal).toBe(9);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('LiveBoard: the counters and the period', () => {
  it('carries the period the server cut the historical counters to', async () => {
    const { board } = configure([
      {
        match: BRAND_COUNTS,
        response: ok(brandBoard({ totals: counts({ cancelled: 3 }) })),
      },
      { match: ROSTER, response: ok([]) },
    ]);

    const snapshot = await board.load(SCOPE);

    expect(snapshot.period).toBe('BUSINESS_DAY');
    expect(snapshot.periodFrom).toBe('2026-09-11T19:00:00Z');
    expect(snapshot.counts.cancelled).toBe(3);
  });
});

describe('LiveBoard: the mixes', () => {
  it("takes both mixes from the server's exact aggregate, never from a page of orders", async () => {
    const { board, get } = configure([
      {
        match: BRAND_COUNTS,
        response: ok(
          brandBoard({
            totals: counts({ totalNonTerminal: 305 }),
            sourceMix: [
              { key: 'TELEGRAM_BOT', orders: 205 },
              { key: 'WEBSITE', orders: 100 },
            ],
            typeMix: [{ key: 'DELIVERY', orders: 305 }],
          }),
        ),
      },
      { match: ROSTER, response: ok([]) },
    ]);

    const snapshot = await board.load(SCOPE);

    // Above the MIX_FETCH_LIMIT = 200 page this screen used to count, and exact:
    // the bars sum to the counter beside them, which is what truncation broke.
    expect(snapshot.sourceMix).toEqual([
      { key: 'TELEGRAM_BOT', count: 205 },
      { key: 'WEBSITE', count: 100 },
    ]);
    expect(snapshot.sourceMix.reduce((sum, slice) => sum + slice.count, 0)).toBe(
      snapshot.counts.totalNonTerminal,
    );
    expect(snapshot.typeMix).toEqual([{ key: 'DELIVERY', count: 305 }]);
    expect(get.mock.calls.map((call) => call[0])).not.toContain(
      '/api/v1/tenants/t1/brands/b1/locations/l1/orders',
    );
  });

  it('preserves the aggregate order rather than re-sorting, so largest-first has one definition', async () => {
    const { board } = configure([
      {
        match: BRAND_COUNTS,
        response: ok(
          brandBoard({
            sourceMix: [
              { key: 'B', orders: 5 },
              { key: 'A', orders: 5 },
            ],
          }),
        ),
      },
      { match: ROSTER, response: ok([]) },
    ]);

    const snapshot = await board.load(SCOPE);

    expect(snapshot.sourceMix.map((slice) => slice.key)).toEqual(['B', 'A']);
  });
});

describe('LiveBoard: the branch band', () => {
  it('renders an active branch with no orders today as zero, not as a missing row', async () => {
    const { board } = configure([
      {
        match: BRAND_COUNTS,
        response: ok(
          brandBoard({
            locations: [{ locationId: 'l1', counts: counts({ totalNonTerminal: 3 }) }],
          }),
        ),
      },
      {
        match: ROSTER,
        response: ok([
          { id: 'l1', displayName: 'Чиланзар', status: 'ACTIVE' },
          { id: 'l2', displayName: 'Тихий', status: 'ACTIVE' },
          { id: 'l3', displayName: 'Закрытый', status: 'ARCHIVED' },
        ]),
      },
    ]);

    const snapshot = await board.load(SCOPE);

    expect(snapshot.branches).toEqual([
      { locationId: 'l1', displayName: 'Чиланзар', inProgress: 3 },
      { locationId: 'l2', displayName: 'Тихий', inProgress: 0 },
    ]);
    expect(snapshot.branchesTotal).toBe(2); // the archived branch never enters the roster
    expect(snapshot.branchesShown).toBe(2);
  });

  it('shows «N из M» when the operator can name every branch but read only their own', async () => {
    const { board } = configure([
      { match: BRAND_COUNTS, response: denied },
      { match: LOCATION_COUNTS, response: ok(locationBoard({ totalNonTerminal: 4 })) },
      {
        match: ROSTER,
        response: ok([
          { id: 'l1', displayName: 'Own branch', status: 'ACTIVE' },
          { id: 'l2', displayName: 'Sibling branch', status: 'ACTIVE' },
        ]),
      },
    ]);

    const snapshot = await board.load(SCOPE);

    expect(snapshot.branches).toEqual([
      { locationId: 'l1', displayName: 'Own branch', inProgress: 4 },
    ]);
    expect(snapshot.branchesShown).toBe(1);
    expect(snapshot.branchesTotal)
      // Not 1: a sibling branch whose load this operator cannot read is still a
      // branch, and nine rows reading zero would say the brand was idle.
      .toBe(2);
  });

  it("falls back to the operator's own single branch when the brand roster is denied too", async () => {
    const { board } = configure([
      { match: BRAND_COUNTS, response: denied },
      { match: LOCATION_COUNTS, response: ok(locationBoard({ totalNonTerminal: 4 })) },
      {
        match: '/locations/l1',
        response: ok({ id: 'l1', displayName: 'Own branch', status: 'ACTIVE' }),
      },
      { match: ROSTER, response: denied },
    ]);

    const snapshot = await board.load(SCOPE);

    expect(snapshot.branchesAvailable).toBe(true);
    expect(snapshot.branchesShown).toBe(1);
    expect(snapshot.branchesTotal).toBe(1);
    expect(snapshot.branches).toEqual([
      { locationId: 'l1', displayName: 'Own branch', inProgress: 4 },
    ]);
  });

  it('reports branchesAvailable: false, not an empty leaderboard, when even the fallback read fails', async () => {
    const { board } = configure([
      { match: BRAND_COUNTS, response: ok(brandBoard()) },
      { match: ROSTER, response: broken },
    ]);

    const snapshot = await board.load(SCOPE);

    expect(snapshot.branchesAvailable).toBe(false);
    expect(snapshot.branches).toEqual([]);
  });

  it('retries a roster that failed rather than holding the failure for the session', async () => {
    let rosterAttempts = 0;
    const { board } = configure([
      { match: BRAND_COUNTS, response: ok(brandBoard()) },
      {
        match: ROSTER,
        response: () => {
          rosterAttempts += 1;
          return rosterAttempts === 1
            ? broken()
            : of({
                value: [{ id: 'l1', displayName: 'Recovered', status: 'ACTIVE' }],
                version: null,
              });
        },
      },
    ]);

    expect((await board.load(SCOPE)).branchesAvailable).toBe(false);
    expect((await board.load(SCOPE)).branches).toEqual([
      { locationId: 'l1', displayName: 'Recovered', inProgress: 0 },
    ]);
  });

  it('lets a non-403 failure of the board itself surface, rather than silently degrading', async () => {
    const { board } = configure([
      { match: BRAND_COUNTS, response: broken },
      { match: ROSTER, response: ok([]) },
    ]);

    await expect(board.load(SCOPE)).rejects.toBeInstanceOf(ApiError);
  });
});
