import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  model,
  output,
} from '@angular/core';

import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ConditionBuilder } from '../../../shared/ui/condition-builder';
import {
  ConditionFixedValue,
  ConditionGroup,
  ConditionTypeDescriptor,
} from '../../../shared/ui/condition-types';
import { MoneyInput } from '../../../shared/ui/money-input';
import { PercentInput } from '../../../shared/ui/percent-input';
import { PromotionChipPicker } from './chip-picker';
import {
  ActionDraft,
  PromotionActionType,
  PromotionDraft,
  PromotionLookups,
  actionTypesFor,
  defaultStackingGroup,
  draftProblems,
  emptyAction,
  scopesFor,
} from './promotion-draft';
import { LoyaltyAccrual, LoyaltyRedemption, PromotionKind, PromotionScope } from './promotions-api';

/** How an action's operands are edited: a percentage, an amount, nothing, a percent-or-amount, or a gift. */
type ActionShape = 'PERCENT' | 'AMOUNT' | 'NONE' | 'REDUCED' | 'GIFT';

function shapeOf(type: PromotionActionType): ActionShape {
  switch (type) {
    case 'ITEM_PERCENTAGE_DISCOUNT':
    case 'ORDER_PERCENTAGE_DISCOUNT':
    case 'ITEM_PERCENTAGE_MARKUP':
      return 'PERCENT';
    case 'ITEM_FIXED_DISCOUNT':
    case 'ORDER_FIXED_DISCOUNT':
    case 'ITEM_FIXED_PRICE':
    case 'ITEM_FIXED_MARKUP':
      return 'AMOUNT';
    case 'FREE_DELIVERY':
      return 'NONE';
    case 'REDUCED_DELIVERY':
      return 'REDUCED';
    case 'FREE_ITEM':
      return 'GIFT';
  }
}

/**
 * The promotion rule editor (ADR 0140, row `6.1`): the basics, the window, the
 * conditions (`q-condition-builder` over the closed vocabulary), the actions, the
 * caps and limits, and the two loyalty flags.
 *
 * It owns no request. The page holds the {@link draft}, this edits it through a
 * two-way `model`, and the page decides what saving means (create, or replace and
 * go back to `DRAFT`); that keeps the unsaved-candidate simulator and the editor
 * reading one value. What the form can already tell is wrong is shown as it
 * stands ({@link draftProblems}); the server's rule checker owns the rest and is
 * run from the page, not here, because validating is a lifecycle step.
 *
 * **Nothing it cannot show is saved over.** A stored promotion with a rule this
 * console cannot represent ({@link unsupported}) opens read-only with that said
 * out loud, because saving the rest would delete the part the editor could not
 * draw.
 */
@Component({
  selector: 'q-promotion-editor',
  imports: [TPipe, ConditionBuilder, MoneyInput, PercentInput, PromotionChipPicker],
  templateUrl: './promotion-editor.html',
  styleUrl: './promotion-editor.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PromotionEditor {
  protected readonly i18n = inject(I18n);

  readonly draft = model.required<PromotionDraft>();
  readonly catalogue = input.required<readonly ConditionTypeDescriptor[]>();
  readonly lookups = input.required<PromotionLookups>();
  /** Stacking groups the brand already uses, offered as suggestions. */
  readonly stackingGroups = input<readonly string[]>([]);
  /** Branches, for choosing whose menu the dish lists come from. */
  readonly locations = input<readonly ConditionFixedValue[]>([]);
  readonly menuLocationId = input<string | null>(null);
  readonly menuLocationChange = output<string>();
  readonly menuLoading = input(false);
  /** Rule types the console cannot draw; non-empty makes the editor read-only. */
  readonly unsupported = input<readonly string[]>([]);
  readonly saving = input(false);
  readonly isNew = input(false);
  /** The promotion is live or retired: shown, never edited in place (suspend it first). */
  readonly locked = input(false);
  /** Why it is locked, already translated; shown above the form. */
  readonly lockedHint = input<string | null>(null);

  readonly save = output<void>();
  readonly cancel = output<void>();

  protected readonly kinds: readonly PromotionKind[] = ['DISCOUNT', 'MARKUP'];
  protected readonly accrualChoices: readonly LoyaltyAccrual[] = ['ACCRUE', 'SUPPRESS'];
  protected readonly redemptionChoices: readonly LoyaltyRedemption[] = ['ALLOW', 'BLOCK'];

  protected readonly readOnly = computed(() => this.unsupported().length > 0 || this.locked());
  protected readonly scopes = computed(() => scopesFor(this.draft().kind));
  protected readonly actionTypes = computed(() =>
    actionTypesFor(this.draft().kind, this.draft().scope),
  );
  protected readonly problems = computed(() => draftProblems(this.draft()));
  protected readonly canSave = computed(
    () => !this.readOnly() && !this.saving() && this.problems().length === 0,
  );

  protected shape(type: PromotionActionType): ActionShape {
    return shapeOf(type);
  }

  protected kindLabelKey(kind: PromotionKind): MessageKey {
    return `marketing.promotions.kind.${kind}` as MessageKey;
  }

  protected scopeLabelKey(scope: PromotionScope): MessageKey {
    return `marketing.promotions.scope.${scope}` as MessageKey;
  }

  protected actionLabelKey(type: PromotionActionType): MessageKey {
    return `marketing.promotions.action.${type}` as MessageKey;
  }

  protected accrualLabelKey(value: LoyaltyAccrual): MessageKey {
    return `marketing.promotions.loyalty.accrual.${value}` as MessageKey;
  }

  protected redemptionLabelKey(value: LoyaltyRedemption): MessageKey {
    return `marketing.promotions.loyalty.redemption.${value}` as MessageKey;
  }

  protected patch(change: Partial<PromotionDraft>): void {
    this.draft.update((current) => ({ ...current, ...change }));
  }

  /** A markup is item-scope and never exclusive; its actions follow. The stacking group follows only while it is still the default of the old choice. */
  protected setKind(kind: PromotionKind): void {
    const current = this.draft();
    if (kind === current.kind) {
      return;
    }
    const scope = scopesFor(kind).includes(current.scope) ? current.scope : 'ITEM';
    this.apply(kind, scope);
  }

  protected setScope(scope: PromotionScope): void {
    const current = this.draft();
    if (scope === current.scope) {
      return;
    }
    this.apply(current.kind, scope);
  }

  private apply(kind: PromotionKind, scope: PromotionScope): void {
    const current = this.draft();
    const followsDefault =
      current.stackingGroup === defaultStackingGroup(current.kind, current.scope) ||
      current.stackingGroup.trim() === '';
    const allowed = actionTypesFor(kind, scope);
    const kept = current.actions.filter((action) => allowed.includes(action.type));
    this.patch({
      kind,
      scope,
      exclusive: kind === 'MARKUP' ? false : current.exclusive,
      stackingGroup: followsDefault ? defaultStackingGroup(kind, scope) : current.stackingGroup,
      actions: kept.length > 0 ? kept : [emptyAction(allowed[0])],
    });
  }

  protected setConditions(groups: readonly ConditionGroup[]): void {
    this.patch({ conditions: groups });
  }

  protected updateAction(index: number, change: Partial<ActionDraft>): void {
    this.patch({
      actions: this.draft().actions.map((action, i) =>
        i === index ? { ...action, ...change } : action,
      ),
    });
  }

  protected addAction(): void {
    const allowed = this.actionTypes();
    this.patch({ actions: [...this.draft().actions, emptyAction(allowed[0])] });
  }

  protected removeAction(index: number): void {
    this.patch({ actions: this.draft().actions.filter((_, i) => i !== index) });
  }

  /** A whole number from a number input; an empty or unreadable field is 0, which the server's checks then refuse where zero is not allowed. */
  protected numberFrom(event: Event): number {
    const value = Number((event.target as HTMLInputElement).value);
    return Number.isFinite(value) ? Math.trunc(value) : 0;
  }
}
