import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { ConditionBuilder } from './condition-builder';
import {
  ConditionGroup,
  ConditionTypeDescriptor,
  emptyConditionRow,
  newConditionGroup,
} from './condition-types';

/** A synthetic catalogue exercising every value kind — no consumer needs all eight at once, but the builder must render each correctly. */
const CATALOGUE: readonly ConditionTypeDescriptor[] = [
  {
    type: 'ORDER_COUNT',
    labelKey: 'customers.segments.predicate.type.ORDER_COUNT',
    valueKind: 'NUMERIC',
  },
  {
    type: 'MIN_ORDER_MINOR',
    labelKey: 'customers.segments.predicate.type.NET_SPEND_MINOR',
    valueKind: 'MONEY_MINOR',
  },
  {
    type: 'DISCOUNT_PCT',
    labelKey: 'customers.segments.predicate.type.AVERAGE_CHECK_MINOR',
    valueKind: 'PERCENT',
  },
  {
    type: 'PLACED_ON',
    labelKey: 'customers.segments.predicate.type.BIRTHDAY_WITHIN_DAYS',
    valueKind: 'DATE',
  },
  {
    type: 'REGISTERED_BETWEEN',
    labelKey: 'customers.segments.predicate.type.REGISTERED_BETWEEN',
    valueKind: 'DATE_RANGE',
  },
  {
    type: 'CHANNEL',
    labelKey: 'customers.segments.predicate.type.ACQUISITION_CHANNEL',
    valueKind: 'TEXT_SET',
  },
  {
    type: 'LOCALE',
    labelKey: 'customers.segments.predicate.type.PREFERRED_LOCALE',
    valueKind: 'TEXT_SET',
    fixedValues: [
      { value: 'ru', label: 'Russian' },
      { value: 'en', label: 'English' },
    ],
  },
  {
    type: 'DELIVERY_DAY',
    labelKey: 'customers.segments.predicate.type.RECENCY_DAYS',
    valueKind: 'DAY_OF_WEEK_SET',
  },
];

@Component({
  selector: 'q-condition-builder-host',
  imports: [ConditionBuilder],
  template: `
    <q-condition-builder
      [catalogue]="catalogue"
      [groups]="groups()"
      [allowGroups]="allowGroups()"
      heading="Conditions"
      (groupsChange)="groups.set($event)"
    />
  `,
})
class ConditionBuilderHost {
  readonly catalogue = CATALOGUE;
  readonly allowGroups = signal(false);
  readonly groups = signal<readonly ConditionGroup[]>([newConditionGroup(CATALOGUE, 'AND')]);
}

describe('ConditionBuilder', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<ConditionBuilderHost>>;
  let host: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ConditionBuilderHost);
    fixture.detectChanges();
    host = fixture.nativeElement;
  });

  function typeSelect(): HTMLSelectElement {
    return host.querySelector('.predicate-row__type') as HTMLSelectElement;
  }

  function operatorSelect(): HTMLSelectElement {
    return host.querySelector('.predicate-row__operator') as HTMLSelectElement;
  }

  it('renders one row from a single AND group by default, with no group chrome when grouping is off', () => {
    expect(host.querySelectorAll('.predicate-row')).toHaveLength(1);
    expect(host.querySelector('.condition-group__header')).toBeNull();
    expect(host.querySelector('.add-group')).toBeNull();
    expect(host.textContent).toContain('Conditions');
  });

  it('resets the operator to the first one the new type accepts when the type changes', () => {
    // ORDER_COUNT (NUMERIC) defaults to AT_LEAST; switch to REGISTERED_BETWEEN (DATE_RANGE, BETWEEN-only).
    typeSelect().value = 'REGISTERED_BETWEEN';
    typeSelect().dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(operatorSelect().value).toBe('BETWEEN');
    expect(fixture.componentInstance.groups()[0].rows[0].operator).toBe('BETWEEN');
  });

  it('shows a second numeric input only once the operator is BETWEEN', () => {
    expect(host.querySelectorAll('.predicate-row__value[type="number"]')).toHaveLength(1);

    operatorSelect().value = 'BETWEEN';
    operatorSelect().dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(host.querySelectorAll('.predicate-row__value[type="number"]')).toHaveLength(2);
  });

  it('renders two date inputs for a DATE_RANGE type and one for a plain DATE type', () => {
    typeSelect().value = 'REGISTERED_BETWEEN';
    typeSelect().dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(host.querySelectorAll('.predicate-row__value[type="date"]')).toHaveLength(2);

    typeSelect().value = 'PLACED_ON';
    typeSelect().dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(host.querySelectorAll('.predicate-row__value[type="date"]')).toHaveLength(1);
  });

  it('shows a minor-units hint for MONEY_MINOR and a percent sign for PERCENT', () => {
    typeSelect().value = 'MIN_ORDER_MINOR';
    typeSelect().dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(host.querySelector('.value-hint')?.textContent).toContain('minor units');

    typeSelect().value = 'DISCOUNT_PCT';
    typeSelect().dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(host.querySelector('.value-hint')?.textContent).toBe('%');
  });

  it('renders a free-text input for a TEXT_SET type with no fixed values', () => {
    typeSelect().value = 'CHANNEL';
    typeSelect().dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(host.querySelector('.predicate-row__value--wide')).not.toBeNull();
    expect(host.querySelector('.fixed-chips')).toBeNull();
  });

  it('renders toggle chips for a TEXT_SET type with fixed values, and toggling edits the comma-joined textValues', () => {
    typeSelect().value = 'LOCALE';
    typeSelect().dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const chips = [...host.querySelectorAll<HTMLButtonElement>('.fixed-chips .dow-chip')];
    expect(chips.map((c) => c.textContent?.trim())).toEqual(['Russian', 'English']);

    chips[0].click();
    fixture.detectChanges();
    expect(fixture.componentInstance.groups()[0].rows[0].textValues).toBe('ru');
    expect(chips[0].getAttribute('aria-pressed')).toBe('true');

    chips[1].click();
    fixture.detectChanges();
    expect(fixture.componentInstance.groups()[0].rows[0].textValues).toBe('ru, en');

    // Un-toggling removes it again rather than leaving a stray comma.
    host.querySelectorAll<HTMLButtonElement>('.fixed-chips .dow-chip')[0].click();
    fixture.detectChanges();
    expect(fixture.componentInstance.groups()[0].rows[0].textValues).toBe('en');
  });

  it('toggles a DAY_OF_WEEK_SET value into and out of the row, keeping it sorted', () => {
    typeSelect().value = 'DELIVERY_DAY';
    typeSelect().dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const chips = [...host.querySelectorAll<HTMLButtonElement>('.dow-chips .dow-chip')];
    expect(chips).toHaveLength(7);
    expect(chips[0].textContent?.trim()).toBe('Mon');

    chips[4].click(); // Fri
    fixture.detectChanges();
    chips[1].click(); // Tue
    fixture.detectChanges();

    expect(fixture.componentInstance.groups()[0].rows[0].dayOfWeekValues).toEqual([2, 5]);

    chips[4].click(); // Fri off again
    fixture.detectChanges();
    expect(fixture.componentInstance.groups()[0].rows[0].dayOfWeekValues).toEqual([2]);
  });

  it('adds and removes rows, but refuses to remove the very last row in the whole builder', () => {
    host.querySelector<HTMLButtonElement>('.add-row')!.click();
    fixture.detectChanges();
    expect(host.querySelectorAll('.predicate-row')).toHaveLength(2);

    const removeButtons = host.querySelectorAll<HTMLButtonElement>('.predicate-row__remove');
    expect([...removeButtons].every((b) => !b.disabled)).toBe(true);

    removeButtons[1].click();
    fixture.detectChanges();
    expect(host.querySelectorAll('.predicate-row')).toHaveLength(1);
    // Only one row left across the whole builder — its remove button is now disabled.
    expect(host.querySelector<HTMLButtonElement>('.predicate-row__remove')!.disabled).toBe(true);
  });

  describe('and/or grouping (allowGroups)', () => {
    beforeEach(() => {
      fixture.componentInstance.allowGroups.set(true);
      fixture.detectChanges();
    });

    it('shows group chrome and an Add group action once grouping is allowed', () => {
      expect(host.querySelector('.condition-group__header')).not.toBeNull();
      expect(host.querySelector('.add-group')).not.toBeNull();
      // A single group has nothing to remove and no OR joiner to show yet.
      expect(host.querySelector('.predicate-row__remove[aria-label="Remove group"]')).toBeNull();
      expect(host.querySelector('.group-joiner')).toBeNull();
    });

    it('adds a second group joined by OR, each with its own AND/OR combinator', () => {
      host.querySelector<HTMLButtonElement>('.add-group')!.click();
      fixture.detectChanges();

      expect(host.querySelectorAll('.condition-group')).toHaveLength(2);
      expect(host.querySelector('.group-joiner')?.textContent).toBe('OR');

      const combinators = host.querySelectorAll<HTMLSelectElement>('.condition-group__combinator');
      combinators[1].value = 'OR';
      combinators[1].dispatchEvent(new Event('change'));
      fixture.detectChanges();

      expect(fixture.componentInstance.groups()[1].combinator).toBe('OR');
      expect(fixture.componentInstance.groups()[0].combinator).toBe('AND');
    });

    it('drops a group entirely once its last row is removed while another group remains', () => {
      host.querySelector<HTMLButtonElement>('.add-group')!.click();
      fixture.detectChanges();
      expect(fixture.componentInstance.groups()).toHaveLength(2);

      // Removing the second group's only row should remove the group itself,
      // not leave an empty AND/OR group with nothing inside it.
      const removeButtons = host.querySelectorAll<HTMLButtonElement>(
        '.condition-group .predicate-row__remove',
      );
      removeButtons[removeButtons.length - 1].click();
      fixture.detectChanges();

      expect(fixture.componentInstance.groups()).toHaveLength(1);
      expect(host.querySelectorAll('.condition-group')).toHaveLength(1);
    });

    it('cannot remove the only remaining group — the remove-group button is absent, not merely disabled', () => {
      expect(fixture.componentInstance.groups()).toHaveLength(1);
      expect(host.querySelector('.condition-group__header .predicate-row__remove')).toBeNull();

      host.querySelector<HTMLButtonElement>('.add-group')!.click();
      fixture.detectChanges();
      expect(host.querySelectorAll('.condition-group__header .predicate-row__remove')).toHaveLength(
        2,
      );

      host
        .querySelectorAll<HTMLButtonElement>('.condition-group__header .predicate-row__remove')[0]
        .click();
      fixture.detectChanges();

      expect(fixture.componentInstance.groups()).toHaveLength(1);
      expect(host.querySelector('.condition-group__header .predicate-row__remove')).toBeNull();
    });
  });

  it('emptyConditionRow defaults to the catalogue-s first type and first allowed operator', () => {
    const row = emptyConditionRow(CATALOGUE);
    expect(row.type).toBe('ORDER_COUNT');
    expect(row.operator).toBe('AT_LEAST');
  });
});
