import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

import { TPipe } from '../../../core/i18n/t.pipe';
import { ChartFrame } from './chart-frame';
import { categoricalColorVar } from './chart-colors';

const WIDTH = 640;
const HEIGHT = 220;
const PAD = { top: 16, right: 16, bottom: 28, left: 40 };
const PLOT_WIDTH = WIDTH - PAD.left - PAD.right;
const PLOT_HEIGHT = HEIGHT - PAD.top - PAD.bottom;
const Y_TICK_VALUES: readonly number[] = [0, 25, 50, 75, 100];

/** One product's rank and cumulative position on the ABC curve — see this file's own doc. */
export interface AbcCurvePoint {
  readonly key: string;
  readonly label: string;
  readonly sharePercent: number;
  readonly cumulativeSharePercent: number;
  readonly abcClass: 'A' | 'B' | 'C';
}

interface PlottedPoint {
  readonly key: string;
  readonly label: string;
  readonly cx: number;
  readonly cy: number;
  readonly sharePercent: number;
  readonly cumulativeSharePercent: number;
  readonly abcClass: 'A' | 'B' | 'C';
  readonly color: string;
}

interface XTick {
  readonly index: number;
  readonly cx: number;
  readonly label: string;
}

interface HoverTip {
  readonly cx: number;
  readonly cy: number;
  readonly label: string;
  readonly value: string;
  readonly abcClass: string;
}

/** A→B and B→C, the two published boundaries {@link AbcCurveChart} draws as dashed reference lines. */
const CLASS_COLOR_INDEX: Readonly<Record<'A' | 'B' | 'C', number>> = { A: 0, B: 1, C: 2 };

/**
 * X.19 (w6-reporting-facts, batch 11): the ABC cumulative-revenue-share
 * curve — one point per product, already ranked revenue-descending by the
 * caller, climbing from 0% to 100% of the range's visible total.
 * `thresholdAPercent`/`thresholdBPercent` draw the two dashed reference
 * lines a manager reads the class boundaries off:
 * `ClassificationThresholds.DEFAULT`'s published 80/95 split, the one this
 * build draws everywhere else (7.7a/7.7b's own ABC/XYZ matrix and its
 * `classificationCaption`).
 *
 * Each point is coloured by its own class rather than the curve being one
 * flat line — the same "colour is a redundant, legible cue" rule
 * `histogram-chart.ts` states for its own ordinal series, applied here to a
 * class instead of a bucket rank. No legend text beyond the three swatches:
 * the reference lines' own labels already name what A/B/C mean.
 */
@Component({
  selector: 'q-abc-curve',
  imports: [ChartFrame, TPipe],
  templateUrl: './abc-curve-chart.html',
  styleUrl: './abc-curve-chart.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AbcCurveChart {
  readonly points = input.required<readonly AbcCurvePoint[]>();
  readonly thresholdAPercent = input.required<number>();
  readonly thresholdBPercent = input.required<number>();
  readonly ariaLabel = input.required<string>();

  protected readonly width = WIDTH;
  protected readonly height = HEIGHT;
  protected readonly plotLeft = PAD.left;
  protected readonly plotTop = PAD.top;
  protected readonly plotWidth = PLOT_WIDTH;
  protected readonly plotHeight = PLOT_HEIGHT;

  protected readonly hover = signal<HoverTip | null>(null);

  protected readonly yTicks = Y_TICK_VALUES.map((value) => ({
    value,
    y: PLOT_HEIGHT - (value / 100) * PLOT_HEIGHT,
  }));

  protected readonly thresholdLines = computed(() => [
    { key: 'a' as const, percent: this.thresholdAPercent(), y: this.yToPixel(this.thresholdAPercent()) },
    { key: 'b' as const, percent: this.thresholdBPercent(), y: this.yToPixel(this.thresholdBPercent()) },
  ]);

  protected readonly plotted = computed<readonly PlottedPoint[]>(() =>
    this.points().map((point, index) => ({
      key: point.key,
      label: point.label,
      cx: this.xToPixel(index),
      cy: this.yToPixel(point.cumulativeSharePercent),
      sharePercent: point.sharePercent,
      cumulativeSharePercent: point.cumulativeSharePercent,
      abcClass: point.abcClass,
      color: categoricalColorVar(CLASS_COLOR_INDEX[point.abcClass]),
    })),
  );

  protected readonly path = computed(() =>
    this.plotted()
      .map((p, i) => `${i === 0 ? 'M' : 'L'}${p.cx.toFixed(1)},${p.cy.toFixed(1)}`)
      .join(' '),
  );

  protected readonly xTicks = computed<readonly XTick[]>(() => {
    const n = this.points().length;
    const indices = new Set<number>();
    if (n <= 7) {
      for (let i = 0; i < n; i += 1) {
        indices.add(i);
      }
    } else {
      const stride = Math.ceil(n / 6);
      for (let i = 0; i < n; i += stride) {
        indices.add(i);
      }
      indices.add(n - 1);
    }
    return [...indices]
      .sort((a, b) => a - b)
      .map((index) => ({ index, cx: this.xToPixel(index), label: String(index + 1) }));
  });

  /** One invisible hover strip per point, the same shape `line-chart.ts` gives every data point. */
  protected readonly hitPoints = computed(() =>
    this.points().map((_point, index) => ({ index, cx: this.xToPixel(index) })),
  );

  protected hitWidth(): number {
    const n = this.points().length;
    return n <= 1 ? this.plotWidth : this.plotWidth / (n - 1) / 2;
  }

  private xToPixel(index: number): number {
    const n = this.points().length;
    if (n <= 1) {
      return this.plotWidth / 2;
    }
    return (index / (n - 1)) * this.plotWidth;
  }

  private yToPixel(percent: number): number {
    const clamped = Math.min(100, Math.max(0, percent));
    return this.plotHeight - (clamped / 100) * this.plotHeight;
  }

  protected showHoverAt(index: number): void {
    const p = this.plotted()[index];
    if (!p) {
      return;
    }
    this.hover.set({
      cx: p.cx,
      cy: p.cy,
      label: p.label,
      value: `${p.cumulativeSharePercent.toFixed(1)}%`,
      abcClass: p.abcClass,
    });
  }

  protected clearHover(): void {
    this.hover.set(null);
  }
}
