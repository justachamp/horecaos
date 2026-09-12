import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

import { TPipe } from '../../../core/i18n/t.pipe';
import { ChartFrame } from './chart-frame';
import { categoricalColorVar } from './chart-colors';
import { ChartCategory, ChartValueFormatter, DEFAULT_VALUE_FORMATTER } from './chart-model';

const SIZE = 160;
const CENTER = SIZE / 2;
const RADIUS = 60;
const STROKE_WIDTH = 24;
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;
const GAP = 2; // px of visible surface between adjacent slices

interface Slice {
  readonly key: string;
  readonly label: string;
  readonly value: string;
  readonly sharePercent: number;
  readonly color: string;
  readonly dashArray: string;
  readonly dashOffset: number;
}

/**
 * A composition-of-a-whole chart over a fixed category set (IA X.19) — a
 * channel mix, a fulfilment mix. Built from stroked circles
 * (`stroke-dasharray`/`stroke-dashoffset`), not path arc geometry — the
 * standard, reliable technique for an SVG donut that needs no trigonometry
 * of its own.
 *
 * No axis: a share-of-whole chart has no scale to tick — the slice angles
 * and the legend's own percentages are the encoding. `centerLabel` is the
 * one number worth anchoring in the middle (the period's total), which is
 * also this chart's answer to "the title already names it" for a
 * single-metric view.
 */
@Component({
  selector: 'q-donut-chart',
  imports: [ChartFrame, TPipe],
  templateUrl: './donut-chart.html',
  styleUrl: './donut-chart.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DonutChart {
  readonly segments = input.required<readonly ChartCategory[]>();
  readonly ariaLabel = input.required<string>();
  readonly centerLabel = input<string | null>(null);
  readonly valueFormatter = input<ChartValueFormatter>(DEFAULT_VALUE_FORMATTER);

  protected readonly size = SIZE;
  protected readonly center = CENTER;
  protected readonly radius = RADIUS;
  protected readonly strokeWidth = STROKE_WIDTH;

  protected readonly hover = signal<Slice | null>(null);

  private readonly total = computed(() => {
    const sum = this.segments().reduce((acc, s) => acc + s.value, 0);
    return sum > 0 ? sum : 1;
  });

  protected readonly slices = computed<readonly Slice[]>(() => {
    const total = this.total();
    let offset = 0;
    return this.segments().map((segment, index) => {
      const length = Math.max(0, (segment.value / total) * CIRCUMFERENCE - GAP);
      const slice: Slice = {
        key: segment.key,
        label: segment.label,
        value: this.valueFormatter()(segment.value),
        sharePercent: Math.round((segment.value / total) * 100),
        color: categoricalColorVar(index),
        dashArray: `${length} ${CIRCUMFERENCE - length}`,
        dashOffset: -offset,
      };
      offset += (segment.value / total) * CIRCUMFERENCE;
      return slice;
    });
  });

  protected showHover(slice: Slice): void {
    this.hover.set(slice);
  }

  protected clearHover(): void {
    this.hover.set(null);
  }
}
