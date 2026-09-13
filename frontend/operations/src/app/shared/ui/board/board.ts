import { CdkDropListGroup } from '@angular/cdk/drag-drop';
import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * The board shell (`X.21`) — a horizontally-scrolling row of `q-board-column`s
 * and nothing else: no notion of a card, a courier, or an order. `dispatch-
 * board-page` is its first caller (`3.1`, courier-keyed columns), but nothing
 * here names a courier, so a future kitchen or table board can reuse it.
 *
 * The one thing this component owns is `cdkDropListGroup` — every
 * `q-board-column` dropped inside connects to every other one automatically,
 * so a card can move from any column to any other without either side
 * naming the other by id. The move itself, and what it means, is entirely
 * `q-drag-drop-assign`'s job (`X.22`).
 */
@Component({
  selector: 'q-board',
  imports: [CdkDropListGroup],
  templateUrl: './board.html',
  styleUrl: './board.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Board {}
