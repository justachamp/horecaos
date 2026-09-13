import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  computed,
  inject,
  input,
  output,
} from '@angular/core';

import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import {
  CONDITION_OPERATOR_KEYS,
  ConditionCombinator,
  ConditionFixedValue,
  ConditionGroup,
  ConditionOperator,
  ConditionRow,
  ConditionTypeDescriptor,
  ConditionValueKind,
  DAY_OF_WEEK_KEYS,
  conditionRowIsWorkable,
  descriptorFor,
  emptyConditionRow,
  newConditionGroup,
  operatorsForValueKind,
} from './condition-types';

/**
 * The typed condition/and-or-grouping builder every rule engine in this
 * console needs (ADR 0101, row `X.25`) — extracted from the predicate editor
 * that used to live entirely inside `segments-page.ts`, the only rule anybody
 * could author anywhere in the console.
 *
 * **Closed, caller-supplied catalogue.** This component knows nothing about
 * audiences, promotions or dispatch — it renders and edits whatever
 * {@link ConditionTypeDescriptor} list `catalogue` hands it, the same
 * "mirrored, not fetched" convention `audience-predicates.ts` already
 * documents for the reason it is closed on both sides at once.
 *
 * **Grouping is opt-in.** Segments' predicates are implicitly ANDed only —
 * `AudiencePredicate` has no combinator at all — so `allowGroups` stays
 * `false` there and the component renders exactly one flat, unlabelled list of
 * rows, byte-for-byte the DOM segments already had. A future consumer that
 * wants "match ALL of this OR ANY of that" passes `true` and gets a second
 * group joined by OR, each with its own AND/OR toggle over its own rows.
 *
 * **Value editors are minimal stand-ins pending `P02`.** `X.10`/`X.11` (money,
 * percent, date and day-of-week inputs) are not built anywhere in this
 * console yet, so `MONEY_MINOR`/`PERCENT`/`DAY_OF_WEEK_SET` render a plain
 * number input with a hint suffix and a set of day toggle chips here, under
 * this component's own name rather than `q-money-input` and friends — so that
 * when `P02` lands, swapping these editors out is a template change inside
 * this one file, not a rename fight over a selector two waves already used.
 */
@Component({
  selector: 'q-condition-builder',
  imports: [TPipe],
  templateUrl: './condition-builder.html',
  styleUrl: './condition-builder.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConditionBuilder {
  protected readonly i18n = inject(I18n);

  readonly catalogue = input.required<readonly ConditionTypeDescriptor[]>();
  readonly groups = input.required<readonly ConditionGroup[]>();
  readonly groupsChange = output<readonly ConditionGroup[]>();

  /** `false` (the default): exactly one flat AND group, no group chrome — segments' own shape. `true` offers "Add group", each joined by OR. */
  readonly allowGroups = input(false, { transform: booleanAttribute });

  /** The section caption, already translated by the host page — "Conditions" for segments, a different noun elsewhere. `null` renders none. */
  readonly heading = input<string | null>(null);

  protected readonly days = [1, 2, 3, 4, 5, 6, 7] as const;

  /** The last row across every group may not be removed — nothing left to save otherwise. */
  protected readonly canRemoveAnyRow = computed(
    () => this.groups().reduce((count, group) => count + group.rows.length, 0) > 1,
  );

  protected typeLabel(type: string): string {
    return this.i18n.t(this.descriptor(type).labelKey);
  }

  protected operatorLabel(operator: ConditionOperator): string {
    return this.i18n.t(CONDITION_OPERATOR_KEYS[operator]);
  }

  protected dayLabel(day: number): string {
    return this.i18n.t(DAY_OF_WEEK_KEYS[day - 1]);
  }

  protected valueKindOf(type: string): ConditionValueKind {
    return this.descriptor(type).valueKind;
  }

  protected fixedValuesOf(type: string): readonly ConditionFixedValue[] | null {
    return this.descriptor(type).fixedValues ?? null;
  }

  protected operatorsFor(type: string): readonly ConditionOperator[] {
    return operatorsForValueKind(this.valueKindOf(type));
  }

  protected rowIsWorkable(row: ConditionRow): boolean {
    return conditionRowIsWorkable(row, this.valueKindOf(row.type));
  }

  protected canRemoveGroup(): boolean {
    return this.groups().length > 1;
  }

  protected selectedFixedValues(row: ConditionRow): readonly string[] {
    return row.textValues
      .split(',')
      .map((value) => value.trim())
      .filter((value) => value.length > 0);
  }

  private descriptor(type: string): ConditionTypeDescriptor {
    return descriptorFor(this.catalogue(), type);
  }

  protected addRow(groupIndex: number): void {
    this.emit(
      this.groups().map((group, i) =>
        i === groupIndex
          ? { ...group, rows: [...group.rows, emptyConditionRow(this.catalogue())] }
          : group,
      ),
    );
  }

  protected removeRow(groupIndex: number, rowIndex: number): void {
    if (!this.canRemoveAnyRow()) {
      return;
    }
    const updated = this.groups().map((group, i) =>
      i === groupIndex ? { ...group, rows: group.rows.filter((_, r) => r !== rowIndex) } : group,
    );
    // A group's row list may never sit empty while another group remains —
    // an empty AND/OR group is a hole, not a rule. Drop it rather than render
    // a group with nothing inside. The single-group (allowGroups=false) case
    // never reaches this: canRemoveAnyRow already refuses the last row.
    this.emit(updated.length > 1 ? updated.filter((group) => group.rows.length > 0) : updated);
  }

  protected updateRow(groupIndex: number, rowIndex: number, patch: Partial<ConditionRow>): void {
    this.emit(
      this.groups().map((group, i) => {
        if (i !== groupIndex) {
          return group;
        }
        return {
          ...group,
          rows: group.rows.map((row, r) => {
            if (r !== rowIndex) {
              return row;
            }
            const next = { ...row, ...patch };
            // Changing the type resets the operator to the first one the new
            // value kind accepts — a stale BETWEEN left over from a numeric
            // field makes no sense once the type switches to a text-set one.
            if (patch.type && patch.type !== row.type) {
              next.operator = this.operatorsFor(patch.type)[0];
            }
            return next;
          }),
        };
      }),
    );
  }

  protected toggleDay(groupIndex: number, rowIndex: number, day: number): void {
    const row = this.groups()[groupIndex].rows[rowIndex];
    const has = row.dayOfWeekValues.includes(day);
    const next = has
      ? row.dayOfWeekValues.filter((d) => d !== day)
      : [...row.dayOfWeekValues, day].sort((a, b) => a - b);
    this.updateRow(groupIndex, rowIndex, { dayOfWeekValues: next });
  }

  protected toggleFixedValue(groupIndex: number, rowIndex: number, value: string): void {
    const row = this.groups()[groupIndex].rows[rowIndex];
    const current = this.selectedFixedValues(row);
    const next = current.includes(value) ? current.filter((v) => v !== value) : [...current, value];
    this.updateRow(groupIndex, rowIndex, { textValues: next.join(', ') });
  }

  protected addGroup(): void {
    this.emit([...this.groups(), newConditionGroup(this.catalogue())]);
  }

  protected removeGroup(groupIndex: number): void {
    if (!this.canRemoveGroup()) {
      return;
    }
    this.emit(this.groups().filter((_, i) => i !== groupIndex));
  }

  protected setGroupCombinator(groupIndex: number, combinator: ConditionCombinator): void {
    this.emit(
      this.groups().map((group, i) => (i === groupIndex ? { ...group, combinator } : group)),
    );
  }

  private emit(next: readonly ConditionGroup[]): void {
    this.groupsChange.emit(next);
  }
}
