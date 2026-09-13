import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  Directive,
  TemplateRef,
  computed,
  contentChild,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { I18n } from '../../../core/i18n/i18n';
import { Board } from '../board/board';
import { BoardCard } from '../board-card/board-card';
import { BoardColumn, BoardColumnDropEvent } from '../board-column/board-column';
import { InlineAlert } from '../inline-alert';
import {
  DragDropAssignColumn,
  DragDropAssignMove,
  DragDropAssignOutcome,
  DragDropAssignRejection,
} from './drag-drop-assign-types';

export interface QBoardCardContext<T> {
  readonly $implicit: T;
}

/**
 * A card's own content, projected by the caller:
 * `<ng-template qBoardCard let-plan>...</ng-template>`. Same shape as
 * `data-table`'s own `qCell` (`QCellDef`) for the same reason: this
 * component knows nothing about what a dispatch plan, or any other card, is.
 */
@Directive({ selector: 'ng-template[qBoardCard]' })
export class QBoardCardDef<T> {
  constructor(readonly templateRef: TemplateRef<QBoardCardContext<T>>) {}
}

/**
 * `X.22` — drag a card onto a column to assign it, drag it back to the pool
 * to unassign, built on `q-board`/`q-board-column`/`q-board-card` (`X.21`).
 *
 * **Optimistic, and the optimism is visible, never silent.** A drop moves the
 * card immediately — a dispatcher should not wait on a round trip to see
 * their own drag settle — but `assignFn`/`unassignFn` are the only source of
 * truth for whether it actually happened. `DispatchController`'s assign and
 * unassign are a compare-and-set single winner (`3.1`): a second dispatcher's
 * drop can lose to one that already changed the plan's version. When that
 * happens the card goes back to its previous column *and* {@link rejected}
 * fires with the server's own reason (`STALE_VERSION`, `ALREADY_ASSIGNED`,
 * …) — the gap this closes is literal: "two dispatchers working the same
 * queue still race each other" because nothing today shows either of them
 * why the other one's move won. A silent revert would look identical to a
 * successful drop that simply bounced back on its own, which is exactly the
 * failure mode this exists to rule out.
 *
 * **Column membership is computed, not stored.** `columnIdOf` reads it off
 * the card the host already owns (a plan's `shipment?.courierId`); this
 * component only overlays a card's column while its own drop is in flight or
 * just settled, and drops the overlay the moment it is no longer needed —
 * once the host's `cards()` input reflects the outcome (a refresh the host
 * itself runs), the overlay and the real data agree and neither is
 * special-cased.
 *
 * **No direct column-to-column reassignment exists on the wire.** Dropping a
 * carried card onto a *different* courier's column calls `assignFn` exactly
 * as an unassigned card would; `DispatchController.assign` refuses any plan
 * that already has a shipment (`ALREADY_ASSIGNED`), so that drop settles as
 * a rejection — surfaced the same way a stale version is, not a silent
 * no-op. A dispatcher who wants to reassign a carried plan drags it to the
 * pool, then onto the new courier, in two moves.
 */
@Component({
  selector: 'q-drag-drop-assign',
  imports: [Board, BoardColumn, BoardCard, NgTemplateOutlet, InlineAlert],
  templateUrl: './drag-drop-assign.html',
  styleUrl: './drag-drop-assign.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DragDropAssign<TCard, TColumn = unknown> {
  private readonly i18n = inject(I18n);

  readonly columns = input.required<readonly DragDropAssignColumn<TColumn>[]>();
  readonly cards = input.required<readonly TCard[]>();
  readonly cardId = input.required<(card: TCard) => string>();
  /** Which column a card sits in *right now*, per the host's own domain state. */
  readonly columnIdOf = input.required<(card: TCard) => string>();
  /** Dropping onto this column id calls `unassignFn` instead of `assignFn`. */
  readonly unassignedColumnId = input.required<string>();
  readonly draggable = input<(card: TCard) => boolean>(() => true);
  readonly assignFn =
    input.required<(card: TCard, targetColumnId: string) => Promise<DragDropAssignOutcome>>();
  readonly unassignFn = input.required<(card: TCard) => Promise<DragDropAssignOutcome>>();

  /** Fires once the drop is confirmed applied — the host's own cue to refresh, if it has not already. */
  readonly moved = output<DragDropAssignMove<TCard>>();
  /** Fires on every refusal, with the server's own reason — never swallowed. */
  readonly rejected = output<DragDropAssignRejection<TCard>>();

  protected readonly cardTemplate = contentChild(QBoardCardDef);

  private readonly pendingCardIds = signal<ReadonlySet<string>>(new Set());
  private readonly columnOverride = signal<ReadonlyMap<string, string>>(new Map());

  /** The most recent refusal, shown beside the board until dismissed or superseded. */
  protected readonly lastRejection = signal<DragDropAssignRejection<TCard> | null>(null);

  /**
   * `lastRejection`, translated — the single-winner refusal surfaced in
   * words, never a silent revert (see this class's own doc). `reason` is
   * the server's own machine code (`STALE_VERSION`, `ALREADY_ASSIGNED`);
   * a caller that wants a friendlier label maps it before this component
   * ever sees it — it is not this generic component's job to know a
   * dispatch-specific vocabulary.
   */
  protected readonly rejectionText = computed<string | null>(() => {
    const rejection = this.lastRejection();
    if (!rejection) {
      return null;
    }
    return rejection.reason
      ? this.i18n.t('shared.dragDropAssign.rejected', { reason: rejection.reason })
      : this.i18n.t('shared.dragDropAssign.rejectedUnknown');
  });

  protected readonly cardsByColumn = computed<ReadonlyMap<string, readonly TCard[]>>(() => {
    const idOf = this.cardId();
    const columnOf = this.columnIdOf();
    const overrides = this.columnOverride();
    const byColumn = new Map<string, TCard[]>();
    for (const card of this.cards()) {
      const columnId = overrides.get(idOf(card)) ?? columnOf(card);
      const bucket = byColumn.get(columnId);
      if (bucket) {
        bucket.push(card);
      } else {
        byColumn.set(columnId, [card]);
      }
    }
    return byColumn;
  });

  protected cardsIn(columnId: string): readonly TCard[] {
    return this.cardsByColumn().get(columnId) ?? [];
  }

  protected isPending(card: TCard): boolean {
    return this.pendingCardIds().has(this.cardId()(card));
  }

  protected isDraggable(card: TCard): boolean {
    return this.draggable()(card) && !this.isPending(card);
  }

  protected dismissRejection(): void {
    this.lastRejection.set(null);
  }

  protected onDropped(event: BoardColumnDropEvent<TCard>): void {
    if (event.fromColumnId === event.toColumnId) {
      return;
    }
    const id = this.cardId()(event.card);
    this.lastRejection.set(null);
    this.pendingCardIds.update((current) => new Set(current).add(id));
    this.columnOverride.update((current) => new Map(current).set(id, event.toColumnId));

    const settle =
      event.toColumnId === this.unassignedColumnId()
        ? this.unassignFn()(event.card)
        : this.assignFn()(event.card, event.toColumnId);

    settle
      .then((outcome) => this.settle(event, outcome))
      // The request itself failed (network, 5xx) — no server-decided outcome
      // exists to trust, so this is treated exactly like a refusal: reverted
      // and surfaced, never silently kept or silently dropped.
      .catch(() => this.settle(event, { applied: false, reason: null }))
      .finally(() => {
        this.pendingCardIds.update((current) => {
          const next = new Set(current);
          next.delete(id);
          return next;
        });
      });
  }

  private settle(event: BoardColumnDropEvent<TCard>, outcome: DragDropAssignOutcome): void {
    const id = this.cardId()(event.card);
    // Either way the overlay is no longer needed: applied, the host's own
    // refresh will bring `columnIdOf` in line with where the card now sits;
    // refused, the card belongs back where `columnIdOf` already says it is.
    this.columnOverride.update((current) => {
      const next = new Map(current);
      next.delete(id);
      return next;
    });
    if (outcome.applied) {
      this.moved.emit({
        card: event.card,
        fromColumnId: event.fromColumnId,
        toColumnId: event.toColumnId,
      });
      return;
    }
    const rejection: DragDropAssignRejection<TCard> = {
      card: event.card,
      fromColumnId: event.fromColumnId,
      toColumnId: event.toColumnId,
      reason: outcome.reason ?? null,
    };
    this.lastRejection.set(rejection);
    this.rejected.emit(rejection);
  }
}
