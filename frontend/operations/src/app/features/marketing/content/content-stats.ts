import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { ContentApi, ContentStats } from './content-api';
import { dayLabel } from './content-schedule';

interface Totals {
  readonly revisionNo: number;
  readonly impressions: number;
  readonly clicks: number;
  readonly dismissals: number;
  readonly completions: number;
}

/**
 * An item's anonymous view counts (ADR 0157, Decision 10).
 *
 * **Client-reported, and labelled so.** The storefront sends them from the visitor's own device;
 * anyone can inflate them, no device, customer or network identifier is stored, and the reporting
 * layer does not consume them. They show roughly which version drew attention, not a measurement.
 */
@Component({
  selector: 'q-content-stats',
  imports: [TPipe],
  templateUrl: './content-stats.html',
  styleUrl: './content-stats.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContentStatsPanel implements OnInit {
  private readonly api = inject(ContentApi);
  private readonly brand = inject(CurrentBrand);
  private readonly i18n = inject(I18n);

  readonly itemId = input.required<string>();

  protected readonly stats = signal<ContentStats | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  /** One line per version: the totals over the window. */
  protected readonly totals = computed<readonly Totals[]>(() => {
    const byRevision = new Map<number, Totals>();
    for (const row of this.stats()?.rows ?? []) {
      const current = byRevision.get(row.revisionNo) ?? {
        revisionNo: row.revisionNo,
        impressions: 0,
        clicks: 0,
        dismissals: 0,
        completions: 0,
      };
      byRevision.set(row.revisionNo, {
        revisionNo: row.revisionNo,
        impressions: current.impressions + row.impressions,
        clicks: current.clicks + row.clicks,
        dismissals: current.dismissals + row.dismissals,
        completions: current.completions + row.completions,
      });
    }
    return [...byRevision.values()].sort((a, b) => b.revisionNo - a.revisionNo);
  });

  async ngOnInit(): Promise<void> {
    const scope = this.brand.scope();
    if (scope === null) {
      this.loading.set(false);
      return;
    }
    try {
      this.stats.set(await this.api.stats(scope, this.itemId()));
    } catch (failure) {
      this.error.set(
        failure instanceof ApiError
          ? describeApiError(failure, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('marketing.content.stats.failed'),
      );
    } finally {
      this.loading.set(false);
    }
  }

  protected range(stats: ContentStats): string {
    return `${dayLabel(stats.from)} – ${dayLabel(stats.to)}`;
  }
}
