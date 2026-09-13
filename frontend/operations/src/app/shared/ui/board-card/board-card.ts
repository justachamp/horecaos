import { CdkDrag } from '@angular/cdk/drag-drop';
import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * One draggable item on a `q-board` (`X.21`). Carries whatever the caller's
 * content projects — `q-drag-drop-assign` projects a plan's own row content
 * (order number, status, fee), never anything about the card's shape itself,
 * so this stays reusable past the dispatch board.
 *
 * `dragData` is what a `q-board-column`'s `(dropped)` event hands back as
 * `card` — it must be the same object the host's `columnIdOf`/`cardId`
 * functions know how to read.
 */
@Component({
  selector: 'q-board-card',
  imports: [CdkDrag],
  templateUrl: './board-card.html',
  styleUrl: './board-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BoardCard<TCard = unknown> {
  readonly dragData = input.required<TCard>();
  readonly dragDisabled = input(false);
}
