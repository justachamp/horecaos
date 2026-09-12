import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { settingsPaths } from '../../core/api/settings-paths';
import { OrderCountsResponse } from '../orders/order-detail';

/**
 * Which stretch of time the three historical counters answer for — the
 * server's `OrderCountsPeriod`, and the only two values it has.
 *
 * The board always asks for `BUSINESS_DAY`: «сколько отменили сегодня» is the
 * question it is on the wall to answer, and the boundary is the tenant's own
 * (ADR 0043), not UTC midnight — a branch that closes at 02:00 files those
 * orders under the evening it served them.
 */
export type CountsPeriod = 'ALL_TIME' | 'BUSINESS_DAY';

const BOARD_PERIOD: CountsPeriod = 'BUSINESS_DAY';

/** One channel or fulfilment-mode slice of the in-progress queue, largest first. */
export interface MixSlice {
  readonly key: string;
  readonly count: number;
}

/** One branch's own active-order load, for the leaderboard band (IA 0.1). */
export interface BranchLoad {
  readonly locationId: string;
  readonly displayName: string;
  readonly inProgress: number;
}

export interface LiveBoardSnapshot {
  /** The nine counters, as `GET .../orders/counts` defines them — §0.1's oversized figures are those numbers enlarged, not a second computation of them. */
  readonly counts: OrderCountsResponse;
  /** Which period `completed`, `cancelled` and `total` above were cut to. The other six are live and are never cut. */
  readonly period: CountsPeriod;
  /** The window's inclusive start, RFC 3339, or null for `ALL_TIME`. Rendered so the operator can see where the day was cut. */
  readonly periodFrom: string | null;
  readonly sourceMix: readonly MixSlice[];
  readonly typeMix: readonly MixSlice[];
  readonly branches: readonly BranchLoad[];
  /** Whether the branches band could be read at all — distinct from an empty roster; see {@link LiveBoard.branchRoster}. */
  readonly branchesAvailable: boolean;
  /** How many of the brand's active locations contributed a row — orders.md §2.11's "Показаны N из M" shape, applied to branches instead of the order filter. */
  readonly branchesShown: number;
  readonly branchesTotal: number;
}

/** The handful of fields this screen needs from `TenantControlPlaneService.LocationView` — not the whole profile Settings 10.2 reads. */
interface BranchRosterEntry {
  readonly id: string;
  readonly displayName: string;
  readonly status: string;
}

/** One bar, as the server sends it. `orders` rather than `count`: it is a count of orders, and the server says so. */
interface OrderMixSliceResponse {
  readonly key: string;
  readonly orders: number;
}

/** `GET .../locations/{id}/orders/counts` — the nine counters, plus what this wave added beside them. */
interface LocationCountsResponse extends OrderCountsResponse {
  readonly period: CountsPeriod;
  readonly periodFrom: string | null;
  readonly periodTo: string | null;
  readonly sourceMix: readonly OrderMixSliceResponse[];
  readonly typeMix: readonly OrderMixSliceResponse[];
}

/** `GET .../brands/{id}/orders/counts` — the same, plus a row per branch. */
interface BrandCountsResponse {
  readonly period: CountsPeriod;
  readonly periodFrom: string | null;
  readonly periodTo: string | null;
  readonly totals: OrderCountsResponse;
  readonly locations: ReadonlyArray<{
    readonly locationId: string;
    readonly counts: OrderCountsResponse;
  }>;
  readonly sourceMix: readonly OrderMixSliceResponse[];
  readonly typeMix: readonly OrderMixSliceResponse[];
}

/** What one tick read, before the branch names are joined onto it. */
interface BoardRead {
  readonly counts: OrderCountsResponse;
  readonly period: CountsPeriod;
  readonly periodFrom: string | null;
  readonly sourceMix: readonly MixSlice[];
  readonly typeMix: readonly MixSlice[];
  /** Live load by location id — every branch of the brand, or just this one when {@link ownBranchOnly}. */
  readonly loadByLocation: ReadonlyMap<string, number>;
  /** True when the grant reached only one branch, so an absent branch means "not readable", not "idle". */
  readonly ownBranchOnly: boolean;
}

/** A roster read, cached: the names never change between two ticks ten seconds apart. */
interface BranchRoster {
  readonly entries: readonly BranchRosterEntry[];
  /** False when even the operator's own branch could not be read — `today-page.ts` renders that as its own state. */
  readonly available: boolean;
}

/**
 * Composes IA 0.1's Live board, at a cost that does not grow with the number
 * of branches.
 *
 * **One request per tick.** This screen used to cost a counts call, a
 * 200-order page for the two mixes, a roster call, and then one further counts
 * call per active branch — eleven requests every ten seconds for a ten-branch
 * tenant, and thirty-one for a thirty-branch one. It now makes exactly one:
 * `GET .../brands/{brandId}/orders/counts` answers the counters, both mixes
 * and every branch's load together. The roster — which is only ever consulted
 * for display names — is read once and held, because a branch's name does not
 * change between two ticks ten seconds apart.
 *
 * **The mixes are exact.** They are server-side aggregates over the same
 * in-progress predicate the `totalNonTerminal` counter uses, so the bars add up
 * to the number beside them. The old client-side count over a
 * `MIX_FETCH_LIMIT = 200` page silently under-counted above that ceiling and
 * had no flag to say so; there is no page left to truncate.
 *
 * **Which grant you hold decides which endpoint answers, mostly.** A
 * brand-scoped principal gets the whole board. A location-scoped one — the
 * common pilot shape — is refused the brand read (ADR 0025: a scope covers
 * downwards, never up) and falls back to their own branch's counts. That
 * refusal is remembered for {@link REFUSAL_TTL_MS}, because re-asking a
 * question already answered 403 every ten seconds would put the request count
 * straight back to two — but it is not remembered forever: this board runs
 * unattended on a wallboard for a whole shift, a grant can be widened while it
 * is open, and nobody is there to reload the tab. Once the TTL passes the next
 * tick tries the brand read again, the same way {@link #readRoster}'s failure
 * is deliberately left uncached, below.
 *
 * **What this cannot build, and does not pretend to.** An operator leaderboard
 * needs a human name for `acceptedByActorId`, and nothing in the platform
 * resolves a principal to a display name yet (IA 9.2 People is tier 2 and not
 * built) — `today-page.ts` renders that band as an honest locked card instead
 * of raw Keycloak subject ids.
 */
@Injectable({ providedIn: 'root' })
export class LiveBoard {
  private readonly api = inject(ApiClient);

  /**
   * How long a brand's 403 is trusted before the next tick tries the brand
   * read again. Well below a shift length — the whole reason this is bounded —
   * and well above the 10s tick interval, so a refusal that is still true does
   * not turn into a retry storm.
   */
  private static readonly REFUSAL_TTL_MS = 5 * 60 * 1000;

  /** Brand id → when its brand-scoped read last answered 403. Expires after {@link REFUSAL_TTL_MS}. */
  private readonly brandReadRefused = new Map<string, number>();

  /** Roster reads in flight or settled, by brand — see {@link branchRoster}. Failures are not cached. */
  private readonly rosters = new Map<string, Promise<BranchRoster>>();

  async load(scope: LocationScope): Promise<LiveBoardSnapshot> {
    const [board, roster] = await Promise.all([this.readBoard(scope), this.branchRoster(scope)]);
    return { ...board, ...this.leaderboard(board, roster) };
  }

  // ------------------------------------------------------------- the board

  private async readBoard(scope: LocationScope): Promise<BoardRead> {
    const refusedAt = this.brandReadRefused.get(scope.brandId);
    const refusalIsStale =
      refusedAt === undefined || Date.now() - refusedAt >= LiveBoard.REFUSAL_TTL_MS;
    if (refusalIsStale) {
      try {
        return fromBrand(
          await this.get<BrandCountsResponse>(operationsPaths.brandOrderCounts(scope)),
        );
      } catch (error) {
        if (!(error instanceof ApiError) || error.status !== 403) {
          throw error;
        }
        this.brandReadRefused.set(scope.brandId, Date.now());
      }
    }
    return fromLocation(
      scope.locationId,
      await this.get<LocationCountsResponse>(operationsPaths.orderCounts(scope)),
    );
  }

  private get<T>(path: string): Promise<T> {
    return firstValueFrom(this.api.get<T>(path, { params: { period: BOARD_PERIOD } })).then(
      (result) => result.value,
    );
  }

  // -------------------------------------------------------- the leaderboard

  /**
   * The roster degrades in the same three steps it always has, but is now read
   * once rather than on every tick:
   *
   * 1. `LOCATION_READ` at `BRAND` scope — the full active roster.
   * 2. Only their own branch's grant — one entry, no roster call, because none
   *    would succeed.
   * 3. Not even that — `available: false`, which `today-page.ts` renders as its
   *    own state rather than as an empty leaderboard, because an empty table
   *    reads as "no other branches" instead of "could not check".
   *
   * A failure is deliberately not cached: step 3 is usually transient, and a
   * cached failure would leave the band broken until the operator reloads the
   * console.
   */
  private branchRoster(scope: LocationScope): Promise<BranchRoster> {
    const held = this.rosters.get(scope.brandId);
    if (held) {
      return held;
    }
    const reading = this.readRoster(scope).then((roster) => {
      if (!roster.available) {
        this.rosters.delete(scope.brandId);
      }
      return roster;
    });
    this.rosters.set(scope.brandId, reading);
    return reading;
  }

  private async readRoster(scope: LocationScope): Promise<BranchRoster> {
    try {
      const entries = await firstValueFrom(
        this.api.get<readonly BranchRosterEntry[]>(settingsPaths.locations(scope)),
      );
      return { entries: entries.value ?? [], available: true };
    } catch (error) {
      if (!(error instanceof ApiError) || error.status !== 403) {
        return { entries: [], available: false };
      }
    }
    try {
      const own = await firstValueFrom(
        this.api.get<BranchRosterEntry>(settingsPaths.location(scope)),
      );
      return { entries: [own.value], available: true };
    } catch {
      return { entries: [], available: false };
    }
  }

  /**
   * Joins the loads onto the names.
   *
   * An active branch the server sent no row for has taken no order in scope, so
   * it renders as zero rather than being dropped — the roster, not the order
   * store, is what knows how many branches exist. A row for a branch the roster
   * does not list (an archived one still finishing its last orders) is left
   * out: this band is a leaderboard of the branches a supervisor is running,
   * and a name-less row would be a UUID on a wall.
   *
   * The one case where a row is genuinely missing rather than zero is a
   * location-scoped operator who can none the less see the brand's roster: they
   * know their branch's load and no other, so only their own branch gets a row
   * while `branchesTotal` stays the size of the brand. That is orders.md
   * §2.11's «Показаны N из M», and it is the honest answer — the alternative,
   * nine rows reading zero, would say the rest of the brand is idle.
   */
  private leaderboard(
    board: BoardRead,
    roster: BranchRoster,
  ): Pick<LiveBoardSnapshot, 'branches' | 'branchesAvailable' | 'branchesShown' | 'branchesTotal'> {
    if (!roster.available) {
      return { branches: [], branchesAvailable: false, branchesShown: 0, branchesTotal: 0 };
    }

    const active = roster.entries.filter((entry) => entry.status === 'ACTIVE');
    const branches = active
      .filter((entry) => !board.ownBranchOnly || board.loadByLocation.has(entry.id))
      .map((entry) => ({
        locationId: entry.id,
        displayName: entry.displayName,
        inProgress: board.loadByLocation.get(entry.id) ?? 0,
      }))
      .sort((a, b) => b.inProgress - a.inProgress);

    return {
      branches,
      branchesAvailable: true,
      branchesShown: branches.length,
      branchesTotal: active.length,
    };
  }
}

function fromBrand(response: BrandCountsResponse): BoardRead {
  return {
    counts: response.totals,
    period: response.period,
    periodFrom: response.periodFrom,
    sourceMix: toMix(response.sourceMix),
    typeMix: toMix(response.typeMix),
    loadByLocation: new Map(
      response.locations.map((row) => [row.locationId, row.counts.totalNonTerminal]),
    ),
    ownBranchOnly: false,
  };
}

function fromLocation(locationId: string, response: LocationCountsResponse): BoardRead {
  return {
    counts: response,
    period: response.period,
    periodFrom: response.periodFrom,
    sourceMix: toMix(response.sourceMix),
    typeMix: toMix(response.typeMix),
    loadByLocation: new Map([[locationId, response.totalNonTerminal]]),
    ownBranchOnly: true,
  };
}

/**
 * The server's bars, in the shape the template reads. Already ordered
 * largest-first by the aggregate; the order is preserved rather than re-sorted,
 * so there is one definition of "largest first" and it is the server's.
 */
function toMix(slices: readonly OrderMixSliceResponse[] | undefined): readonly MixSlice[] {
  return (slices ?? []).map((slice) => ({ key: slice.key, count: slice.orders }));
}
