import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

import { TPipe } from '../../../core/i18n/t.pipe';
import { ChartFrame } from './chart-frame';
import { categoricalColorVar } from './chart-colors';
import { ChartCategory, ChartValueFormatter, DEFAULT_VALUE_FORMATTER } from './chart-model';

const WIDTH = 640;
const BAR_HEIGHT = 28;
const BAR_Y = 8;
const HEIGHT = BAR_Y * 2 + BAR_HEIGHT + 20;
const SEGMENT_GAP = 2;

interface Segment {
  readonly key: string;
  readonly label: string;
  readonly value: string;
  readonly sharePercent: number;
  readonly color: string;
  readonly x: number;
  readonly width: number;
}

/**
 * A single 100%-wide stacked bar over a fixed set of categories that
 * partition a whole (IA X.19) — replaces `business-overview-page.html`'s
 * `stacked-bar__segment--DELIVERY/PICKUP/DINE_IN` divs, which reused
 * `--q-primary`/`--q-success`/`--q-warning` for series identity. Those are
 * status colours, reserved for order state — the data-viz procedure's own
 * rule ("never reused for series 4") is not a style preference, it is what
 * stops a fulfilment-mix segment from reading as a warning. This chart uses
 * the categorical palette instead.
 *
 * A 2px surface gap separates every segment (the mark spec's own rule for
 * stacked fills), which is also what makes each segment an independent
 * hover/focus target rather than one solid band.
 */
@Component({
  selector: 'q-stacked-bar-chart',
  imports: [ChartFrame, TPipe],
  templateUrl: './stacked-bar-chart.html',
  styleUrl: './stacked-bar-chart.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StackedBarChart {
  readonly segments = input.required<readonly ChartCategory[]>();
  readonly ariaLabel = input.required<string>();
  readonly valueFormatter = input<ChartValueFormatter>(DEFAULT_VALUE_FORMATTER);

  protected readonly width = WIDTH;
  protected readonly height = HEIGHT;
  protected readonly barY = BAR_Y;
  protected readonly barHeight = BAR_HEIGHT;

  protected readonly hover = signal<Segment | null>(null);

  private readonly total = computed(() => {
    const sum = this.segments().reduce((acc, segment) => acc + segment.value, 0);
    return sum > 0 ? sum : 1;
  });

  protected readonly plotted = computed<readonly Segment[]>(() => {
    const total = this.total();
    let x = 0;
    return this.segments().map((segment, index) => {
      const rawWidth = (segment.value / total) * WIDTH;
      const width = Math.max(0, rawWidth - SEGMENT_GAP);
      const plottedSegment: Segment = {
        key: segment.key,
        label: segment.label,
        value: this.valueFormatter()(segment.value),
        sharePercent: Math.round((segment.value / total) * 100),
        color: categoricalColorVar(index),
        x,
        width,
      };
      x += rawWidth;
      return plottedSegment;
    });
  });

  protected showHover(segment: Segment): void {
    this.hover.set(segment);
  }

  protected clearHover(): void {
    this.hover.set(null);
  }
}
