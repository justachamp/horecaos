import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

const WIDTH = 72;
const HEIGHT = 24;
const STROKE_INSET = 2;

/**
 * The compact trend line inside a `q-kpi-tile` (IA X.20) — the sparkline
 * proper, in Tufte's own sense: no axis, no legend, no gridlines, because a
 * sparkline's whole point is a shape a reader takes in without reading it as
 * a chart. That is why it is a separate component from `q-line-chart` rather
 * than that chart's tiniest configuration — the two have almost nothing in
 * common once the chrome is gone.
 *
 * It is `aria-hidden`: the tile's own value and delta text (in `kpi-tile.ts`)
 * already state the number a sighted reader gets from the shape, so
 * announcing the same fact twice would be the redundant-encoding failure the
 * data-viz procedure warns against, not an accessibility improvement. Gaps
 * (`null`) are dropped from the drawn path — a compact trend line reads the
 * shape of the points it has, not the days it is missing.
 */
@Component({
  selector: 'q-sparkline',
  templateUrl: './sparkline.html',
  styleUrl: './sparkline.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Sparkline {
  readonly points = input.required<readonly (number | null)[]>();

  protected readonly width = WIDTH;
  protected readonly height = HEIGHT;

  private readonly plottable = computed(() => this.points().filter(isNumber));

  protected readonly path = computed(() => {
    const values = this.plottable();
    if (values.length < 2) {
      return '';
    }
    const min = Math.min(...values);
    const max = Math.max(...values);
    const span = max - min || 1;
    return values
      .map((value, index) => {
        const x = STROKE_INSET + (index / (values.length - 1)) * (WIDTH - STROKE_INSET * 2);
        const y = HEIGHT - STROKE_INSET - ((value - min) / span) * (HEIGHT - STROKE_INSET * 2);
        return `${index === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`;
      })
      .join(' ');
  });

  protected readonly hasTrend = computed(() => this.plottable().length >= 2);
}

function isNumber(value: number | null): value is number {
  return value !== null && Number.isFinite(value);
}
