import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

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
}

interface PositionedBlock extends TimelineBlock {
  readonly leftPx: number;
  readonly widthPx: number;
}

/**
 * `X.36` TimelineScheduler — one row per resource, blocks positioned along a
 * shared time axis, built alongside `FloorPlanCanvas`/`TableToken` this wave
 * (P38) per the gap map's own instruction that all three land together.
 *
 * **A display component, not an editor.** The gap map names this row for
 * "can I fit a party of six at 20:00", a question a host answers by
 * *reading* a room's bookings across time, not by dragging one — that
 * belongs to whatever screen actually creates and moves a reservation. This
 * component only lays out `blocks()` against `resources()`; a host that
 * wants interaction composes its own click handlers around it, the same
 * separation `q-floor-plan-canvas` draws between "the drag happened" and
 * "the server accepted it".
 *
 * **No live consumer yet, and that is named rather than hidden.** This
 * row's own natural home is the reservations day view (IA 1.5), a screen
 * outside this wave's brief — the same "built and spec'd, no call site yet"
 * shape several other `X.*` rows already carry (see `q-phone-frame`'s
 * siblings). `q-floor-plan-canvas`/`q-table-token` are what this wave's own
 * Settings → Locations screen actually uses.
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
}
