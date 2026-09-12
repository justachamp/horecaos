import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

import { TPipe } from '../../../core/i18n/t.pipe';
import { ChartFrame } from './chart-frame';
import { sequentialColorVar } from './chart-colors';
import { ChartCategory, ChartValueFormatter, DEFAULT_VALUE_FORMATTER } from './chart-model';

const WIDTH = 640;
const HEIGHT = 220;
const PAD = { top: 16, right: 16, bottom: 32, left: 48 };
const PLOT_WIDTH = WIDTH - PAD.left - PAD.right;
const PLOT_HEIGHT = HEIGHT - PAD.top - PAD.bottom;
const Y_TICKS = 3;
const BAR_GAP = 6;

interface Bar {
  readonly key: string;
  readonly label: string;
  readonly value: string;
  readonly color: string;
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

interface Tick {
  readonly value: number;
  readonly y: number;
  readonly label: string;
}

interface HoverTip {
  readonly cx: number;
  readonly cy: number;
  readonly label: string;
  readonly value: string;
}

/**
 * A histogram over a fixed, ordered set of buckets (IA X.19) — built for
 * `sla_bucket_set.v1`'s six fixed handover-time buckets
 * (`branch-sla-report-page.ts`), the console's one honest ordinal series.
 *
 * Colour is the sequential ramp, not the categorical palette: this is one
 * series (order count) read across six *ordered* tiers, not six unrelated
 * categories, so the data-viz procedure's ordinal case applies — bars darken
 * as the bucket gets slower, a redundant-but-legible cue alongside the axis
 * position. No legend: a single series names itself in the chart's own
 * title, per the data-viz procedure's own rule.
 */
@Component({
  selector: 'q-histogram-chart',
  imports: [ChartFrame, TPipe],
  templateUrl: './histogram-chart.html',
  styleUrl: './histogram-chart.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HistogramChart {
  readonly buckets = input.required<readonly ChartCategory[]>();
  readonly ariaLabel = input.required<string>();
  readonly valueFormatter = input<ChartValueFormatter>(DEFAULT_VALUE_FORMATTER);

  protected readonly width = WIDTH;
  protected readonly height = HEIGHT;
  protected readonly plotLeft = PAD.left;
  protected readonly plotTop = PAD.top;
  protected readonly plotWidth = PLOT_WIDTH;
  protected readonly plotHeight = PLOT_HEIGHT;

  protected readonly hover = signal<HoverTip | null>(null);

  private readonly maxValue = computed(() => Math.max(1, ...this.buckets().map((b) => b.value)));

  protected readonly bars = computed<readonly Bar[]>(() => {
    const max = this.maxValue();
    const buckets = this.buckets();
    const n = buckets.length;
    const slot = n > 0 ? PLOT_WIDTH / n : PLOT_WIDTH;
    const barWidth = Math.max(4, slot - BAR_GAP);
    return buckets.map((bucket, index) => {
      const barHeight = (bucket.value / max) * PLOT_HEIGHT;
      return {
        key: bucket.key,
        label: bucket.label,
        value: this.valueFormatter()(bucket.value),
        color: sequentialColorVar(index, n),
        x: slot * index + BAR_GAP / 2,
        y: PLOT_HEIGHT - barHeight,
        width: barWidth,
        height: barHeight,
      };
    });
  });

  protected readonly yTicks = computed<readonly Tick[]>(() => {
    const max = this.maxValue();
    const step = max / Y_TICKS;
    return Array.from({ length: Y_TICKS + 1 }, (_unused, i) => {
      const value = step * i;
      return {
        value,
        y: PLOT_HEIGHT - (value / max) * PLOT_HEIGHT,
        label: this.valueFormatter()(value),
      };
    }).reverse();
  });

  protected showHover(bar: Bar): void {
    this.hover.set({ cx: bar.x + bar.width / 2, cy: bar.y, label: bar.label, value: bar.value });
  }

  protected clearHover(): void {
    this.hover.set(null);
  }
}
