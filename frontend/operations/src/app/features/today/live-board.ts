import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { settingsPaths } from '../../core/api/settings-paths';
import { OrderCountsResponse } from '../orders/order-detail';
import { IN_PROGRESS_ORDER_STATUSES } from '../orders/order-status';
import { OrderSummaryResponse } from '../orders/order-summary';

/**
 * The ceiling on the in-progress fetch used only to compute the source/type
 * mix — same compromise `order-queue.ts`'s own `FETCH_LIMIT` documents: a
 * first-render approximation, not a promise that every active order is
 * counted at a location busier than this.
 */
const MIX_FETCH_LIMIT = 200;

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
  /** The same `GET .../orders/counts` the board's own tab badges read — §0.1's counters are that number, enlarged, not a second computation of it. */
  readonly counts: OrderCountsResponse;
  readonly sourceMix: readonly MixSlice[];
  readonly typeMix: readonly MixSlice[];
  readonly branches: readonly BranchLoad[];
  /** Whether the branches band could be read at all — distinct from an empty roster; see {@link LiveBoard.loadBranches}. */
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

/**
 * Composes IA 0.1's Live board from reads that already exist — no new
 * endpoint, no migration. The oversized counters and the "in progress"
 * grouping are `GET .../orders/counts` and `order-status.ts`'s
 * `IN_PROGRESS_ORDER_STATUSES` (the same aggregate the order board's tab
 * badges already trust); the source/type mix is computed client-side over
 * the in-progress orders the same endpoint the board already polls returns;
 * and the branch leaderboard is the brand's location roster
 * (`OperationsBrandController.locations`, Settings 10.2's own read) paired
 * with one `orders/counts` call per branch.
 *
 * **What this cannot build, and does not pretend to.** An operator
 * leaderboard needs a human name for `acceptedByActorId`, and nothing in the
 * platform resolves a principal to a display name yet (IA 9.2 People is
 * tier 2 and not built) — `today-page.ts` renders that band as an honest
 * locked card instead of raw Keycloak subject ids.
 */
@Injectable({ providedIn: 'root' })
export class LiveBoard {
  private readonly api = inject(ApiClient);

  async load(scope: LocationScope): Promise<LiveBoardSnapshot> {
    const [counts, mixes, branchBand] = await Promise.all([
      this.loadCounts(scope),
      this.loadMixes(scope),
      this.loadBranches(scope),
    ]);

    return { counts, ...mixes, ...branchBand };
  }

  private async loadCounts(scope: LocationScope): Promise<OrderCountsResponse> {
    const result = await firstValueFrom(
      this.api.get<OrderCountsResponse>(operationsPaths.orderCounts(scope)),
    );
    return result.value;
  }

  private async loadMixes(
    scope: LocationScope,
  ): Promise<Pick<LiveBoardSnapshot, 'sourceMix' | 'typeMix'>> {
    const result = await firstValueFrom(
      this.api.get<OrderSummaryResponse[]>(operationsPaths.orders(scope), {
        params: { status: IN_PROGRESS_ORDER_STATUSES, limit: MIX_FETCH_LIMIT },
      }),
    );
    const activeOrders = result.value ?? [];
    return {
      sourceMix: mixBy(activeOrders, (order) => order.channelCode ?? null),
      typeMix: mixBy(activeOrders, (order) => order.fulfillmentMode ?? null),
    };
  }

  /**
   * The branch band degrades in three steps, each one a real state rather
   * than a fabricated one:
   *
   * 1. The operator holds `LOCATION_READ` at `BRAND` scope (an owner, or a
   *    manager scoped to the whole brand) — the full active roster, one row
   *    per branch, each with its own live count.
   * 2. They hold only their own branch's grant, the common pilot shape
   *    (`current-location.ts` resolves the same thing the same way) — one
   *    row, this branch, no roster call needed because none would succeed.
   * 3. Even that fails (a transient error, or a principal with no location
   *    grant at all reaching this far) — `branchesAvailable: false`, and
   *    `today-page.ts` renders that as its own state rather than an empty
   *    leaderboard, which would read as "no other branches" instead of
   *    "could not check".
   */
  private async loadBranches(
    scope: LocationScope,
  ): Promise<
    Pick<LiveBoardSnapshot, 'branches' | 'branchesAvailable' | 'branchesShown' | 'branchesTotal'>
  > {
    let roster: readonly BranchRosterEntry[];
    try {
      roster = await this.brandRoster(scope);
    } catch (error) {
      if (!(error instanceof ApiError) || error.status !== 403) {
        return { branches: [], branchesAvailable: false, branchesShown: 0, branchesTotal: 0 };
      }
      try {
        roster = [await this.ownLocation(scope)];
      } catch {
        return { branches: [], branchesAvailable: false, branchesShown: 0, branchesTotal: 0 };
      }
    }

    const active = roster.filter((entry) => entry.status === 'ACTIVE');
    const settled = await Promise.all(active.map((entry) => this.branchLoad(scope, entry)));
    const branches = settled
      .filter((row): row is BranchLoad => row !== null)
      .sort((a, b) => b.inProgress - a.inProgress);

    return {
      branches,
      branchesAvailable: true,
      branchesShown: branches.length,
      branchesTotal: active.length,
    };
  }

  private async branchLoad(
    scope: LocationScope,
    entry: BranchRosterEntry,
  ): Promise<BranchLoad | null> {
    try {
      const counts = await this.loadCounts({ ...scope, locationId: entry.id });
      return {
        locationId: entry.id,
        displayName: entry.displayName,
        inProgress: counts.totalNonTerminal,
      };
    } catch {
      // This one branch's grant does not extend to ORDER_READ there — omit
      // the row rather than render a broken one; `branchesShown`/`branchesTotal`
      // is what tells the operator the leaderboard is partial.
      return null;
    }
  }

  private async brandRoster(scope: LocationScope): Promise<readonly BranchRosterEntry[]> {
    const result = await firstValueFrom(
      this.api.get<readonly BranchRosterEntry[]>(settingsPaths.locations(scope)),
    );
    return result.value ?? [];
  }

  private async ownLocation(scope: LocationScope): Promise<BranchRosterEntry> {
    const result = await firstValueFrom(
      this.api.get<BranchRosterEntry>(settingsPaths.location(scope)),
    );
    return result.value;
  }
}

function mixBy(
  orders: readonly OrderSummaryResponse[],
  keyOf: (order: OrderSummaryResponse) => string | null,
): readonly MixSlice[] {
  const counts = new Map<string, number>();
  for (const order of orders) {
    const key = keyOf(order) ?? '—';
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  return [...counts.entries()]
    .map(([key, count]) => ({ key, count }))
    .sort((a, b) => b.count - a.count);
}
