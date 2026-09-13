import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { ItemModifierDialog, ModifierDialogConfirmation } from './item-modifier-dialog';
import { MenuModifierGroup } from './new-order-api';

const SIZE_GROUP: MenuModifierGroup = {
  modifierGroupId: 'g-size',
  code: 'SIZE',
  name: 'Size',
  required: true,
  minimumSelections: 1,
  maximumSelections: 1,
  allowSameOptionMultipleTimes: false,
  options: [
    { optionId: 'o-small', code: 'SMALL', maximumQuantity: 1, amountMinor: 0 },
    { optionId: 'o-large', code: 'LARGE', maximumQuantity: 1, amountMinor: 5_000 },
  ],
};

const EXTRAS_GROUP: MenuModifierGroup = {
  modifierGroupId: 'g-extras',
  code: 'EXTRAS',
  name: 'Extras',
  required: false,
  minimumSelections: 0,
  maximumSelections: 2,
  allowSameOptionMultipleTimes: false,
  options: [
    { optionId: 'o-cheese', code: 'CHEESE', maximumQuantity: 1, amountMinor: 3_000 },
    { optionId: 'o-bacon', code: 'BACON', maximumQuantity: 1, amountMinor: 4_000 },
    { optionId: 'o-egg', code: 'EGG', maximumQuantity: 1, amountMinor: 2_000 },
  ],
};

const SHOTS_GROUP: MenuModifierGroup = {
  modifierGroupId: 'g-shots',
  code: 'SHOTS',
  name: 'Espresso shots',
  required: false,
  minimumSelections: 0,
  maximumSelections: 5,
  allowSameOptionMultipleTimes: true,
  options: [{ optionId: 'o-shot', code: 'EXTRA_SHOT', maximumQuantity: 3, amountMinor: 2_000 }],
};

function render(
  groups: readonly MenuModifierGroup[],
): ReturnType<typeof TestBed.createComponent<ItemModifierDialog>> {
  const fixture = TestBed.createComponent(ItemModifierDialog);
  fixture.componentRef.setInput('productName', 'Cheeseburger');
  fixture.componentRef.setInput('groups', groups);
  fixture.componentRef.setInput('currency', 'UZS');
  fixture.detectChanges();
  return fixture;
}

describe('ItemModifierDialog', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('blocks confirm when a required single-choice group has no selection, and shows the error', () => {
    const fixture = render([SIZE_GROUP]);
    const host: HTMLElement = fixture.nativeElement;
    const confirmations: ModifierDialogConfirmation[] = [];
    fixture.componentInstance.confirm.subscribe((c) => confirmations.push(c));

    (host.querySelector('[data-testid="item-modifier-confirm"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="item-modifier-group-error"]')).not.toBeNull();
    expect(confirmations).toEqual([]);
  });

  it('a radio pick replaces any earlier pick in the same single-choice group', () => {
    const fixture = render([SIZE_GROUP]);
    const host: HTMLElement = fixture.nativeElement;
    const radios = host.querySelectorAll<HTMLInputElement>('[data-testid="item-modifier-radio"]');
    expect(radios).toHaveLength(2);

    radios[0].dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(fixture.componentInstance['quantityOf']('o-small')).toBe(1);

    radios[1].dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(fixture.componentInstance['quantityOf']('o-small')).toBe(0);
    expect(fixture.componentInstance['quantityOf']('o-large')).toBe(1);
  });

  it('caps a checkbox group at its maximumSelections, refusing a third pick', () => {
    const fixture = render([EXTRAS_GROUP]);
    const host: HTMLElement = fixture.nativeElement;
    const boxes = host.querySelectorAll<HTMLInputElement>('[data-testid="item-modifier-checkbox"]');
    expect(boxes).toHaveLength(3);

    boxes[0].dispatchEvent(new Event('change')); // cheese
    boxes[1].dispatchEvent(new Event('change')); // bacon
    fixture.detectChanges();
    expect(fixture.componentInstance['groupSelectedCount'](EXTRAS_GROUP)).toBe(2);

    boxes[2].dispatchEvent(new Event('change')); // egg — group is already full
    fixture.detectChanges();
    // The group is already at its cap of 2; a third pick must not exceed it.
    expect(fixture.componentInstance['groupSelectedCount'](EXTRAS_GROUP)).toBe(2);
    expect(fixture.componentInstance['quantityOf']('o-egg')).toBe(0);
  });

  it('unchecking a checkbox frees a slot for another option', () => {
    const fixture = render([EXTRAS_GROUP]);
    const host: HTMLElement = fixture.nativeElement;
    const boxes = host.querySelectorAll<HTMLInputElement>('[data-testid="item-modifier-checkbox"]');

    boxes[0].dispatchEvent(new Event('change')); // cheese on
    boxes[1].dispatchEvent(new Event('change')); // bacon on
    boxes[0].dispatchEvent(new Event('change')); // cheese off
    fixture.detectChanges();
    boxes[2].dispatchEvent(new Event('change')); // egg on — a slot is free again
    fixture.detectChanges();

    expect(fixture.componentInstance['quantityOf']('o-cheese')).toBe(0);
    expect(fixture.componentInstance['quantityOf']('o-bacon')).toBe(1);
    expect(fixture.componentInstance['quantityOf']('o-egg')).toBe(1);
  });

  it('a stepper option can be repeated up to its own maximumQuantity, capped by the group ceiling', () => {
    const fixture = render([SHOTS_GROUP]);
    expect(fixture.componentInstance['optionCeiling'](SHOTS_GROUP, SHOTS_GROUP.options[0])).toBe(3);

    fixture.componentInstance['setStepperQuantity'](SHOTS_GROUP.options[0], 3);
    fixture.detectChanges();
    expect(fixture.componentInstance['quantityOf']('o-shot')).toBe(3);
  });

  it('emits every selected option with its quantity once every required group is met', () => {
    const fixture = render([SIZE_GROUP, EXTRAS_GROUP]);
    const host: HTMLElement = fixture.nativeElement;
    const confirmations: ModifierDialogConfirmation[] = [];
    fixture.componentInstance.confirm.subscribe((c) => confirmations.push(c));

    host
      .querySelectorAll<HTMLInputElement>('[data-testid="item-modifier-radio"]')[1]
      .dispatchEvent(new Event('change')); // LARGE
    host
      .querySelectorAll<HTMLInputElement>('[data-testid="item-modifier-checkbox"]')[1]
      .dispatchEvent(new Event('change')); // BACON
    fixture.detectChanges();

    (host.querySelector('[data-testid="item-modifier-confirm"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(confirmations).toHaveLength(1);
    expect(confirmations[0].selections).toEqual(
      expect.arrayContaining([
        { optionId: 'o-large', code: 'LARGE', quantity: 1, amountMinor: 5_000 },
        { optionId: 'o-bacon', code: 'BACON', quantity: 1, amountMinor: 4_000 },
      ]),
    );
    expect(confirmations[0].selections).toHaveLength(2);
  });

  it('dismiss clears every selection, so reopening the dialog for a different item starts clean', () => {
    const fixture = render([SIZE_GROUP]);
    const host: HTMLElement = fixture.nativeElement;
    host
      .querySelectorAll<HTMLInputElement>('[data-testid="item-modifier-radio"]')[0]
      .dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(fixture.componentInstance['quantityOf']('o-small')).toBe(1);

    fixture.componentInstance['close']();
    expect(fixture.componentInstance['quantityOf']('o-small')).toBe(0);
  });
});
