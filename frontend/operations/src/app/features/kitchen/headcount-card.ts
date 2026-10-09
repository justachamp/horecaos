import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { DayOfWeekToggle } from '../../shared/ui/day-of-week-toggle';
import { describeApiError } from '../orders/order-errors';
import { HeadcountApi, HeadcountPlan, HeadcountRow } from './headcount-api';
import { StationResponse } from './kitchen-api';
import { ProductivityEditor } from './productivity-editor';

/** One station of the grid: its label, and its cell per operating hour. */
interface StationLine {
  readonly stationId: string;
  readonly label: string;
  readonly rows: ReadonlyMap<number, HeadcountRow>;
  /** Every cell of this station is `NO_POLICY`: it has no figure and shows no number. */
  readonly noPolicy: boolean;
}

/**
 * IA §2.6, Card 2 of Capacity & buffer settings: how many cooks each station needs in each operating
 * hour of a chosen weekday (ADR 0163, row `2.6a`).
 *
 * **A planning aid, never a rota, and the card says so first.** Cooks are
 * `ceil(expected portions in the hour ÷ the pace the tenant stated for the station)`, from this branch's
 * last same-weekday dates (ADR 0043's method at station grain). The platform supplies no pace: until a
 * manager states one the card is an explanation and a form, not a chart. It names no person, changes no
 * ticket, release time or ceiling, and shows the dates it was built from so a manager can judge it.
 *
 * Every state the API can answer is rendered: loading, denied (no `kitchen.headcount.read`), failed,
 * no pace stated, too few dates (the raw per-date portions instead of a number), nothing fired, and the
 * plan with its busiest hour and the hours where the station's own ceiling, not the cooks, is the limit.
 */
@Component({
  selector: 'q-headcount-card',
  imports: [TPipe, DayOfWeekToggle, ProductivityEditor],
  templateUrl: './headcount-card.html',
  styleUrl: './headcount-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HeadcountCard {
  private readonly api = inject(HeadcountApi);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<LocationScope>();
  /** The branch's stations, for display names and the pace form. */
  readonly stations = input<readonly StationResponse[]>([]);
  /** Holds `kitchen.productivity.manage`. */
  readonly canManage = input(false);

  protected readonly weekday = signal(todayIsoWeekday());
  protected readonly weekdaySelection = computed<ReadonlySet<number>>(
    () => new Set([this.weekday()]),
  );

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly plan = signal<HeadcountPlan | null>(null);

  private generation = 0;

  /** The operating hours any station fired in, ascending: the grid's columns. */
  protected readonly hours = computed<readonly number[]>(() => {
    const seen = new Set<number>();
    for (const row of this.plan()?.rows ?? []) {
      seen.add(row.hour);
    }
    return [...seen].sort((a, b) => a - b);
  });

  protected readonly lines = computed<readonly StationLine[]>(() => {
    const byStation = new Map<string, Map<number, HeadcountRow>>();
    for (const row of this.plan()?.rows ?? []) {
      let cells = byStation.get(row.stationId);
      if (!cells) {
        cells = new Map();
        byStation.set(row.stationId, cells);
      }
      cells.set(row.hour, row);
    }
    return [...byStation.entries()].map(([stationId, rows]) => {
      const first = rows.values().next().value as HeadcountRow;
      return {
        stationId,
        label: this.stationLabel(first),
        rows,
        noPolicy: [...rows.values()].every((row) => row.status === 'NO_POLICY'),
      };
    });
  });

  /** Nothing in the plan has a figure: the card is an explanation and a form. */
  protected readonly nothingStated = computed(() => {
    const current = this.plan();
    return (
      current !== null && current.rows.length > 0 && this.lines().every((line) => line.noPolicy)
    );
  });

  /** Hours where the station's own ceiling is below the top of the band: more cooks do not help. */
  protected readonly plantLimits = computed(() =>
    (this.plan()?.rows ?? []).filter((row) => row.plantLimited),
  );

  constructor() {
    effect(() => {
      const scope = this.scope();
      const weekday = this.weekday();
      untracked(() => void this.load(scope, weekday));
    });
  }

  protected onWeekday(selected: ReadonlySet<number>): void {
    const [day] = selected;
    if (day !== undefined) {
      this.weekday.set(day);
    }
  }

  protected reload(): void {
    void this.load(this.scope(), this.weekday());
  }

  protected hourLabel(hour: number): string {
    return `${String(hour).padStart(2, '0')}:00`;
  }

  protected dateList(plan: HeadcountPlan): string {
    return plan.sampleDates.join(', ');
  }

  protected cellDetail(row: HeadcountRow): string {
    return this.i18n.t('kitchen.headcount.cell.detail', {
      portions: formatPortions(row.expectedPortions),
      low: formatPortions(row.lowPortions),
      high: formatPortions(row.highPortions),
      bandCooks: row.cooksAtPeakOfBand ?? 0,
    });
  }

  protected plantLimitText(row: HeadcountRow): string {
    return this.i18n.t('kitchen.headcount.plantLimited', {
      station: this.stationLabel(row),
      ceiling: row.stationCeiling ?? 0,
      hour: this.hourLabel(row.hour),
    });
  }

  protected stationLabel(row: {
    readonly stationId?: string;
    readonly stationCode: string;
  }): string {
    const station = this.stations().find((candidate) => candidate.stationId === row.stationId);
    if (!station) {
      return row.stationCode;
    }
    switch (this.i18n.locale()) {
      case 'uz-Latn':
        return station.displayNameUz;
      case 'en':
        return station.displayNameEn;
      default:
        return station.displayNameRu;
    }
  }

  private async load(scope: LocationScope, weekday: number): Promise<void> {
    const generation = ++this.generation;
    this.loading.set(true);
    try {
      const plan = await this.api.plan(scope, weekday);
      if (generation !== this.generation) {
        return;
      }
      this.plan.set(plan);
      this.denied.set(false);
      this.loadError.set(null);
    } catch (error) {
      if (generation !== this.generation) {
        return;
      }
      this.plan.set(null);
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
        this.loadError.set(null);
      } else if (error instanceof ApiError) {
        this.denied.set(false);
        this.loadError.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
      } else {
        throw error;
      }
    } finally {
      if (generation === this.generation) {
        this.loading.set(false);
      }
    }
  }
}

/** ISO-8601: 1 = Monday .. 7 = Sunday; `Date#getDay` has Sunday as 0. */
function todayIsoWeekday(): number {
  const day = new Date().getDay();
  return day === 0 ? 7 : day;
}

function formatPortions(value: number | null): string {
  if (value === null) {
    return '0';
  }
  return Number.isInteger(value) ? String(value) : value.toFixed(1);
}
