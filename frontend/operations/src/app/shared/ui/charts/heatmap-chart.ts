import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';

import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ChartFrame } from './chart-frame';
import { CHART_SEQUENTIAL_SLOTS, sequentialColorVar } from './chart-colors';
import { ChartHeatRow, ChartValueFormatter, DEFAULT_VALUE_FORMATTER } from './chart-model';

const WIDTH = 640;
const ROW_LABEL_WIDTH = 64;
const ROW_HEIGHT = 24;
const CELL_GAP = 1;
const TOP_PAD = 8;
const BOTTOM_PAD = 20;

interface Cell {
  readonly rowKey: string;
  readonly colKey: string;
  readonly rowLabel: string;
  readonly colLabel: string;
  readonly value: number | null;
  readonly color: string;
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

interface HoverTip {
  readonly x: number;
  readonly y: number;
  readonly rowLabel: string;
  readonly colLabel: string;
  readonly text: string;
}

/**
 * A two-dimensional heatmap (IA X.19) — hour-of-day demand
 * (`/reporting/demand-history`) by weekday, the shape `demand-forecast-page`
 * could only ever show one row of at a time before this (a weekday picker
 * over a 24-hour table).
 *
 * Colour is the sequential ramp, scaled against the *whole grid's* domain —
 * comparing a Monday 18:00 cell against a Tuesday 09:00 cell only means
 * something if both were shaded from the same scale, not a scale local to
 * their own row. `value: null` (`HourDemandResponse.averageOrders`'s own
 * honesty rule — a sample thinner than the location's minimum) renders as a
 * hatched, uncoloured cell, never a fabricated shade of the lowest step.
 */
@Component({
  selector: 'q-heatmap-chart',
  imports: [ChartFrame, TPipe],
  templateUrl: './heatmap-chart.html',
  styleUrl: './heatmap-chart.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HeatmapChart {
  private readonly i18n = inject(I18n);

  readonly rows = input.required<readonly ChartHeatRow[]>();
  readonly ariaLabel = input.required<string>();
  readonly valueFormatter = input<ChartValueFormatter>(DEFAULT_VALUE_FORMATTER);

  protected readonly width = WIDTH;
  protected readonly rowLabelWidth = ROW_LABEL_WIDTH;

  protected readonly height = computed(
    () => this.rows().length * ROW_HEIGHT + TOP_PAD + BOTTOM_PAD,
  );

  protected readonly colLabels = computed(() => this.rows()[0]?.cells.map((c) => c.label) ?? []);

  private readonly domain = computed(() => {
    const values = this.rows()
      .flatMap((row) => row.cells.map((cell) => cell.value))
      .filter((v): v is number => v !== null);
    return { min: Math.min(0, ...values), max: values.length > 0 ? Math.max(...values) : 1 };
  });

  protected readonly cells = computed<readonly Cell[]>(() => {
    const rows = this.rows();
    const colCount = rows[0]?.cells.length ?? 0;
    const colWidth = colCount > 0 ? (WIDTH - ROW_LABEL_WIDTH) / colCount : 0;
    const { min, max } = this.domain();
    return rows.flatMap((row, rowIndex) =>
      row.cells.map((cell, colIndex): Cell => {
        const rank =
          cell.value === null || max === min
            ? 0
            : Math.round(((cell.value - min) / (max - min)) * (CHART_SEQUENTIAL_SLOTS - 1));
        return {
          rowKey: row.key,
          colKey: cell.key,
          rowLabel: row.label,
          colLabel: cell.label,
          value: cell.value,
          color: cell.value === null ? '' : sequentialColorVar(rank, CHART_SEQUENTIAL_SLOTS),
          x: ROW_LABEL_WIDTH + colIndex * colWidth + CELL_GAP / 2,
          y: TOP_PAD + rowIndex * ROW_HEIGHT + CELL_GAP / 2,
          width: Math.max(0, colWidth - CELL_GAP),
          height: ROW_HEIGHT - CELL_GAP,
        };
      }),
    );
  });

  protected readonly rowLabelPositions = computed(() =>
    this.rows().map((row, index) => ({
      key: row.key,
      label: row.label,
      y: TOP_PAD + index * ROW_HEIGHT + ROW_HEIGHT / 2,
    })),
  );

  protected readonly hover = signal<HoverTip | null>(null);

  protected showHover(cell: Cell): void {
    const text = cell.value === null ? this.noDataLabel() : this.valueFormatter()(cell.value);
    this.hover.set({
      x: cell.x + cell.width / 2,
      y: cell.y,
      rowLabel: cell.rowLabel,
      colLabel: cell.colLabel,
      text,
    });
  }

  protected clearHover(): void {
    this.hover.set(null);
  }

  private noDataLabel(): string {
    return this.i18n.t('ui.charts.tooltip.noData');
  }
}
