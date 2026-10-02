import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { ComboDialogConfirmation, ComboPickerDialog } from './combo-picker-dialog';
import { MenuComboComponent, MenuComboGroup } from './new-order-api';

const component = (
  id: string,
  name: string,
  overrides: Partial<MenuComboComponent> = {},
): MenuComboComponent => ({
  componentId: id,
  variantId: `${id}-v`,
  productId: null,
  name,
  variantName: null,
  defaultQuantity: 1,
  sortOrder: 0,
  orderable: true,
  amountMinor: 0,
  ...overrides,
});

const MAIN: MenuComboGroup = {
  comboGroupId: 'g-main',
  containerVariantId: 'lunch-v',
  code: 'MAIN',
  name: 'Main',
  minimumSelections: 1,
  maximumSelections: 1,
  allowSameComponentMultipleTimes: false,
  sortOrder: 0,
  components: [
    component('burger', 'Burger', { amountMinor: 25_000 }),
    component('wrap', 'Wrap', { amountMinor: 22_000 }),
  ],
};

const DRINK: MenuComboGroup = {
  comboGroupId: 'g-drink',
  containerVariantId: 'lunch-v',
  code: 'DRINK',
  name: 'Drink',
  minimumSelections: 0,
  maximumSelections: 2,
  allowSameComponentMultipleTimes: true,
  sortOrder: 1,
  components: [
    component('cola', 'Cola', { amountMinor: 3_000 }),
    component('water', 'Water', { amountMinor: 0 }),
  ],
};

function render(
  groups: readonly MenuComboGroup[] = [MAIN, DRINK],
  currency: string | null = 'UZS',
) {
  TestBed.resetTestingModule();
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(ComboPickerDialog);
  fixture.componentRef.setInput('productName', 'Lunch box');
  fixture.componentRef.setInput('groups', groups);
  fixture.componentRef.setInput('currency', currency);
  const confirmed: ComboDialogConfirmation[] = [];
  let dismissed = 0;
  fixture.componentInstance.confirm.subscribe((value) => confirmed.push(value));
  fixture.componentInstance.dismiss.subscribe(() => dismissed++);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const q = (testId: string) => host.querySelector<HTMLElement>(`[data-testid="${testId}"]`);
  const all = (testId: string) => [
    ...host.querySelectorAll<HTMLElement>(`[data-testid="${testId}"]`),
  ];
  const tick = () => fixture.detectChanges();
  return { fixture, host, q, all, tick, confirmed, dismissed: () => dismissed };
}

describe('ComboPickerDialog', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('shows each group with its range and every component with what it costs in this combo', () => {
    const { all, host } = render();

    expect(all('combo-dialog-group')).toHaveLength(2);
    const text = host.textContent ?? '';
    expect(text).toContain('Pick 1');
    expect(text).toContain('Pick 0 to 2');
    expect(text).toContain('Burger');
    expect(text).toMatch(/25\s?000/);
    expect(text).toContain('included');
  });

  it('draws a one-pick group as radios and a repeating group as steppers', () => {
    const { all, host } = render();

    expect(all('combo-dialog-radio')).toHaveLength(2);
    expect(host.querySelectorAll('q-number-stepper')).toHaveLength(2);
  });

  it('refuses to confirm while a group is short of its minimum, and names it', () => {
    const { q, tick, confirmed, all } = render();

    (q('combo-dialog-confirm') as HTMLButtonElement).click();
    tick();

    expect(confirmed).toEqual([]);
    expect(all('combo-dialog-group-error')).toHaveLength(1);
    expect(q('combo-dialog-group-error')?.textContent).toContain('at least 1');
  });

  it('confirms the picks in the groups’ order with each component’s own price', () => {
    const { q, all, tick, confirmed } = render();

    (all('combo-dialog-radio')[1] as HTMLInputElement).click();
    tick();
    (q('combo-dialog-confirm') as HTMLButtonElement).click();

    expect(confirmed).toEqual([
      {
        picks: [
          {
            componentId: 'wrap',
            name: 'Wrap',
            pickQuantity: 1,
            unitQuantity: 1,
            amountMinor: 22_000,
          },
        ],
      },
    ]);
  });

  it('adds the picked components up at the foot, and holds the total back for an unpriced one', () => {
    const unpriced: MenuComboGroup = {
      ...MAIN,
      components: [component('burger', 'Burger', { amountMinor: null })],
    };
    const { q, all, tick } = render([unpriced]);

    expect(q('combo-dialog-total')?.textContent).toMatch(/0/);

    (all('combo-dialog-radio')[0] as HTMLInputElement).click();
    tick();

    expect(q('combo-dialog-total')?.textContent).toContain('Total pending');
  });

  it('totals the combo as the sum of its components', () => {
    const { q, all, tick } = render([MAIN]);

    (all('combo-dialog-radio')[0] as HTMLInputElement).click();
    tick();

    expect(q('combo-dialog-total')?.textContent).toMatch(/25\s?000/);
  });

  it('shows a stopped component as stopped and does not let it be picked', () => {
    const stopped: MenuComboGroup = {
      ...MAIN,
      components: [
        component('burger', 'Burger', { orderable: false }),
        component('wrap', 'Wrap', { amountMinor: 22_000 }),
      ],
    };
    const { q, all, tick, confirmed } = render([stopped]);

    expect(q('combo-dialog-stopped')).not.toBeNull();
    const [burgerRadio] = all('combo-dialog-radio') as HTMLInputElement[];
    expect(burgerRadio.disabled).toBe(true);

    (q('combo-dialog-confirm') as HTMLButtonElement).click();
    tick();
    expect(confirmed).toEqual([]);
  });

  it('says so, and cannot confirm, when a group has nothing orderable to fill its minimum', () => {
    const empty: MenuComboGroup = {
      ...MAIN,
      components: [component('burger', 'Burger', { orderable: false })],
    };
    const { q, tick, confirmed } = render([empty]);

    expect(q('combo-dialog-impossible')).not.toBeNull();
    (q('combo-dialog-confirm') as HTMLButtonElement).click();
    tick();
    expect(confirmed).toEqual([]);
  });

  it('dismisses without confirming', () => {
    const { host, dismissed } = render();

    (host.querySelector('.combo-dialog__dismiss') as HTMLButtonElement).click();

    expect(dismissed()).toBe(1);
  });
});
