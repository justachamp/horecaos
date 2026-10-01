import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';

/**
 * One person on the live board's operator band (IA `0.1d`).
 *
 * Mirrors `OperatorTodayLeaderboardController.OperatorTodayRowResponse`.
 *
 * @property operatorPrincipalId the account the orders were written under. Only
 *   ever a staff subject here -- a bot or a website order names a channel, and
 *   the server counts `USER` actors alone. It is a join key and is never
 *   printed: a row with no name renders the page's typed «unnamed colleague»
 *   label, not this value.
 * @property displayName this tenant's own name for the person (ADR 0139), or
 *   `null` for a subject it keeps no name for.
 * @property createdCount orders the person took.
 * @property acceptedCount orders the person confirmed. Both counts can include
 *   the same order and are never added together.
 */
export interface LiveOperatorRow {
  readonly operatorPrincipalId: string;
  readonly displayName: string | null;
  readonly createdCount: number;
  readonly acceptedCount: number;
}

/** `GET .../orders/operators/today`. */
interface OperatorsTodayResponse {
  readonly rows: readonly LiveOperatorRow[];
  readonly businessDayFrom: string;
  readonly businessDayTo: string;
}

/**
 * What one tick of the band read.
 *
 * `available` is false when the band could not be read at all (a failure, or a
 * grant that reaches neither route) and is distinct from an empty `rows`: an
 * empty table reads as "nobody has taken an order yet today", a failed read as
 * "could not check", and a supervisor must not mistake the second for the first.
 */
export interface LiveOperatorsBand {
  readonly available: boolean;
  readonly rows: readonly LiveOperatorRow[];
}

const UNAVAILABLE: LiveOperatorsBand = { available: false, rows: [] };

/**
 * The live board's operator band, read on its own request beside the board.
 *
 * **Why this is not folded into {@link LiveBoard}.** `LiveBoard` states, and
 * tests, that a tick costs one request however many branches a brand has. The
 * band is a second, independent read of personal data under its own gate and
 * its own failure: a band that cannot be read must leave the counters standing,
 * and a counters failure must not blank the band. Two requests with two failure
 * modes are two services.
 *
 * **Which route answers, mostly depends on the grant.** `ORDER_READ` is
 * declared at `BRAND` scope for the brand-wide band and at `LOCATION` scope for
 * one branch, because a grant covers only the routes whose path names its level
 * (ADR 0025). A brand-level reader gets the whole brand's people; a shift
 * supervisor holding the capability at their own branch alone is refused the
 * brand route and reads that branch. The refusal is remembered for
 * {@link REFUSAL_TTL_MS}, as `LiveBoard` does for the same reason: a board that
 * runs unattended for a whole shift must not re-ask a question already answered
 * 403 every ten seconds, and must not stay stuck behind it forever either once
 * a grant is widened.
 *
 * **Names come from the server.** The platform composes them from the tenant's
 * own staff record (ADR 0139); nothing here asks anyone else.
 */
@Injectable({ providedIn: 'root' })
export class LiveOperators {
  private readonly api = inject(ApiClient);

  /** Same horizon as `LiveBoard`'s own brand refusal. */
  private static readonly REFUSAL_TTL_MS = 5 * 60 * 1000;

  /** Brand id -> when its brand-wide band last answered 403. */
  private readonly brandRefused = new Map<string, number>();

  async load(scope: LocationScope): Promise<LiveOperatorsBand> {
    try {
      const refusedAt = this.brandRefused.get(scope.brandId);
      const refusalIsStale =
        refusedAt === undefined || Date.now() - refusedAt >= LiveOperators.REFUSAL_TTL_MS;
      if (refusalIsStale) {
        try {
          return await this.read(operationsPaths.brandOperatorsToday(scope));
        } catch (error) {
          if (!(error instanceof ApiError) || error.status !== 403) {
            throw error;
          }
          this.brandRefused.set(scope.brandId, Date.now());
        }
      }
      return await this.read(operationsPaths.locationOperatorsToday(scope));
    } catch {
      // The band is an extra. Whatever went wrong -- a 403 on the branch route
      // too, a 5xx, a dropped connection -- the counters beside it must stand.
      return UNAVAILABLE;
    }
  }

  private async read(path: string): Promise<LiveOperatorsBand> {
    const result = await firstValueFrom(this.api.get<OperatorsTodayResponse>(path));
    return { available: true, rows: result.value.rows };
  }
}
