import { BoardColumnDef } from '../board/board-types';

/** A `q-drag-drop-assign` column — see {@link BoardColumnDef} for the field meanings. */
export type DragDropAssignColumn<TColumn = unknown> = BoardColumnDef<TColumn>;

/**
 * What `assignFn`/`unassignFn` resolve to: `DispatchController.DispatchResponse`'s
 * own shape (`applied`, and — only when `false` — `reason`), generalized past
 * dispatch. `applied: false` is a refusal, not a request failure: the compare-
 * and-set single winner already settled it, so `reason` is what a dispatcher
 * needs to see, never silence (that is the whole point of `X.22`'s own gap:
 * "two dispatchers working the same queue still race each other" because
 * nothing today shows either of them why the other one's move was refused).
 */
export interface DragDropAssignOutcome {
  readonly applied: boolean;
  readonly reason?: string | null;
}

/** A card moved (successfully) from one column to another. */
export interface DragDropAssignMove<TCard> {
  readonly card: TCard;
  readonly fromColumnId: string;
  readonly toColumnId: string;
}

/** A card's move was refused — surfaced, never a silent revert (see `DragDropAssignOutcome`'s own doc). */
export interface DragDropAssignRejection<TCard> {
  readonly card: TCard;
  readonly fromColumnId: string;
  readonly toColumnId: string;
  readonly reason: string | null;
}
