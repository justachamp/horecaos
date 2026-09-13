import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';

/** One row `q-rule-list` renders — a promotion, an automation, a dispatch rule, a courier bonus/penalty rule, whatever the host domain is. The list itself knows nothing about what a rule *does*. */
export interface RuleListItem {
  readonly id: string;
  /** Already translated / already the rule's own name — the list renders it verbatim. */
  readonly label: string;
  readonly description?: string | null;
  readonly enabled: boolean;
}

/** One rule moved to a new position — the caller's own id order to persist. */
export type RuleReorder = readonly string[];

export interface RuleEnabledChange {
  readonly id: string;
  readonly enabled: boolean;
}

/**
 * The priority-ordered rule list every rule engine in this console needs
 * (ADR 0101, row `X.25`) — promotions and dispatch rules break ties by list
 * order (`PromotionEvaluator`'s own priority tie-break), so the order this
 * component renders *is* the semantics, not a display nicety.
 *
 * **Reorder two ways, because a mouse is not guaranteed.** `draggable` native
 * HTML5 drag-and-drop (no `@angular/cdk` — see `overlay.ts`'s own doc for why
 * this console still has none) for a pointer, and an explicit move-up/move-
 * down pair per row for a keyboard operator who cannot drag a `<li>` at all.
 * Both paths call the same {@link reorder} output with the full, new id
 * order — the host owns persisting it, this component owns nothing but the
 * gesture.
 *
 * **Presentational only.** No backend, no capability, no knowledge of what
 * "enabled" turns on or off — that stays the host screen's own concern
 * (a promotion's `/promotions/{id}` PATCH, say). This is the `q-rule-list`
 * `X.25` asks for: today it has no consumer yet (promotions and dispatch
 * rules are named as the first real ones — see the `T21` wave report) and is
 * exercised only by its own spec, exactly like `q-rule-simulator` beside it.
 */
@Component({
  selector: 'q-rule-list',
  imports: [TPipe],
  templateUrl: './rule-list.html',
  styleUrl: './rule-list.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RuleList {
  protected readonly i18n = inject(I18n);

  readonly items = input.required<readonly RuleListItem[]>();
  readonly reorder = output<RuleReorder>();
  readonly enabledChange = output<RuleEnabledChange>();

  private dragIndex: number | null = null;

  protected onDragStart(index: number, event: DragEvent): void {
    this.dragIndex = index;
    event.dataTransfer?.setData('text/plain', String(index));
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = 'move';
    }
  }

  protected onDragOver(event: DragEvent): void {
    // A drop target must cancel dragover, or the browser never fires `drop`.
    event.preventDefault();
  }

  protected onDrop(index: number, event: DragEvent): void {
    event.preventDefault();
    const from = this.dragIndex;
    this.dragIndex = null;
    if (from === null || from === index) {
      return;
    }
    this.moveTo(from, index);
  }

  protected onDragEnd(): void {
    this.dragIndex = null;
  }

  protected moveUp(index: number): void {
    if (index > 0) {
      this.moveTo(index, index - 1);
    }
  }

  protected moveDown(index: number): void {
    if (index < this.items().length - 1) {
      this.moveTo(index, index + 1);
    }
  }

  protected onToggle(item: RuleListItem, enabled: boolean): void {
    this.enabledChange.emit({ id: item.id, enabled });
  }

  private moveTo(from: number, to: number): void {
    const ids = this.items().map((item) => item.id);
    const [moved] = ids.splice(from, 1);
    ids.splice(to, 0, moved);
    this.reorder.emit(ids);
  }
}
