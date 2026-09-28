import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

export interface TimelineResource {
  readonly id: string;
  readonly label: string;
}

export interface TimelineBlock {
  readonly id: string;
  readonly resourceId: string;
  /** Minutes since {@link TimelineScheduler.startOfDayMinutes}'s own zero point (never a wall-clock instant). */
  readonly startMinutes: number;
  readonly endMinutes: number;
  readonly label: string;
  /**
   * This block overlaps another block on the same resource's row (row
   * `X.36`'s "capacity conflicts highlighted"). The scheduler only renders
   * the flag; deciding which blocks overlap is the consumer's own read of
   * its data — `reservations-page.ts`'s day timeline computes it from two
   * bookings holding the same table over intersecting windows, a state the
   * host stand's own advisory availability read already allows before a
   * booking is confirmed.
   */
  readonly conflict?: boolean;
}

interface PositionedBlock extends TimelineBlock {
  readonly leftPx: number;
  readonly widthPx: number;
}

/**
 * `X.36` TimelineScheduler — one row per resource, blocks positioned along a
 * shared time axis, built alongside `FloorPlanCanvas`/`TableToken` in wave
 * P38 per the gap map's own instruction that all three land together.
 *
 * **A display component, not an editor.** The gap map names this row for
 * "can I fit a party of six at 20:00", a question a host answers by
 * *reading* a room's bookings across time, not by dragging one — that
 * belongs to whatever screen actually creates and moves a reservation. This
 * component only lays out `blocks()` against `resources()` and reports which
 * one a host clicked; a host that wants a drag or a resize composes its own
 * handler around it, the same separation `q-floor-plan-canvas` draws between
 * "the drag happened" and "the server accepted it".
 *
 * **Now has a live consumer**: `reservations-page.ts`'s day view (IA 1.5),
 * a table-as-row/booking-as-block read of the same `tables()`/
 * `reservations()` its existing hour grid already loads, toggled alongside
 * it rather than replacing it. `blockSelect` is what lets that screen open
 * its own existing detail pane — seat/complete and every other action stay
 * exactly the endpoints the grid already calls; this component knows
 * nothing about a booking beyond the block it was handed.
 */
@Component({
  selector: 'q-timeline-scheduler',
  templateUrl: './timeline-scheduler.html',
  styleUrl: './timeline-scheduler.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TimelineScheduler {
  readonly resources = input.required<readonly TimelineResource[]>();
  readonly blocks = input.required<readonly TimelineBlock[]>();
  readonly startOfDayMinutes = input(0);
  readonly endOfDayMinutes = input(24 * 60);
  readonly pixelsPerMinute = input(1.5);

  /** Emits a clicked block's own `id` — never the block itself, so a consumer looks its row up from the data it already holds. */
  readonly blockSelect = output<string>();

  protected readonly totalWidthPx = computed(
    () => (this.endOfDayMinutes() - this.startOfDayMinutes()) * this.pixelsPerMinute(),
  );

  protected readonly blocksByResource = computed<ReadonlyMap<string, readonly PositionedBlock[]>>(
    () => {
      const start = this.startOfDayMinutes();
      const ppm = this.pixelsPerMinute();
      const byResource = new Map<string, PositionedBlock[]>();
      for (const block of this.blocks()) {
        const positioned: PositionedBlock = {
          ...block,
          leftPx: (block.startMinutes - start) * ppm,
          widthPx: Math.max(4, (block.endMinutes - block.startMinutes) * ppm),
        };
        const bucket = byResource.get(block.resourceId);
        if (bucket) {
          bucket.push(positioned);
        } else {
          byResource.set(block.resourceId, [positioned]);
        }
      }
      return byResource;
    },
  );

  protected blocksFor(resourceId: string): readonly PositionedBlock[] {
    return this.blocksByResource().get(resourceId) ?? [];
  }

  protected onBlockClick(blockId: string): void {
    this.blockSelect.emit(blockId);
  }
}
