import { CdkDragDrop, CdkDropList } from '@angular/cdk/drag-drop';
import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

/** A column's own load, past which it reads as full or over (`3.1`: the drop target's own carrying load). */
export type BoardColumnLoadState = 'none' | 'ok' | 'full' | 'over';

/** One card moved into (or within) this column. `card` is whatever {@link CdkDrag}'s `dragData` carried. */
export interface BoardColumnDropEvent<TCard> {
  readonly card: TCard;
  readonly fromColumnId: string;
  readonly toColumnId: string;
}

/**
 * One column of a `q-board` (`X.21`) — a courier, or the dispatch board's own
 * "Unassigned" pool. Owns exactly one `cdkDropList`; `q-board`'s
 * `cdkDropListGroup` connects it to every sibling column automatically, so
 * this component never needs to know another column's id.
 *
 * The header shows {@link current} against {@link capacity} — dispatch's
 * `activeAssignments`/`concurrencyCeiling` — because IA `X.21`'s own gap is
 * exactly that nothing today shows a courier's load at the point a
 * dispatcher is about to drop one more order onto them. `capacity`
 * `undefined` hides the badge outright (a column that is not a courier);
 * `null` shows a bare count with no ceiling (the unassigned pool, so an
 * empty queue is visibly empty rather than silently uncounted).
 */
@Component({
  selector: 'q-board-column',
  imports: [CdkDropList],
  templateUrl: './board-column.html',
  styleUrl: './board-column.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BoardColumn<TCard = unknown> {
  readonly columnId = input.required<string>();
  readonly titleText = input.required<string>();
  readonly current = input<number | undefined>(undefined);
  readonly capacity = input<number | null | undefined>(undefined);

  /** A courier a dispatcher should not drop onto right now — suspended, expired document, etc. */
  readonly dropDisabled = input(false);

  readonly dropped = output<BoardColumnDropEvent<TCard>>();

  protected readonly loadState = computed<BoardColumnLoadState>(() => {
    const capacity = this.capacity();
    const current = this.current();
    if (capacity === undefined || current === undefined) {
      return 'none';
    }
    if (capacity === null) {
      return 'ok';
    }
    if (current > capacity) {
      return 'over';
    }
    if (current === capacity) {
      return 'full';
    }
    return 'ok';
  });

  protected readonly loadLabel = computed<string | null>(() => {
    const current = this.current();
    if (current === undefined) {
      return null;
    }
    const capacity = this.capacity();
    return capacity === null || capacity === undefined ? `${current}` : `${current} / ${capacity}`;
  });

  protected onDropped(event: CdkDragDrop<string, string, TCard>): void {
    this.dropped.emit({
      card: event.item.data,
      fromColumnId: event.previousContainer.data,
      toColumnId: event.container.data,
    });
  }
}
