import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { formatDuration } from '../../../core/format/datetime';
import { formatMoney } from '../../../core/format/money';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { DateRange, DateRangePicker } from '../../../shared/ui/date-range-picker';
import { ProvenanceBanner } from '../../reports/provenance-banner';
import { ProvenanceResponse } from '../../reports/reporting-api';
import { LocationsApi } from '../../settings/locations/locations-api';
import {
  REVIEW_TAG_CATEGORIES,
  RecoveryRow,
  RecoverySummary,
  ReviewReportApi,
  ReviewSummary,
  ReviewSummaryRow,
  ReviewTagCategory,
} from './review-report-api';

type LoadState = 'loading' | 'ready' | 'error' | 'denied';

/** One branch's ratings as the table prints them. */
interface RatingLine {
  readonly key: string;
  readonly name: string;
  readonly reviews: number;
  readonly average: string;
  readonly withComment: number;
  readonly distribution: string;
}

/** One branch and tag category, with the two sentiments. */
interface TagLine {
  readonly key: string;
  readonly name: string;
  readonly category: ReviewTagCategory;
  readonly positive: number;
  readonly negative: number;
}

/** One branch's cases, every time already formatted. */
interface CaseLine {
  readonly key: string;
  readonly name: string;
  readonly opened: number;
  readonly closed: number;
  readonly firstResponse: string;
  readonly close: string;
  readonly breached: string;
  readonly compensations: number;
}

/** One-decimal half-up mean from a sum and a count (the sum and the count are summed, then divided). */
export function meanOf(sum: number, count: number): number | null {
  return count === 0 ? null : Math.round((sum / count) * 10) / 10;
}

/**
 * Review quality (ADR 0159, rows 5.4 and 5.5): ratings, what guests mention, and how fast service-recovery
 * cases are worked, per branch over the closed business days of a range.
 *
 * This is the screen the review report endpoints never had. The IA asked for a breakdown by meal, by operator and
 * by delivery time; the platform answers it with tags (five categories by two sentiments), which are counted
 * here by category and never by wording. A breakdown by courier, and a rating of a courier, are declined by the
 * record (ADR 0159, open input 8; ADR 0042), so the courier is a topic of a tag and never a person.
 *
 * Read from the day-close facts, so a figure for today settles tomorrow: the banner says so. Remedy money is
 * shown per currency as it was attached to the cases, because the compensation link carries no legal entity and
 * ADR 0038 forbids calling it a money metric.
 */
@Component({
  selector: 'q-review-report-page',
  imports: [TPipe, DateRangePicker, ProvenanceBanner],
  templateUrl: './review-report-page.html',
  styleUrl: './review-report-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReviewReportPage implements OnInit {
  private readonly api = inject(ReviewReportApi);
  private readonly location = inject(CurrentLocation);
  private readonly locationsApi = inject(LocationsApi);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<LoadState>('loading');
  protected readonly range = signal<{ start: string; end: string }>(lastSevenDays());
  protected readonly summary = signal<ReviewSummary | null>(null);
  protected readonly recovery = signal<RecoverySummary | null>(null);
  private readonly names = signal<ReadonlyMap<string, string>>(new Map());

  protected readonly categories = REVIEW_TAG_CATEGORIES;

  protected readonly provenance = computed<ProvenanceResponse | null>(
    () => this.summary()?.provenance ?? this.recovery()?.provenance ?? null,
  );

  protected readonly ratingLines = computed<readonly RatingLine[]>(() => {
    const rows = this.summary()?.rows ?? [];
    return [...rows]
      .sort((a, b) => this.nameOf(a.locationId).localeCompare(this.nameOf(b.locationId)))
      .map((row) => this.ratingLine(row));
  });

  /** The total across branches: the sums are summed, then divided. */
  protected readonly ratingTotal = computed<RatingLine | null>(() => {
    const rows = this.summary()?.rows ?? [];
    if (rows.length < 2) {
      return null;
    }
    const reviews = rows.reduce((sum, row) => sum + row.reviews, 0);
    const ratingSum = rows.reduce((sum, row) => sum + row.ratingSum, 0);
    const distribution = [0, 1, 2, 3, 4].map((index) =>
      rows.reduce((sum, row) => sum + (row.distribution[index] ?? 0), 0),
    );
    return {
      key: 'total',
      name: this.i18n.t('customers.reviews.report.total'),
      reviews,
      average: this.averageText(meanOf(ratingSum, reviews)),
      withComment: rows.reduce((sum, row) => sum + row.withComment, 0),
      distribution: distribution.join(' · '),
    };
  });

  protected readonly tagLines = computed<readonly TagLine[]>(() => {
    const tags = this.summary()?.tags ?? [];
    const byKey = new Map<
      string,
      { name: string; category: ReviewTagCategory; positive: number; negative: number }
    >();
    for (const row of tags) {
      const key = `${row.locationId}|${row.category}`;
      const line = byKey.get(key) ?? {
        name: this.nameOf(row.locationId),
        category: row.category,
        positive: 0,
        negative: 0,
      };
      if (row.sentiment === 'POSITIVE') {
        line.positive += row.tags;
      } else {
        line.negative += row.tags;
      }
      byKey.set(key, line);
    }
    return [...byKey.entries()]
      .map(([key, line]) => ({ key, ...line }))
      .sort(
        (a, b) =>
          a.name.localeCompare(b.name) ||
          this.categories.indexOf(a.category) - this.categories.indexOf(b.category),
      );
  });

  protected readonly caseLines = computed<readonly CaseLine[]>(() => {
    const rows = this.recovery()?.rows ?? [];
    return [...rows]
      .sort((a, b) => this.nameOf(a.locationId).localeCompare(this.nameOf(b.locationId)))
      .map((row) => this.caseLine(row));
  });

  /** How the cases of the range ended, across branches, most common first. */
  protected readonly outcomes = computed<readonly { code: string; label: string; cases: number }[]>(
    () => {
      const totals = new Map<string, number>();
      for (const row of this.recovery()?.outcomes ?? []) {
        totals.set(row.outcomeCode, (totals.get(row.outcomeCode) ?? 0) + row.cases);
      }
      return [...totals.entries()]
        .map(([code, cases]) => ({ code, cases, label: this.outcomeLabel(code) }))
        .sort((a, b) => b.cases - a.cases || a.code.localeCompare(b.code));
    },
  );

  /** Remedy money per currency, as attached to the cases. */
  protected readonly remedyMoney = computed<readonly string[]>(() => {
    const totals = new Map<string, number>();
    for (const row of this.recovery()?.remedies ?? []) {
      totals.set(row.currency, (totals.get(row.currency) ?? 0) + row.amountMinor);
    }
    return [...totals.entries()]
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([currency, amountMinor]) =>
        formatMoney({ amountMinor, currency }, this.i18n.locale(), { withUnit: true }),
      );
  });

  protected readonly isEmpty = computed(
    () =>
      (this.summary()?.rows ?? []).every((row) => row.reviews === 0) &&
      this.caseLines().length === 0,
  );

  ngOnInit(): void {
    void this.load();
  }

  protected setRange(range: DateRange): void {
    this.range.set({ start: range.start, end: range.end });
    void this.load();
  }

  protected retry(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    this.state.set('loading');
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.state.set(this.location.denied() ? 'denied' : 'error');
      return;
    }
    const window = { from: this.range().start, to: this.range().end };
    try {
      const [summary, recovery] = await Promise.all([
        this.api.summary(scope.tenantId, window, scope.brandId),
        this.api.recovery(scope.tenantId, window, scope.brandId),
      ]);
      this.summary.set(summary);
      this.recovery.set(recovery);
      this.state.set('ready');
    } catch (error) {
      this.state.set(error instanceof ApiError && error.status === 403 ? 'denied' : 'error');
      return;
    }
    void this.loadNames(scope);
  }

  private async loadNames(scope: {
    tenantId: string;
    brandId: string;
    locationId: string;
  }): Promise<void> {
    try {
      const locations = await this.locationsApi.list(scope);
      this.names.set(new Map(locations.map((location) => [location.id, location.displayName])));
    } catch {
      // Names are a courtesy; the rows stand without them.
    }
  }

  protected nameOf(locationId: string): string {
    return this.names().get(locationId) ?? locationId.slice(0, 8);
  }

  private ratingLine(row: ReviewSummaryRow): RatingLine {
    return {
      key: row.locationId,
      name: this.nameOf(row.locationId),
      reviews: row.reviews,
      average: this.averageText(row.average),
      withComment: row.withComment,
      distribution: row.distribution.join(' · '),
    };
  }

  private caseLine(row: RecoveryRow): CaseLine {
    return {
      key: row.locationId,
      name: this.nameOf(row.locationId),
      opened: row.opened,
      closed: row.closed,
      firstResponse: this.pair(row.firstResponseMeanSeconds, row.firstResponseP90Seconds),
      close: this.pair(row.closeMeanSeconds, row.closeP90Seconds),
      breached:
        row.breachRateBasisPoints === null
          ? '—'
          : `${row.breached} (${(row.breachRateBasisPoints / 100).toFixed(1)}%)`,
      compensations: row.compensations,
    };
  }

  private averageText(average: number | null): string {
    return average === null ? '—' : average.toFixed(1);
  }

  /** `mean / p90` in the console's own duration unit, or a dash when there is nothing to average. */
  private pair(mean: number | null, p90: number | null): string {
    if (mean === null && p90 === null) {
      return '—';
    }
    return `${this.duration(mean)} / ${this.duration(p90)}`;
  }

  private duration(seconds: number | null): string {
    return seconds === null
      ? '—'
      : formatDuration(seconds / 60, {
          hour: this.i18n.t('customers.reviews.report.unit.hour'),
          minute: this.i18n.t('customers.reviews.report.unit.minute'),
        });
  }

  protected categoryLabel(category: ReviewTagCategory): string {
    return this.i18n.t(`customers.feedback.tags.category.${category}` as MessageKey);
  }

  private outcomeLabel(code: string): string {
    return this.i18n.t(`customers.recovery.outcome.${code}` as MessageKey);
  }
}

function lastSevenDays(): { start: string; end: string } {
  const pad = (value: number): string => String(value).padStart(2, '0');
  const iso = (date: Date): string =>
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
  const end = new Date();
  const start = new Date(end);
  start.setDate(start.getDate() - 6);
  return { start: iso(start), end: iso(end) };
}
