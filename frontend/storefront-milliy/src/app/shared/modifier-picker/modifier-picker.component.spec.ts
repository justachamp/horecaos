import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { TranslateService } from '../../services/translate.service';
import type {
  MenuItem,
  MenuItemModifierGroup,
  MenuItemModifierOption,
  MenuItemVariant,
} from '../../types/home.types';
import { ModifierPickerComponent, type ModifierSelection } from './modifier-picker.component';

class FakeTranslateService {
  get = (key: string): string => key;
  getWithParams = (key: string, params?: Record<string, string | number>): string =>
    params ? `${key}(${JSON.stringify(params)})` : key;
  current = (): Record<string, unknown> => ({});
}

function option(
  id: string,
  overrides: Partial<MenuItemModifierOption> = {},
): MenuItemModifierOption {
  return { id, label: id.toUpperCase(), amountMinor: null, maximumQuantity: 1, ...overrides };
}

function group(overrides: Partial<MenuItemModifierGroup> = {}): MenuItemModifierGroup {
  return {
    id: 'size',
    name: 'Size',
    required: true,
    minimumSelections: 1,
    maximumSelections: 1,
    allowSameOptionMultipleTimes: false,
    options: [option('small'), option('large')],
    ...overrides,
  };
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

function dish(
  groups: MenuItemModifierGroup[],
  variants: MenuItemVariant[] = [variant()],
): MenuItem {
  return {
    id: 'p1',
    name: 'Osh',
    description: '',
    active: true,
    has_discount: false,
    preparation_time: 0,
    price: variants[0].price,
    price_without_discount: variants[0].price,
    image: null,
    start: null,
    finish: null,
    discount: null,
    is_favourite: false,
    delivery_duration: 0,
    variants,
    modifierGroups: groups,
  };
}

@Component({
  standalone: true,
  imports: [ModifierPickerComponent],
  template: `<app-modifier-picker
    [item]="item()"
    [variantId]="variantId()"
    [currency]="'UZS'"
    [busy]="busy()"
    [errorKey]="errorKey()"
    (confirmed)="confirmed.push($event)"
    (dismissed)="dismissed = dismissed + 1"
  />`,
})
class Host {
  readonly item = signal<MenuItem>(dish([group()]));
  readonly variantId = signal('v1');
  readonly busy = signal(false);
  readonly errorKey = signal<string | null>(null);
  readonly confirmed: ModifierSelection[] = [];
  dismissed = 0;
}

function render(item: MenuItem = dish([group()])) {
  TestBed.configureTestingModule({
    imports: [Host],
    providers: [{ provide: TranslateService, useClass: FakeTranslateService }],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.item.set(item);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const api = {
    fixture,
    host,
    state: fixture.componentInstance,
    q: (testId: string) => host.querySelector<HTMLElement>(`[data-testid="${testId}"]`),
    all: (testId: string) =>
      Array.from(host.querySelectorAll<HTMLElement>(`[data-testid="${testId}"]`)),
    /** Taps the option with this label. */
    tap: (label: string) => {
      const found = api.all('modifier-option').find((entry) => entry.textContent?.includes(label));
      expect(found, `no option labelled ${label}`).toBeDefined();
      found!.click();
      fixture.detectChanges();
    },
    add: () => {
      api.q('modifier-picker-add')!.click();
      fixture.detectChanges();
    },
    addDisabled: () => (api.q('modifier-picker-add') as HTMLButtonElement).disabled,
  };
  return api;
}

describe("ModifierPickerComponent -- choosing a dish's options at a table (ADR 0047)", () => {
  it('names the dish and lists every group with its options, marking the mandatory ones', () => {
    const view = render(
      dish([
        group({ name: 'Size' }),
        group({
          id: 'extras',
          name: 'Extras',
          required: false,
          minimumSelections: 0,
          maximumSelections: 3,
          options: [option('meat', { amountMinor: 12_000 }), option('egg')],
        }),
      ]),
    );

    expect(view.q('modifier-picker-title')?.textContent).toContain('Osh');
    expect(view.all('modifier-group').length).toBe(2);
    expect(view.all('modifier-group-required').length).toBe(1);
    expect(
      view
        .all('modifier-option')
        .map((entry) => entry.querySelector('.option__name')?.textContent?.trim()),
    ).toEqual(['SMALL', 'LARGE', 'MEAT', 'EGG']);
    // A surcharge is shown on the option that carries one, and only there.
    expect(view.host.textContent).toContain('+12 000');
  });

  it('says what each group asks: exactly, a range, a floor, a ceiling, or nothing', () => {
    const view = render(
      dish([
        group({ id: 'a', minimumSelections: 1, maximumSelections: 1 }),
        group({ id: 'b', minimumSelections: 1, maximumSelections: 3 }),
        group({ id: 'c', minimumSelections: 2, maximumSelections: 0 }),
        group({ id: 'd', required: false, minimumSelections: 0, maximumSelections: 2 }),
        group({ id: 'e', required: false, minimumSelections: 0, maximumSelections: 0 }),
      ]),
    );

    const rules = view
      .all('modifier-group')
      .map(
        (entry) =>
          entry.querySelector('[data-testid="modifier-group-rule"]')?.textContent?.trim() ?? null,
      );
    expect(rules[0]).toContain('dineIn.pickerExactly');
    expect(rules[1]).toContain('dineIn.pickerBetween');
    expect(rules[1]).toContain('"min":1,"max":3');
    expect(rules[2]).toContain('dineIn.pickerAtLeast');
    expect(rules[3]).toContain('dineIn.pickerUpTo');
    expect(rules[4]).toBeNull();
  });

  describe('a required group with nothing chosen', () => {
    it('refuses to add and says which group is missing, instead of a button that does nothing', () => {
      const view = render(dish([group({ name: 'Size' }), group({ id: 'sauce', name: 'Sauce' })]));

      expect(view.addDisabled()).toBe(true);
      expect(view.q('modifier-picker-missing')?.textContent).toContain('Size, Sauce');

      view.add();
      expect(view.state.confirmed).toEqual([]);
    });

    it('marks the missing group and stops saying it once it is chosen', () => {
      const view = render(
        dish([
          group({ name: 'Size' }),
          group({ id: 'sauce', name: 'Sauce', options: [option('mild')] }),
        ]),
      );
      expect(
        view.all('modifier-group').map((entry) => entry.classList.contains('is-missing')),
      ).toEqual([true, true]);

      view.tap('SMALL');

      expect(
        view.all('modifier-group').map((entry) => entry.classList.contains('is-missing')),
      ).toEqual([false, true]);
      expect(view.q('modifier-picker-missing')?.textContent).toContain('Sauce');
      expect(view.q('modifier-picker-missing')?.textContent).not.toContain('Size');
      expect(view.addDisabled()).toBe(true);
    });

    it('lets the guest add once every mandatory group is satisfied', () => {
      const view = render(
        dish([
          group({ name: 'Size' }),
          group({ id: 'sauce', name: 'Sauce', options: [option('mild')] }),
        ]),
      );

      view.tap('LARGE');
      view.tap('MILD');

      expect(view.addDisabled()).toBe(false);
      expect(view.q('modifier-picker-missing')).toBeNull();
    });
  });

  describe('the selection it hands back', () => {
    it("is the portion, the quantity and every chosen option id in the dish's group order", () => {
      const view = render(
        dish([
          group({ name: 'Size' }),
          group({
            id: 'extras',
            name: 'Extras',
            required: false,
            minimumSelections: 0,
            maximumSelections: 3,
            options: [option('meat'), option('egg')],
          }),
        ]),
      );

      // Tapped in the opposite order to the dish's own group order.
      view.tap('EGG');
      view.tap('MEAT');
      view.tap('LARGE');
      view.q('modifier-picker-increase')!.click();
      view.fixture.detectChanges();
      view.add();

      expect(view.state.confirmed).toEqual([
        { variantId: 'v1', quantity: 2, modifierOptionIds: ['large', 'egg', 'meat'] },
      ]);
    });

    it('carries no options for an optional group left alone', () => {
      const view = render(
        dish([
          group(),
          group({ id: 'extras', required: false, minimumSelections: 0, options: [option('meat')] }),
        ]),
      );

      view.tap('SMALL');
      view.add();

      expect(view.state.confirmed[0].modifierOptionIds).toEqual(['small']);
    });

    it('is for the portion it was opened on', () => {
      const view = render(dish([group()], [variant({ id: 'small' }), variant({ id: 'large' })]));
      view.state.variantId.set('large');
      view.fixture.detectChanges();

      view.tap('SMALL');
      view.add();

      expect(view.state.confirmed[0].variantId).toBe('large');
    });
  });

  describe('the limits of a group', () => {
    it('a one-choice group swaps the choice rather than refusing the second tap', () => {
      const view = render();

      view.tap('SMALL');
      view.tap('LARGE');
      view.add();

      expect(view.state.confirmed[0].modifierOptionIds).toEqual(['large']);
    });

    it('a group at its ceiling ignores a further option, and a second tap takes one back', () => {
      const view = render(
        dish([
          group({
            minimumSelections: 1,
            maximumSelections: 2,
            options: [option('a'), option('b'), option('c')],
          }),
        ]),
      );

      view.tap('A');
      view.tap('B');
      view.tap('C');
      expect(
        view.all('modifier-option').map((entry) => entry.classList.contains('is-active')),
      ).toEqual([true, true, false]);

      view.tap('A');
      view.tap('C');
      view.add();
      expect(view.state.confirmed[0].modifierOptionIds).toEqual(['b', 'c']);
    });

    it('a group with no ceiling keeps taking options', () => {
      const view = render(
        dish([
          group({
            minimumSelections: 1,
            maximumSelections: 0,
            options: [option('a'), option('b'), option('c')],
          }),
        ]),
      );

      view.tap('A');
      view.tap('B');
      view.tap('C');
      view.add();

      expect(view.state.confirmed[0].modifierOptionIds).toEqual(['a', 'b', 'c']);
    });

    it('a group whose minimum is two needs two', () => {
      const view = render(
        dish([
          group({
            minimumSelections: 2,
            maximumSelections: 3,
            options: [option('a'), option('b'), option('c')],
          }),
        ]),
      );

      view.tap('A');
      expect(view.addDisabled()).toBe(true);
      view.tap('B');
      expect(view.addDisabled()).toBe(false);
    });
  });

  describe('quantity', () => {
    it('starts at one and never goes below it', () => {
      const view = render();

      expect(view.q('modifier-picker-quantity')?.textContent).toContain('1');
      expect((view.q('modifier-picker-decrease') as HTMLButtonElement).disabled).toBe(true);

      view.q('modifier-picker-increase')!.click();
      view.q('modifier-picker-increase')!.click();
      view.fixture.detectChanges();
      expect(view.q('modifier-picker-quantity')?.textContent).toContain('3');

      view.q('modifier-picker-decrease')!.click();
      view.fixture.detectChanges();
      expect(view.q('modifier-picker-quantity')?.textContent).toContain('2');
    });
  });

  describe('portions', () => {
    it('names the portion and its price only for a dish that has several', () => {
      const single = render();
      expect(single.q('modifier-picker-portion')).toBeNull();
      TestBed.resetTestingModule();

      const several = render(
        dish(
          [group()],
          [
            variant({ id: 'small', name: 'S', price: 30_000 }),
            variant({ id: 'large', name: 'L', price: 50_000 }),
          ],
        ),
      );
      several.state.variantId.set('large');
      several.fixture.detectChanges();
      expect(several.q('modifier-picker-portion')?.textContent).toContain('L');
      expect(several.q('modifier-picker-portion')?.textContent).toContain('50 000');
    });
  });

  describe('while a write to the basket is in flight, and when it fails', () => {
    it('cannot be added twice', () => {
      const view = render();
      view.tap('SMALL');
      view.state.busy.set(true);
      view.fixture.detectChanges();

      expect(view.addDisabled()).toBe(true);
      view.add();
      expect(view.state.confirmed).toEqual([]);
      expect(view.q('modifier-picker-add')?.textContent).toContain('common.saving');
    });

    it('says why the platform refused it, inside the sheet the guest is looking at', () => {
      const view = render();
      view.state.errorKey.set('errors.reason.itemUnavailable');
      view.fixture.detectChanges();

      expect(view.q('modifier-picker-error')?.textContent).toContain(
        'errors.reason.itemUnavailable',
      );
      expect(view.q('modifier-picker-error')?.getAttribute('role')).toBe('alert');
    });

    it('keeps what the guest chose across a refusal, so they can try again', () => {
      const view = render();
      view.tap('LARGE');
      view.state.errorKey.set('errors.reason.itemUnavailable');
      view.fixture.detectChanges();

      expect(view.all('modifier-option')[1].classList.contains('is-active')).toBe(true);
      expect(view.addDisabled()).toBe(false);
    });
  });

  describe('getting out', () => {
    it('the close button, the backdrop and Escape all dismiss it without adding anything', () => {
      const view = render();

      view.q('modifier-picker-close')!.click();
      view.q('modifier-picker-backdrop')!.click();
      view.q('modifier-picker')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));

      expect(view.state.dismissed).toBe(3);
      expect(view.state.confirmed).toEqual([]);
    });

    it('is a modal dialog labelled by the dish, and takes focus when it opens', async () => {
      const view = render();
      await view.fixture.whenStable();

      const dialog = view.q('modifier-picker')!;
      expect(dialog.getAttribute('role')).toBe('dialog');
      expect(dialog.getAttribute('aria-modal')).toBe('true');
      expect(dialog.getAttribute('aria-label')).toBe('Osh');
      expect(document.activeElement).toBe(dialog);
    });
  });
});

describe('ModifierPickerComponent -- portions (ADR 0137)', () => {
  const SPLITTABLE = { catchweight: false, splittable: true, portionSize: 0.5 } as const;

  function splittable(portionSize = 0.5) {
    return dish([group()], [variant({ physical: { ...SPLITTABLE, portionSize } })]);
  }

  it('starts a splittable portion at one whole portion', () => {
    const view = render(splittable());

    expect(view.q('modifier-picker-quantity')?.textContent?.trim()).toBe('1');
  });

  it('starts a portion that does not divide one at the first quantity the cart accepts', () => {
    const view = render(splittable(0.3));

    expect(view.q('modifier-picker-quantity')?.textContent?.trim()).toBe('1,2');
  });

  it('steps by the portion size and never below one portion', () => {
    const view = render(splittable());

    view.q('modifier-picker-increase')!.click();
    view.fixture.detectChanges();
    expect(view.q('modifier-picker-quantity')?.textContent?.trim()).toBe('1,5');
    view.q('modifier-picker-decrease')!.click();
    view.q('modifier-picker-decrease')!.click();
    view.fixture.detectChanges();
    expect(view.q('modifier-picker-quantity')?.textContent?.trim()).toBe('0,5');
    expect((view.q('modifier-picker-decrease') as HTMLButtonElement).disabled).toBe(true);
  });

  it('hands the chosen fraction to the screen', () => {
    const view = render(splittable());
    view.tap('SMALL');

    view.q('modifier-picker-decrease')!.click();
    view.q('modifier-picker-add')!.click();

    expect(view.state.confirmed[0].quantity).toBe(0.5);
  });

  it('keeps a plain portion in whole units, as before', () => {
    const view = render();

    view.q('modifier-picker-increase')!.click();
    view.fixture.detectChanges();

    expect(view.q('modifier-picker-quantity')?.textContent?.trim()).toBe('2');
  });
});
