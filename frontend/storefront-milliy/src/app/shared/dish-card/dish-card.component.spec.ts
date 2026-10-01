import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { DishCardComponent } from './dish-card.component';
import { TranslateService } from '../../services/translate.service';
import type {
  MenuItem,
  MenuItemModifierGroup,
  MenuItemModifierOption,
  MenuItemVariant,
} from '../../types/home.types';

class FakeTranslateService {
  get = (key: string): string => key;
  getWithParams = (key: string): string => key;
  current = (): Record<string, unknown> => ({});
}

function variant(overrides: Partial<MenuItemVariant> = {}): MenuItemVariant {
  return {
    id: 'v1',
    name: '',
    active: true,
    onSaleNow: true,
    preparation_time: 0,
    price: 45_000,
    price_without_discount: 45_000,
    remainingQuantity: null,
    ...overrides,
  };
}

function group(overrides: Partial<MenuItemModifierGroup> = {}): MenuItemModifierGroup {
  return {
    id: 'g1',
    name: 'Toppings',
    required: false,
    minimumSelections: 0,
    maximumSelections: 2,
    allowSameOptionMultipleTimes: false,
    options: [],
    ...overrides,
  };
}

function option(id: string): MenuItemModifierOption {
  return { id, label: id, amountMinor: null, maximumQuantity: 1 };
}

/** A mandatory group the guest can actually choose from: it offers options. */
function choice(overrides: Partial<MenuItemModifierGroup> = {}): MenuItemModifierGroup {
  return group({
    id: 'size',
    name: 'Size',
    required: true,
    minimumSelections: 1,
    maximumSelections: 1,
    options: [option('small'), option('large')],
    ...overrides,
  });
}

function dish(
  variants: MenuItemVariant[] = [variant()],
  modifierGroups: MenuItemModifierGroup[] = [],
): MenuItem {
  return {
    id: 'p1',
    name: 'Osh',
    description: '',
    active: variants.some((entry) => entry.active),
    has_discount: false,
    preparation_time: 0,
    price: variants[0]?.price ?? 0,
    price_without_discount: variants[0]?.price ?? 0,
    image: null,
    start: null,
    finish: null,
    discount: null,
    is_favourite: false,
    delivery_duration: 0,
    variants,
    modifierGroups,
  };
}

interface Inputs {
  item: MenuItem;
  linked: boolean;
  ordering: boolean;
  quantities: Record<string, number>;
  busy: boolean;
}

@Component({
  standalone: true,
  imports: [DishCardComponent],
  template: `<app-dish-card
    [item]="inputs().item"
    [currency]="'UZS'"
    [linked]="inputs().linked"
    [ordering]="inputs().ordering"
    [quantities]="inputs().quantities"
    [busy]="inputs().busy"
    (quantityChange)="changes.push($event)"
    (choose)="chooses.push($event)"
  />`,
})
class Host {
  readonly inputs = signal<Inputs>({
    item: dish(),
    linked: false,
    ordering: true,
    quantities: {},
    busy: false,
  });
  readonly changes: { variantId: string; quantity: number }[] = [];
  readonly chooses: { item: MenuItem; variantId: string }[] = [];
}

function render(inputs: Partial<Inputs> = {}) {
  TestBed.configureTestingModule({
    imports: [Host],
    providers: [provideRouter([]), { provide: TranslateService, useClass: FakeTranslateService }],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.inputs.update((current) => ({ ...current, ...inputs }));
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return {
    fixture,
    host,
    changes: fixture.componentInstance.changes,
    chooses: fixture.componentInstance.chooses,
    set: (next: Partial<Inputs>) => {
      fixture.componentInstance.inputs.update((current) => ({ ...current, ...next }));
      fixture.detectChanges();
    },
    q: (testId: string) => host.querySelector<HTMLButtonElement>(`[data-testid="${testId}"]`),
    all: (testId: string) =>
      Array.from(host.querySelectorAll<HTMLButtonElement>(`[data-testid="${testId}"]`)),
  };
}

describe('DishCardComponent -- ordering at a table (ADR 0047)', () => {
  it('draws no ordering control unless the screen asks for ordering: the menu-only table and the home screen are unchanged', () => {
    const view = render({ ordering: false });

    expect(view.q('dine-in-add')).toBeNull();
    expect(view.q('dine-in-increase')).toBeNull();
  });

  it('never draws a control inside a link: a linked card opens the product page, which orders into the delivery basket', () => {
    const view = render({ linked: true, ordering: true });

    expect(view.host.querySelector('a.dish')).not.toBeNull();
    expect(view.q('dine-in-add')).toBeNull();
  });

  it('offers to add a dish that can be bought, and asks for one of the portion it shows', () => {
    const view = render();

    view.q('dine-in-add')!.click();

    expect(view.changes).toEqual([{ variantId: 'v1', quantity: 1 }]);
  });

  it('shows the quantity and a stepper once the basket holds the dish', () => {
    const view = render({ quantities: { v1: 2 } });

    expect(view.q('dine-in-add')).toBeNull();
    expect(view.q('dine-in-quantity')?.textContent).toContain('2');

    view.q('dine-in-increase')!.click();
    view.q('dine-in-decrease')!.click();
    expect(view.changes).toEqual([
      { variantId: 'v1', quantity: 3 },
      { variantId: 'v1', quantity: 1 },
    ]);
  });

  it('asks for zero when the last one is taken out, which removes the line', () => {
    const view = render({ quantities: { v1: 1 } });

    view.q('dine-in-decrease')!.click();

    expect(view.changes).toEqual([{ variantId: 'v1', quantity: 0 }]);
  });

  it('waits while a write to the basket is in flight, so two taps cannot both ask for the same quantity', () => {
    const view = render({ busy: true, quantities: { v1: 1 } });

    expect(view.q('dine-in-increase')!.disabled).toBe(true);
    expect(view.q('dine-in-decrease')!.disabled).toBe(true);

    view.set({ quantities: {} });
    expect(view.q('dine-in-add')!.disabled).toBe(true);
  });

  describe('a dish with several portions', () => {
    const portions = () => [
      variant({ id: 'small', name: 'S', price: 30_000 }),
      variant({ id: 'large', name: 'L', price: 50_000 }),
    ];

    it('lets the guest order each portion on its own, named and priced', () => {
      const view = render({ item: dish(portions()), quantities: { large: 1 } });

      expect(view.host.textContent).toContain('S');
      expect(view.host.textContent).toContain('30\u00a0000');
      expect(view.host.textContent).toContain('L');
      expect(view.host.textContent).toContain('50\u00a0000');
      expect(view.all('dine-in-add').length).toBe(1);
      expect(view.all('dine-in-quantity').length).toBe(1);

      view.q('dine-in-add')!.click();
      expect(view.changes).toEqual([{ variantId: 'small', quantity: 1 }]);
    });

    it('offers only the portions that can be bought right now', () => {
      const view = render({
        item: dish([
          variant({ id: 'small', name: 'S' }),
          variant({ id: 'large', name: 'L', active: false }),
          variant({ id: 'xl', name: 'XL', onSaleNow: false }),
        ]),
      });

      expect(view.all('dine-in-add').length).toBe(1);
      view.q('dine-in-add')!.click();
      expect(view.changes).toEqual([{ variantId: 'small', quantity: 1 }]);
    });
  });

  describe('a dish that cannot be bought', () => {
    it('sold out: says so and offers nothing', () => {
      const view = render({ item: dish([variant({ active: false })]) });

      expect(view.q('dish-sold-out')).not.toBeNull();
      expect(view.q('dine-in-add')).toBeNull();
    });

    it('outside its sale window: says so and offers nothing', () => {
      const view = render({ item: dish([variant({ onSaleNow: false })]) });

      expect(view.q('dish-out-of-window')).not.toBeNull();
      expect(view.q('dine-in-add')).toBeNull();
    });
  });

  describe('a portion the basket holds that can no longer be bought (it sold out, or its window closed)', () => {
    it('keeps a stepper that takes it out: the guest cannot order again while a dead line is held', () => {
      const view = render({
        item: dish([variant({ active: false })]),
        quantities: { v1: 2 },
      });

      expect(view.q('dish-sold-out')).not.toBeNull();
      expect(view.q('dine-in-quantity')?.textContent).toContain('2');
      expect(view.q('dine-in-held-unavailable')).not.toBeNull();

      view.q('dine-in-decrease')!.click();
      // The whole line, not one fewer: the platform checks stock on every write of
      // a line, so a PUT of quantity 1 for a sold-out portion is refused too.
      expect(view.changes).toEqual([{ variantId: 'v1', quantity: 0 }]);
    });

    it('asks for zero when the last one is taken out, and cannot be raised past what it holds', () => {
      const view = render({
        item: dish([variant({ onSaleNow: false })]),
        quantities: { v1: 1 },
      });

      expect(view.q('dine-in-increase')!.disabled).toBe(true);
      view.q('dine-in-decrease')!.click();
      expect(view.changes).toEqual([{ variantId: 'v1', quantity: 0 }]);
    });

    it('offers nothing for a portion that is unavailable and not held', () => {
      const view = render({
        item: dish([
          variant({ id: 'small', name: 'S' }),
          variant({ id: 'large', name: 'L', active: false }),
        ]),
        quantities: { small: 1 },
      });

      expect(view.all('dine-in-quantity').length).toBe(1);
      expect(view.q('dine-in-held-unavailable')).toBeNull();
    });

    it('keeps the way out of a held line on a dish whose modifier group has since become mandatory', () => {
      const view = render({
        item: dish([variant()], [group({ required: true, minimumSelections: 1 })]),
        quantities: { v1: 1 },
      });

      expect(view.q('dine-in-add')).toBeNull();
      expect(view.q('dine-in-increase')!.disabled).toBe(true);
      view.q('dine-in-decrease')!.click();
      expect(view.changes).toEqual([{ variantId: 'v1', quantity: 0 }]);
    });
  });

  describe('a dish that asks the guest to choose something', () => {
    it('with a required group offers to choose its options instead of a plain add', () => {
      const item = dish([variant()], [choice()]);
      const view = render({ item });

      expect(view.q('dine-in-add')).toBeNull();
      expect(view.q('dine-in-needs-staff')).toBeNull();
      expect(view.q('dine-in-choose')?.textContent).toContain('dineIn.choose');

      view.q('dine-in-choose')!.click();

      // The card only reports the wish; the screen opens the picker and writes the line.
      expect(view.chooses).toEqual([{ item, variantId: 'v1' }]);
      expect(view.changes).toEqual([]);
    });

    it('with a group that must have a minimum is treated the same, whatever its required flag says', () => {
      const view = render({
        item: dish([variant()], [choice({ required: false, minimumSelections: 1 })]),
      });

      expect(view.q('dine-in-add')).toBeNull();
      expect(view.q('dine-in-choose')).not.toBeNull();
    });

    it('offers to choose for each portion that can be bought, naming the portion', () => {
      const item = dish(
        [
          variant({ id: 'small', name: 'S', price: 30_000 }),
          variant({ id: 'large', name: 'L', price: 50_000 }),
          variant({ id: 'gone', name: 'XL', active: false }),
        ],
        [choice()],
      );
      const view = render({ item });

      expect(view.all('dine-in-choose').length).toBe(2);
      expect(view.host.textContent).toContain('S');
      expect(view.host.textContent).toContain('50\u00a0000');

      view.all('dine-in-choose')[1].click();
      expect(view.chooses).toEqual([{ item, variantId: 'large' }]);
    });

    it('offers no choosing on a dish that cannot be bought: its badge says why', () => {
      const view = render({ item: dish([variant({ active: false })], [choice()]) });

      expect(view.q('dish-sold-out')).not.toBeNull();
      expect(view.q('dine-in-choose')).toBeNull();
    });

    it('draws no Choose button unless the table takes orders: the menu-only table and the home screen are unchanged', () => {
      const view = render({ item: dish([variant()], [choice()]), ordering: false });

      expect(view.q('dine-in-choose')).toBeNull();
    });

    it('never draws a Choose button inside a link: a linked card opens the product page', () => {
      const view = render({ item: dish([variant()], [choice()]), linked: true });

      expect(view.q('dine-in-choose')).toBeNull();
    });

    it('with a mandatory group that offers nothing to choose from, says a member of staff will help', () => {
      const view = render({
        item: dish([variant()], [group({ required: true, minimumSelections: 1, options: [] })]),
      });

      expect(view.q('dine-in-add')).toBeNull();
      expect(view.q('dine-in-choose')).toBeNull();
      expect(view.q('dine-in-needs-staff')?.textContent).toContain('dineIn.needsStaff');
    });

    it('with a mandatory group that offers fewer options than its minimum is treated the same', () => {
      const view = render({
        item: dish([variant()], [choice({ minimumSelections: 3, maximumSelections: 3 })]),
      });

      expect(view.q('dine-in-choose')).toBeNull();
      expect(view.q('dine-in-needs-staff')).not.toBeNull();
    });

    it('with only optional groups can be ordered plain, with nothing to choose', () => {
      const view = render({ item: dish([variant()], [group()]) });

      expect(view.q('dine-in-add')).not.toBeNull();
      expect(view.q('dine-in-choose')).toBeNull();
      expect(view.q('dine-in-needs-staff')).toBeNull();
    });

    it('keeps the way out of a plain portion already held, and still offers to choose', () => {
      const view = render({ item: dish([variant()], [choice()]), quantities: { v1: 2 } });

      expect(view.q('dine-in-increase')!.disabled).toBe(true);
      expect(view.q('dine-in-choose')).not.toBeNull();
      view.q('dine-in-decrease')!.click();
      // The way out is the whole line: a plain line of a dish that must be chosen
      // from cannot be written at any quantity (the platform refuses the PUT), so
      // lowering it to one would be refused and the guest could never reach zero.
      expect(view.changes).toEqual([{ variantId: 'v1', quantity: 0 }]);
    });

    it('takes the whole plain line out for a dish only staff can put in, however many are held', () => {
      const view = render({
        item: dish([variant()], [choice({ options: [] })]),
        quantities: { v1: 3 },
      });

      view.q('dine-in-decrease')!.click();
      expect(view.changes).toEqual([{ variantId: 'v1', quantity: 0 }]);
    });
  });
});
