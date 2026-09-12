import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

import { ChartFrame } from './chart-frame';
import { categoricalColorVar } from './chart-colors';
import { ChartCategory, ChartValueFormatter, DEFAULT_VALUE_FORMATTER } from './chart-model';

export type BarChartOrientation = 'horizontal' | 'vertical';

/**
 * What a bar's length is a fraction *of*. `'max'` is the ordinary bar-chart
 * reading (the tallest bar is full length, every other a magnitude relative
 * to it) and the default. `'total'` reproduces the two retired
 * `[style.width.%]` implementations exactly — both `today-page.ts`'s
 * `mixShare` and `business-overview-page.ts`'s `channelBarWidth` sized a bar
 * as its share of the *sum* of every bar, which reads correctly only when the
 * categories partition a whole (a source mix, a channel mix) — see
 * `bar-chart.spec.ts`'s parity test.
 */
export type BarChartScale = 'max' | 'total';

const WIDTH = 640;
const Y_TICKS = 3;

// Horizontal layout — one ranked row per category (replaces the retired
// `mix-row`/`bar-row` `[style.width.%]` divs in `today-page.html` and
// `business-overview-page.html`).
const H_ROW_HEIGHT = 32;
const H_TOP_PAD = 8;
const H_BOTTOM_PAD = 24;
const H_LABEL_WIDTH = 150;
const H_VALUE_WIDTH = 56;
const H_GAP = 8;
const H_BAR_HEIGHT = 14;

// Vertical layout — a category per x position (a per-day count, say).
const V_HEIGHT = 220;
const V_PAD = { top: 16, right: 16, bottom: 28, left: 48 };

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
  readonly position: number;
  readonly label: string;
}

interface HoverTip {
  readonly x: number;
  readonly y: number;
  readonly label: string;
  readonly value: string;
}

/**
 * A categorical bar chart over `/reporting/queries` (IA X.19) — horizontal
 * when the categories are named things a reader reads left-to-right (a
 * channel, a source), vertical when they are a day axis. One component
 * either way: the data-viz procedure treats orientation as a layout
 * parameter of the same form, not a different chart type.
 *
 * Every category gets its own fixed categorical colour (`categoricalColorVar`)
 * — a deliberate improvement over the retired `mix-row`/`bar-row` divs this
 * replaces, which painted every bar the same brand blue and made the reader
 * do the discrimination the colour should have done. See
 * `bar-chart.spec.ts`'s "renders the same proportions" test for the
 * behavioural parity check the brief asks for.
 */
@Component({
  selector: 'q-bar-chart',
  imports: [ChartFrame],
  templateUrl: './bar-chart.html',
  styleUrl: './bar-chart.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BarChart {
  readonly items = input.required<readonly ChartCategory[]>();
  readonly ariaLabel = input.required<string>();
  readonly orientation = input<BarChartOrientation>('horizontal');
  readonly scale = input<BarChartScale>('max');
  readonly valueFormatter = input<ChartValueFormatter>(DEFAULT_VALUE_FORMATTER);

  protected readonly hover = signal<HoverTip | null>(null);
  protected readonly width = WIDTH;

  /** The value a full-length bar represents — the largest item, or the sum of all of them. */
  private readonly scaleBasis = computed(() => {
    const items = this.items();
    if (this.scale() === 'total') {
      const total = items.reduce((sum, item) => sum + item.value, 0);
      return total > 0 ? total : 1;
    }
    return Math.max(1, ...items.map((item) => item.value));
  });

  protected readonly height = computed(() =>
    this.orientation() === 'vertical'
      ? V_HEIGHT
      : this.items().length * H_ROW_HEIGHT + H_TOP_PAD + H_BOTTOM_PAD,
  );

  protected readonly bars = computed<readonly Bar[]>(() => {
    const basis = this.scaleBasis();
    return this.orientation() === 'vertical'
      ? this.verticalBars(basis)
      : this.horizontalBars(basis);
  });

  protected readonly ticks = computed<readonly Tick[]>(() => {
    const basis = this.scaleBasis();
    return this.orientation() === 'vertical'
      ? this.verticalTicks(basis)
      : this.horizontalTicks(basis);
  });

  private horizontalBars(max: number): readonly Bar[] {
    const trackWidth = WIDTH - H_LABEL_WIDTH - H_VALUE_WIDTH - H_GAP * 2;
    return this.items().map((item, index) => ({
      key: item.key,
      label: item.label,
      value: this.valueFormatter()(item.value),
      color: categoricalColorVar(index),
      x: H_LABEL_WIDTH + H_GAP,
      y: H_TOP_PAD + index * H_ROW_HEIGHT + (H_ROW_HEIGHT - H_BAR_HEIGHT) / 2,
      width: trackWidth * (item.value / max),
      height: H_BAR_HEIGHT,
    }));
  }

  private horizontalTicks(max: number): readonly Tick[] {
    const trackWidth = WIDTH - H_LABEL_WIDTH - H_VALUE_WIDTH - H_GAP * 2;
    return [0, 0.5, 1].map((fraction) => ({
      value: max * fraction,
      position: H_LABEL_WIDTH + H_GAP + trackWidth * fraction,
      label: this.valueFormatter()(max * fraction),
    }));
  }

  private verticalBars(max: number): readonly Bar[] {
    const plotWidth = WIDTH - V_PAD.left - V_PAD.right;
    const plotHeight = V_HEIGHT - V_PAD.top - V_PAD.bottom;
    const n = this.items().length;
    const slot = n > 0 ? plotWidth / n : plotWidth;
    const barWidth = Math.min(40, slot * 0.6);
    return this.items().map((item, index) => {
      const barHeight = (item.value / max) * plotHeight;
      return {
        key: item.key,
        label: item.label,
        value: this.valueFormatter()(item.value),
        color: categoricalColorVar(index),
        x: V_PAD.left + slot * index + (slot - barWidth) / 2,
        y: V_PAD.top + plotHeight - barHeight,
        width: barWidth,
        height: barHeight,
      };
    });
  }

  private verticalTicks(max: number): readonly Tick[] {
    const plotHeight = V_HEIGHT - V_PAD.top - V_PAD.bottom;
    const step = max / Y_TICKS;
    return Array.from({ length: Y_TICKS + 1 }, (_unused, i) => {
      const value = step * i;
      return {
        value,
        position: V_PAD.top + plotHeight - (value / max) * plotHeight,
        label: this.valueFormatter()(value),
      };
    }).reverse();
  }

  protected showHover(bar: Bar): void {
    const cx = this.orientation() === 'vertical' ? bar.x + bar.width / 2 : bar.x + bar.width;
    const cy = this.orientation() === 'vertical' ? bar.y : bar.y + bar.height / 2;
    this.hover.set({ x: cx, y: cy, label: bar.label, value: bar.value });
  }

  protected clearHover(): void {
    this.hover.set(null);
  }
}
