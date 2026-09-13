import { ChangeDetectionStrategy, Component, booleanAttribute, input } from '@angular/core';

import { Sparkline } from './sparkline';

/**
 * A KPI tile's delta against its own comparison period — `+12%` styled up,
 * `−4%` styled down, absent styled as nothing at all.
 *
 * **Extracted because it was implemented more than once with no shared
 * definition** (IA X.20's own row: "the same average-check tile is
 * implemented twice... with no shared definition of the delta").
 * `business-overview-page.ts` built `moneyTile`/`countTile` as two
 * near-identical assemblers around this exact comparison, each free to drift
 * from the other's rounding or its up/down rule. There is now one function
 * (moved here from `report-formatting.ts`'s retired `formatDeltaPercent` —
 * see that file's own note) and one component, and every caller gets both
 * the text and the up/down rule from the same place.
 */
export function deltaOf(
  value: number | null,
  previous: number | null,
): { readonly deltaText: string | null; readonly deltaUp: boolean } {
  if (value === null || previous === null || previous === 0) {
    return { deltaText: null, deltaUp: false };
  }
  const percent = Math.round(((value - previous) / previous) * 100);
  const sign = percent > 0 ? '+' : percent < 0 ? '−' : '';
  return { deltaText: `${sign}${Math.abs(percent)}%`, deltaUp: percent > 0 };
}

/**
 * One KPI tile (IA X.20) — a value, its delta against a comparison period,
 * and (when the caller has one) a sparkline drawn from the per-business-date
 * rows `/reporting/queries` already returns and `report-rollup.ts`'s
 * `sumTotal`/`sumAcrossDays` used to throw away (see `dailySeries` there).
 *
 * **The sparkline is period-over-period, not intraday.** There is no
 * today-so-far hourly series to draw — the day-grain query has no hour
 * dimension — so each point is one whole business day's total, the same
 * property `deltaOf` compares against one whole period back. Callers that
 * have no series to offer (a tile with no sparkline yet) simply omit
 * `sparklinePoints`; the tile renders exactly as it did with no trend at
 * all.
 */
@Component({
  selector: 'q-kpi-tile',
  imports: [Sparkline],
  templateUrl: './kpi-tile.html',
  styleUrl: './kpi-tile.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KpiTile {
  /** Already translated — the tile does not know or care which locale it is in. */
  readonly label = input.required<string>();
  /** Already formatted for display — money, a count, a duration; the tile never formats a number itself. */
  readonly value = input.required<string>();

  readonly deltaText = input<string | null>(null);
  readonly deltaUp = input(false, { transform: booleanAttribute });
  /** Already translated, e.g. "vs. same period a week back" — rendered beside `deltaText`, never alone. */
  readonly deltaSuffix = input<string | null>(null);

  readonly subtitle = input<string | null>(null);

  readonly provisional = input(false, { transform: booleanAttribute });
  /** Already translated, e.g. "Provisional" — rendered only when {@link provisional} is set. */
  readonly provisionalNote = input<string | null>(null);

  /** One point per business date in the tile's own period, oldest first. `null` renders as a gap. */
  readonly sparklinePoints = input<readonly (number | null)[] | null>(null);
}
