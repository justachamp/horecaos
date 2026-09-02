import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { formatClock } from '../../core/format/datetime';
import { TPipe } from '../../core/i18n/t.pipe';
import { REPORTS_PLACEHOLDER_TIME_ZONE } from './reports-filter-state';
import { ddmm } from './report-formatting';
import { ProvenanceResponse } from './reporting-api';

/**
 * The freshness line every 7.x view carries (statistics.md §2.1, "Freshness"),
 * plus the two states ADR 0043 requires a report to be able to say about
 * itself: a business day still inside its settle window, and a recut that
 * disagreed with a stored figure and was left alone rather than silently
 * applied.
 *
 * ADR 0023: "a report that cannot state its freshness is not shipped." This is
 * the one component that states it, so every 7.x screen states it the same way.
 */
@Component({
  selector: 'q-provenance-banner',
  imports: [TPipe],
  template: `
    @if (provenance(); as p) {
      <div class="provenance">
        <span class="provenance__freshness q-caption q-tnum">
          {{ 'reports.provenance.asOf' | t: { time: asOfClock() } }}
          @if (p.closedThrough) {
            · {{ 'reports.provenance.closedThrough' | t: { date: ddmm(p.closedThrough) } }}
          } @else {
            · {{ 'reports.provenance.neverClosed' | t }}
          }
        </span>

        @if (dayNotYetClosed()) {
          <div class="provenance__band provenance__band--amber" role="status">
            {{ 'reports.provenance.settling' | t }}
          </div>
        }

        @if (p.openDivergences > 0) {
          <div class="provenance__band provenance__band--red" role="alert">
            {{ 'reports.provenance.divergence' | t: { count: p.openDivergences } }}
          </div>
        }

        @if (p.provisionalMetrics.length > 0) {
          <div class="provenance__band provenance__band--amber-rule" role="note">
            {{ 'reports.provenance.provisional' | t }}
          </div>
        }
      </div>
    }
  `,
  styles: `
    .provenance {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .provenance__freshness {
      color: var(--q-ink-subtle);
    }
    .provenance__band {
      padding: 6px 10px;
      font-size: 13px;
      border-left: 3px solid transparent;
    }
    .provenance__band--amber {
      background: var(--q-warning-tint);
      color: var(--q-warning-text);
      border-left-color: var(--q-warning);
    }
    .provenance__band--red {
      background: var(--q-error-tint);
      color: var(--q-error-text);
      border-left-color: var(--q-error);
    }
    .provenance__band--amber-rule {
      background: var(--q-warning-tint);
      color: var(--q-warning-text);
      border-left-color: var(--q-warning);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProvenanceBanner {
  readonly provenance = input<ProvenanceResponse | null>(null);
  /** The requested range's end date — compared against `closedThrough` to show the settling band. */
  readonly requestedTo = input<string | null>(null);

  protected readonly ddmm = ddmm;

  protected readonly asOfClock = computed(() => {
    const p = this.provenance();
    return p ? formatClock(new Date(p.asOf), REPORTS_PLACEHOLDER_TIME_ZONE) : '';
  });

  protected readonly dayNotYetClosed = computed(() => {
    const p = this.provenance();
    const to = this.requestedTo();
    if (!p || !to) {
      return false;
    }
    return p.closedThrough === null || to > p.closedThrough;
  });
}
