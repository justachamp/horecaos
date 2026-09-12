import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

import { ChartFrame } from './chart-frame';
import { categoricalColorVar } from './chart-colors';
import { ChartSeries, ChartValueFormatter, DEFAULT_VALUE_FORMATTER } from './chart-model';

const WIDTH = 640;
const HEIGHT = 220;
const PAD = { top: 16, right: 16, bottom: 28, left: 48 };
const PLOT_WIDTH = WIDTH - PAD.left - PAD.right;
const PLOT_HEIGHT = HEIGHT - PAD.top - PAD.bottom;
const Y_TICKS = 4;

interface PlottedPoint {
  readonly cx: number;
  readonly cy: number | null;
  readonly rawY: number | null;
}

interface PlottedSeries {
  readonly key: string;
  readonly label: string;
  readonly color: string;
  readonly path: string;
  readonly points: readonly PlottedPoint[];
}

interface XTick {
  readonly index: number;
  readonly cx: number;
  readonly label: string;
}

interface HoverTip {
  readonly index: number;
  readonly cx: number;
  readonly label: string;
  readonly rows: readonly {
    readonly label: string;
    readonly color: string;
    readonly value: string;
  }[];
}

/**
 * A multi-series line chart over `/reporting/queries`' per-day rows (IA
 * X.19) — the one shape this console had none of before: business-overview
 * always collapsed the day axis away (`report-rollup.ts`'s `sumTotal`,
 * `dailySeries`'s own doc) rather than ever drawing it.
 *
 * A crosshair-style tooltip, not a per-point one: hovering any x position
 * shows every series' value at that x together, which is what a reader
 * comparing two lines actually wants (the data-viz procedure's own
 * recommendation for line/area). The tooltip is a mouse affordance only —
 * see `chart-frame.ts` for why the graphic itself carries no keyboard path
 * and the accessible table beside it is the real equivalent.
 */
@Component({
  selector: 'q-line-chart',
  imports: [ChartFrame],
  templateUrl: './line-chart.html',
  styleUrl: './line-chart.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LineChart {
  readonly series = input.required<readonly ChartSeries[]>();
  readonly ariaLabel = input.required<string>();
  readonly valueFormatter = input<ChartValueFormatter>(DEFAULT_VALUE_FORMATTER);

  protected readonly width = WIDTH;
  protected readonly height = HEIGHT;
  protected readonly plotLeft = PAD.left;
  protected readonly plotTop = PAD.top;
  protected readonly plotWidth = PLOT_WIDTH;
  protected readonly plotHeight = PLOT_HEIGHT;

  protected readonly hover = signal<HoverTip | null>(null);

  protected readonly xLabelsList = computed(() => this.series()[0]?.points.map((p) => p.x) ?? []);

  private readonly domain = computed(() => {
    const values = this.series().flatMap((s) => s.points.map((p) => p.y).filter(isNumber));
    const min = Math.min(0, ...values);
    const max = values.length > 0 ? Math.max(...values) : 1;
    return { min, max: max === min ? min + 1 : max };
  });

  protected readonly yTicks = computed(() => {
    const { min, max } = this.domain();
    const step = (max - min) / Y_TICKS;
    return Array.from({ length: Y_TICKS + 1 }, (_unused, i) => {
      const value = min + step * i;
      return { value, y: this.yToPixel(value), label: this.valueFormatter()(value) };
    }).reverse();
  });

  protected readonly xTicks = computed<readonly XTick[]>(() => {
    const labels = this.xLabelsList();
    const n = labels.length;
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
      .map((index) => ({
        index,
        cx: this.xToPixel(index),
        label: labels[index],
      }));
  });

  protected readonly plotted = computed<readonly PlottedSeries[]>(() =>
    this.series().map((s, seriesIndex) => {
      const points = s.points.map((point, i) => ({
        cx: this.xToPixel(i),
        cy: point.y === null ? null : this.yToPixel(point.y),
        rawY: point.y,
      }));
      return {
        key: s.key,
        label: s.label,
        color: categoricalColorVar(seriesIndex),
        path: buildPathD(points),
        points,
      };
    }),
  );

  protected readonly showLegend = computed(() => this.series().length > 1);

  /** One hover hit-strip per data point (every point, not only the labelled ticks). */
  protected readonly hitPoints = computed(() =>
    this.xLabelsList().map((_label, index) => ({ index, cx: this.xToPixel(index) })),
  );

  private xToPixel(index: number): number {
    const n = this.xLabelsList().length;
    if (n <= 1) {
      return this.plotWidth / 2;
    }
    return (index / (n - 1)) * this.plotWidth;
  }

  private yToPixel(value: number): number {
    const { min, max } = this.domain();
    const t = (value - min) / (max - min);
    return this.plotHeight - t * this.plotHeight;
  }

  /** The invisible hover strip's half-width — half the gap to the next point, capped so strips near a sparse axis do not overlap. */
  protected hitWidth(): number {
    const n = this.xLabelsList().length;
    return n <= 1 ? this.plotWidth : this.plotWidth / (n - 1) / 2;
  }

  protected showHoverAt(index: number): void {
    const cx = this.xToPixel(index);
    const label = this.xLabelsList()[index];
    const rows = this.plotted()
      .map((s) => {
        const rawY = s.points[index]?.rawY;
        return rawY === null || rawY === undefined
          ? null
          : { label: s.label, color: s.color, value: this.valueFormatter()(rawY) };
      })
      .filter((r): r is { label: string; color: string; value: string } => r !== null);
    this.hover.set({ index, cx, label, rows });
  }

  protected clearHover(): void {
    this.hover.set(null);
  }
}

function isNumber(value: number | null): value is number {
  return value !== null && Number.isFinite(value);
}

/** SVG path `d` through the points, breaking into a new subpath after every `null` (a gap, never a false zero). */
function buildPathD(
  points: readonly { readonly cx: number; readonly cy: number | null }[],
): string {
  let d = '';
  let drawing = false;
  for (const point of points) {
    if (point.cy === null) {
      drawing = false;
      continue;
    }
    d += `${drawing ? 'L' : 'M'}${point.cx.toFixed(1)},${point.cy.toFixed(1)} `;
    drawing = true;
  }
  return d.trim();
}
