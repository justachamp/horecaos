import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import { TPipe } from '../../../core/i18n/t.pipe';
import { ConditionFixedValue } from '../../../shared/ui/condition-types';

/**
 * A multi-select over a list too long to scan as chips: the editor's gift picker
 * (ADR 0140's `FREE_ITEM` names variants, never free text).
 *
 * The selected values always show, so an operator can see and remove what a rule
 * carries; the rest are found by typing, and at most {@link limit} matches render
 * so a thousand-dish menu does not become a thousand buttons. A selected value the
 * list no longer holds (a dish since withdrawn) shows under its raw id rather
 * than vanishing, because a rule that silently carries an id the screen cannot
 * show is a rule nobody can fix.
 */
@Component({
  selector: 'q-promotion-chip-picker',
  imports: [TPipe],
  template: `
    <div class="picker">
      @for (value of selectedOptions(); track value.value) {
        <button
          type="button"
          class="chip chip--active"
          [attr.aria-pressed]="true"
          (click)="toggle(value.value)"
        >
          {{ value.label }}
        </button>
      }
      <input
        type="search"
        class="picker__filter"
        [attr.aria-label]="'ui.conditionBuilder.filterPlaceholder' | t"
        [placeholder]="'ui.conditionBuilder.filterPlaceholder' | t"
        [value]="filter()"
        (input)="filter.set($any($event.target).value)"
      />
      @for (option of matches(); track option.value) {
        <button
          type="button"
          class="chip"
          [attr.aria-pressed]="false"
          (click)="toggle(option.value)"
        >
          {{ option.label }}
        </button>
      }
      @if (hidden() > 0) {
        <span class="q-caption">{{
          'ui.conditionBuilder.moreMatches' | t: { count: hidden() }
        }}</span>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .picker {
      display: flex;
      flex-wrap: wrap;
      gap: 4px;
      align-items: center;
    }
    .picker__filter {
      flex: 1 1 100%;
      padding: 6px 10px;
      border: 1px solid var(--q-hairline);
      border-radius: var(--q-radius);
      font-size: var(--q-type-body-sm);
    }
    .chip {
      height: 30px;
      padding: 0 10px;
      border: 1px solid var(--q-hairline);
      border-radius: var(--q-radius);
      background: var(--q-canvas);
      color: var(--q-ink);
      cursor: pointer;
      font-size: var(--q-type-caption);
    }
    .chip--active {
      background: var(--q-primary);
      border-color: var(--q-primary);
      color: var(--q-inverse-ink);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PromotionChipPicker {
  readonly options = input.required<readonly ConditionFixedValue[]>();
  readonly selected = input.required<readonly string[]>();
  readonly limit = input(30);
  readonly selectedChange = output<readonly string[]>();

  protected readonly filter = signal('');

  protected readonly selectedOptions = computed<readonly ConditionFixedValue[]>(() => {
    const known = new Map(this.options().map((option) => [option.value, option]));
    return this.selected().map((value) => known.get(value) ?? { value, label: value });
  });

  private readonly allMatches = computed<readonly ConditionFixedValue[]>(() => {
    const needle = this.filter().trim().toLocaleLowerCase();
    if (needle === '') {
      return [];
    }
    const chosen = new Set(this.selected());
    return this.options().filter(
      (option) => !chosen.has(option.value) && option.label.toLocaleLowerCase().includes(needle),
    );
  });

  protected readonly matches = computed(() => this.allMatches().slice(0, this.limit()));
  protected readonly hidden = computed(() => Math.max(0, this.allMatches().length - this.limit()));

  protected toggle(value: string): void {
    const current = this.selected();
    this.selectedChange.emit(
      current.includes(value) ? current.filter((entry) => entry !== value) : [...current, value],
    );
  }
}
