import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

import { TPipe } from '../../../core/i18n/t.pipe';
import { ChartFrame } from './chart-frame';
import { sequentialColorVar } from './chart-colors';
import { ChartValueFormatter, DEFAULT_VALUE_FORMATTER } from './chart-model';

/** One stage of the funnel's main path — ordered, each expected to be no larger than the one before it. */
export interface FunnelStage {
  readonly key: string;
  readonly label: string;
  readonly value: number;
}

/**
 * A labelled drop-off beside a stage (statistics.md §2.1 Band D) — an order
 * that reached {@link fromStageKey} but never continued to the stage after
 * it. Never a stage of the main path itself: a drop-off's own width reads
 * against the stage it left, not against the funnel's first stage, which is
 * what keeps a small branch visually small rather than competing with the
 * path for scale.
 */
export interface FunnelDropOff {
  readonly key: string;
  readonly label: string;
  readonly value: number;
  readonly fromStageKey: string;
}

interface Bar {
  readonly key: string;
  readonly label: string;
  readonly value: string;
  readonly sharePercent: number;
  readonly color: string;
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

interface DropOffRow {
  readonly key: string;
  readonly label: string;
  readonly value: string;
  /** Share of the stage it branched from — never of the funnel's first stage. */
  readonly sharePercent: number;
  readonly y: number;
}

interface HoverTip {
  readonly x: number;
  readonly y: number;
  readonly label: string;
  readonly value: string;
}

const WIDTH = 640;
const LABEL_WIDTH = 170;
const VALUE_WIDTH = 130;
const STAGE_HEIGHT = 36;
const STAGE_GAP = 10;
const DROP_ROW_HEIGHT = 18;
const DROP_GAP = 6;
const TOP_PAD = 8;
const BOTTOM_PAD = 8;

/**
 * The sales-funnel chart (IA X.19, wave 8 w7-reports 7.1a) — an ordered main
 * path of narrowing stages (`stages`, first-to-last), each optionally
 * carrying its own labelled drop-offs (`dropOffs`) for what left the funnel
 * between it and the stage after it. Reused for the business overview's
 * «Воронка по финальному статусу» — completed vs. not, on-time vs. late —
 * rather than a bespoke chart, so a second funnel elsewhere in the console
 * costs a data translation, not a new component.
 *
 * A drop-off's bar width and share are relative to the stage it left, never
 * to the funnel's first stage: four cancelled orders out of four hundred
 * total reads as noise at the funnel's own scale but is the whole story at
 * the stage it actually happened.
 */
@Component({
  selector: 'q-funnel-chart',
  imports: [ChartFrame, TPipe],
  templateUrl: './funnel-chart.html',
  styleUrl: './funnel-chart.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FunnelChart {
  readonly stages = input.required<readonly FunnelStage[]>();
  readonly dropOffs = input<readonly FunnelDropOff[]>([]);
  readonly ariaLabel = input.required<string>();
  readonly valueFormatter = input<ChartValueFormatter>(DEFAULT_VALUE_FORMATTER);

  protected readonly width = WIDTH;
  protected readonly hover = signal<HoverTip | null>(null);

  private readonly firstStageValue = computed(() => {
    const first = this.stages().at(0);
    return first && first.value > 0 ? first.value : 1;
  });

  /** {@link DropOffRow}s grouped under the stage they left, in `dropOffs`' own order. */
  private readonly dropOffsByStage = computed(() => {
    const byStage = new Map<string, FunnelDropOff[]>();
    for (const dropOff of this.dropOffs()) {
      const list = byStage.get(dropOff.fromStageKey) ?? [];
      list.push(dropOff);
      byStage.set(dropOff.fromStageKey, list);
    }
    return byStage;
  });

  protected readonly bars = computed<readonly Bar[]>(() => {
    const basis = this.firstStageValue();
    const trackWidth = WIDTH - LABEL_WIDTH - VALUE_WIDTH;
    const format = this.valueFormatter();
    const byStage = this.dropOffsByStage();

    let y = TOP_PAD;
    const result: Bar[] = [];
    this.stages().forEach((stage, index) => {
      const barWidth = Math.max(2, trackWidth * (stage.value / basis));
      result.push({
        key: stage.key,
        label: stage.label,
        value: format(stage.value),
        sharePercent: Math.round((stage.value / basis) * 100),
        color: sequentialColorVar(index, Math.max(1, this.stages().length)),
        x: LABEL_WIDTH + (trackWidth - barWidth) / 2,
        y,
        width: barWidth,
        height: STAGE_HEIGHT,
      });
      y += STAGE_HEIGHT + STAGE_GAP;
      const dropOffs = byStage.get(stage.key) ?? [];
      if (dropOffs.length > 0) {
        y += dropOffs.length * DROP_ROW_HEIGHT + DROP_GAP;
      }
    });
    return result;
  });

  /** One row per drop-off, positioned directly under the stage it branched from. */
  protected readonly dropOffRows = computed<readonly DropOffRow[]>(() => {
    const format = this.valueFormatter();
    const byStage = this.dropOffsByStage();

    let y = TOP_PAD;
    const rows: DropOffRow[] = [];
    this.stages().forEach((stage) => {
      y += STAGE_HEIGHT + STAGE_GAP;
      const dropOffs = byStage.get(stage.key) ?? [];
      const stageBasis = stage.value > 0 ? stage.value : 1;
      dropOffs.forEach((dropOff) => {
        rows.push({
          key: dropOff.key,
          label: dropOff.label,
          value: format(dropOff.value),
          sharePercent: Math.round((dropOff.value / stageBasis) * 100),
          y,
        });
        y += DROP_ROW_HEIGHT;
      });
      if (dropOffs.length > 0) {
        y += DROP_GAP;
      }
    });
    return rows;
  });

  protected readonly height = computed(
    () =>
      TOP_PAD +
      BOTTOM_PAD +
      this.stages().reduce((sum, stage) => {
        const dropOffs = this.dropOffsByStage().get(stage.key) ?? [];
        const dropOffHeight =
          dropOffs.length > 0 ? dropOffs.length * DROP_ROW_HEIGHT + DROP_GAP : 0;
        return sum + STAGE_HEIGHT + STAGE_GAP + dropOffHeight;
      }, 0),
  );

  protected showHover(bar: Bar): void {
    this.hover.set({ x: bar.x + bar.width / 2, y: bar.y, label: bar.label, value: bar.value });
  }

  protected clearHover(): void {
    this.hover.set(null);
  }
}
