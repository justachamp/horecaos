import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { signal } from '@angular/core';

import { APP_CONFIG, type AppConfig } from '../../core/config/app-config';
import { DetailsComponent } from './details.component';
import { LangService } from '../../services/lang.service';
import { MenuService } from '../../services/menu.service';
import { TranslateService } from '../../services/translate.service';
import { UiCartService } from '../../services/ui-cart.service';
import type { MenuItem, MenuItemComboGroup, MenuItemModifierGroup } from '../../types/home.types';

const TEST_APP_CONFIG: AppConfig = {
  apiBaseUrl: '/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
  defaultLocationId: '10000000-0000-0000-0000-000000000003',
  channel: 'STOREFRONT',
  yandexMapsApiKey: '',
  brand: { displayName: 'Test Brand', theme: { accent: '#000000', accentDeep: '#000000' } },
};

function group(overrides: Partial<MenuItemModifierGroup> = {}): MenuItemModifierGroup {
  return {
    id: 'g1',
    name: 'Qo‘shimchalar',
    required: false,
    minimumSelections: 0,
    maximumSelections: 2,
    allowSameOptionMultipleTimes: false,
    options: [
      { id: 'o1', label: 'Extra meat', amountMinor: 500, maximumQuantity: 1 },
      { id: 'o2', label: 'Extra bread', amountMinor: 200, maximumQuantity: 1 },
      { id: 'o3', label: 'Herbs', amountMinor: null, maximumQuantity: 1 },
    ],
    ...overrides,
  };
}

function product(overrides: Partial<MenuItem> = {}): MenuItem {
  return {
    id: 'p1',
    name: 'Osh',
    description: 'Mahalliy demo osh',
    active: true,
    has_discount: false,
    preparation_time: 20,
    price: 48000,
    price_without_discount: 48000,
    image: null,
    start: null,
    finish: null,
    discount: null,
    is_favourite: false,
    delivery_duration: 30,
    variants: [
      {
        id: 'v1',
        name: 'Kichik',
        active: true,
        onSaleNow: true,
        preparation_time: 20,
        price: 48000,
        price_without_discount: 48000,
        remainingQuantity: null,
      },
      {
        id: 'v2',
        name: 'Katta',
        active: true,
        onSaleNow: true,
        preparation_time: 25,
        price: 62000,
        price_without_discount: 62000,
        remainingQuantity: null,
      },
    ],
    modifierGroups: [],
    ...overrides,
  };
}

class FakeMenuService {
  readonly currency = signal<string | null>('UZS');
  item = vi.fn(async () => product());
}
class FakeUiCartService {
  readonly errorKey = signal<string | null>(null);
  add = vi.fn(async (..._args: unknown[]) => true);
  totalItemsCount = () => 0;
}
class FakeLangService {
  langId = () => 'uz';
}
class FakeTranslateService {
  get = (key: string) => key;
  getWithParams = (key: string, params?: Record<string, string | number>) =>
    params ? `${key}(${JSON.stringify(params)})` : key;
  current = () => ({});
}

async function setUp(item: MenuItem = product()) {
  const menu = new FakeMenuService();
  menu.item.mockResolvedValue(item);
  const cart = new FakeUiCartService();

  TestBed.configureTestingModule({
    imports: [DetailsComponent],
    providers: [
      provideRouter([]),
      { provide: MenuService, useValue: menu },
      { provide: UiCartService, useValue: cart },
      { provide: LangService, useClass: FakeLangService },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: APP_CONFIG, useValue: TEST_APP_CONFIG },
    ],
  });

  const fixture = TestBed.createComponent(DetailsComponent);
  fixture.componentRef.setInput('productId', 'p1');
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
  return {
    fixture,
    comp: fixture.componentInstance as never as InternalDetails,
    menu,
    cart,
    router: TestBed.inject(Router),
  };
}

/** The protected surface these tests drive, named once rather than cast inline. */
interface InternalDetails {
  variantId(): string | null;
  quantity(): number;
  chosenIn(groupId: string): readonly string[];
  toggleOption(group: MenuItemModifierGroup, optionId: string): void;
  canAdd(): boolean;
  addError(): string | null;
  step(by: number): void;
  selectVariant(variantId: string): void;
  addToCart(): Promise<void>;
  setComboPicks(next: Readonly<Record<string, number>>): void;
  comboComplete(): boolean;
  comboUnavailable(): boolean;
}

describe('DetailsComponent', () => {
  it('preselects the first active variant, because the customer cannot choose one they cannot see', async () => {
    const { comp } = await setUp();

    expect(comp.variantId()).toBe('v1');
  });

  it('refuses to add while a required group is unsatisfied', async () => {
    const { comp, cart } = await setUp(
      product({ modifierGroups: [group({ required: true, minimumSelections: 1 })] }),
    );

    expect(comp.canAdd()).toBe(false);

    await comp.addToCart();
    expect(cart.add).not.toHaveBeenCalled();

    comp.toggleOption(group({ required: true, minimumSelections: 1 }), 'o1');
    expect(comp.canAdd()).toBe(true);
  });

  it('replaces rather than accumulates in a single-selection group', async () => {
    const single = group({ maximumSelections: 1 });
    const { comp } = await setUp(product({ modifierGroups: [single] }));

    comp.toggleOption(single, 'o1');
    comp.toggleOption(single, 'o2');

    // Pressing a second option in a one-of group plainly means "change my
    // mind", not "refuse me" -- and never "take both", which the platform
    // would reject at the line.
    expect(comp.chosenIn('g1')).toEqual(['o2']);
  });

  it('stops at the group maximum instead of quietly taking a third', async () => {
    const two = group({ maximumSelections: 2 });
    const { comp } = await setUp(product({ modifierGroups: [two] }));

    comp.toggleOption(two, 'o1');
    comp.toggleOption(two, 'o2');
    comp.toggleOption(two, 'o3');

    expect(comp.chosenIn('g1')).toEqual(['o1', 'o2']);
  });

  it('takes as many options as the guest likes from a group with no ceiling: a maximum of zero is "unlimited", not "none"', async () => {
    const open = group({ maximumSelections: 0 });
    const { comp } = await setUp(product({ modifierGroups: [open] }));

    comp.toggleOption(open, 'o1');
    comp.toggleOption(open, 'o2');
    comp.toggleOption(open, 'o3');

    expect(comp.chosenIn('g1')).toEqual(['o1', 'o2', 'o3']);
  });

  it('holds a group that must have a minimum to it whatever its required flag says, as the table screen does', async () => {
    const pair = group({ required: false, minimumSelections: 2, maximumSelections: 3 });
    const { comp, cart } = await setUp(product({ modifierGroups: [pair] }));

    expect(comp.canAdd()).toBe(false);
    await comp.addToCart();
    expect(cart.add).not.toHaveBeenCalled();

    comp.toggleOption(pair, 'o1');
    expect(comp.canAdd()).toBe(false);
    comp.toggleOption(pair, 'o2');
    expect(comp.canAdd()).toBe(true);
  });

  describe('a group that must be chosen from, however it is authored', () => {
    const text = (host: HTMLElement, id: string): string | null =>
      host.querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? null;

    it('is marked and its rule is said, for a non-required group with a minimum too', async () => {
      const pair = group({
        name: 'Sides',
        required: false,
        minimumSelections: 2,
        maximumSelections: 3,
      });
      const { fixture } = await setUp(product({ modifierGroups: [pair] }));
      const host = fixture.nativeElement as HTMLElement;

      expect(host.querySelector('[data-testid="details-group-required"]')).not.toBeNull();
      expect(text(host, 'details-group-rule')).toContain('dineIn.pickerBetween');
      expect(text(host, 'details-group-rule')).toContain('"min":2,"max":3');
    });

    it('is named in the hint beside the disabled add, and the hint goes once it is satisfied', async () => {
      const pair = group({
        name: 'Sides',
        required: false,
        minimumSelections: 2,
        maximumSelections: 3,
      });
      const bread = group({ id: 'g2', name: 'Bread', required: true, maximumSelections: 1 });
      const { fixture, comp } = await setUp(product({ modifierGroups: [pair, bread] }));
      const host = fixture.nativeElement as HTMLElement;

      expect(text(host, 'details-missing')).toContain('Sides, Bread');
      expect(host.querySelectorAll('.rule.is-missing').length).toBe(2);

      comp.toggleOption(pair, 'o1');
      comp.toggleOption(pair, 'o2');
      fixture.detectChanges();
      expect(text(host, 'details-missing')).toContain('"groups":"Bread"');
      expect(host.querySelectorAll('.rule.is-missing').length).toBe(1);

      comp.toggleOption(bread, 'o1');
      fixture.detectChanges();
      expect(host.querySelector('[data-testid="details-missing"]')).toBeNull();
    });

    it('leaves an optional group unmarked, with only its ceiling said', async () => {
      const { fixture } = await setUp(product({ modifierGroups: [group()] }));
      const host = fixture.nativeElement as HTMLElement;

      expect(host.querySelector('[data-testid="details-group-required"]')).toBeNull();
      expect(text(host, 'details-group-rule')).toContain('dineIn.pickerUpTo');
      expect(host.querySelector('[data-testid="details-missing"]')).toBeNull();
    });
  });

  it('never lets quantity fall below one', async () => {
    const { comp } = await setUp();

    comp.step(-5);

    expect(comp.quantity()).toBe(1);
  });

  it('sends the variant, the quantity and every chosen modifier to the basket', async () => {
    const two = group();
    const { comp, cart } = await setUp(product({ modifierGroups: [two] }));

    comp.toggleOption(two, 'o1');
    comp.step(2);
    await comp.addToCart();

    expect(cart.add).toHaveBeenCalledWith('v1', 3, undefined, ['o1']);
  });

  // ----------------------------------------- rows 4.4c/4.4d: low-stock display

  it('shows the low-stock notice for the selected variant when the server reports a remaining count', async () => {
    const { fixture } = await setUp(
      product({
        variants: [
          {
            id: 'v1',
            name: 'Kichik',
            active: true,
            onSaleNow: true,
            preparation_time: 20,
            price: 48000,
            price_without_discount: 48000,
            remainingQuantity: 3,
          },
        ],
      }),
    );
    const host = fixture.nativeElement as HTMLElement;

    const notice = host.querySelector('[data-testid="details-low-stock"]');
    expect(notice).not.toBeNull();
  });

  it('shows no low-stock notice when the server sent no remaining count', async () => {
    const { fixture } = await setUp();
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="details-low-stock"]')).toBeNull();
  });
});

describe('DetailsComponent -- sold out and the sale window (rows 4.4c/4.4d, 4.2g)', () => {
  function variantOf(overrides: Record<string, unknown> = {}) {
    return {
      id: 'v1',
      name: 'Kichik',
      active: true,
      onSaleNow: true,
      preparation_time: 20,
      price: 48000,
      price_without_discount: 48000,
      remainingQuantity: null,
      ...overrides,
    };
  }

  it('says so and refuses the add when every portion is sold out -- the button no longer sits enabled over nothing', async () => {
    const { comp, fixture, cart } = await setUp(
      product({ active: false, variants: [variantOf({ active: false })] }),
    );
    const host = fixture.nativeElement as HTMLElement;

    expect(comp.canAdd()).toBe(false);
    expect(host.querySelector('[data-testid="details-sold-out"]')?.textContent).toContain(
      'dish.soldOut',
    );
    expect((host.querySelector('.cta') as HTMLButtonElement).disabled).toBe(true);

    await comp.addToCart();
    expect(cart.add).not.toHaveBeenCalled();
  });

  it('shows the sale-window text and refuses the add for a dish outside its window, without calling it sold out', async () => {
    const { comp, fixture, cart } = await setUp(
      product({ variants: [variantOf({ onSaleNow: false })] }),
    );
    const host = fixture.nativeElement as HTMLElement;

    expect(comp.canAdd()).toBe(false);
    expect(host.querySelector('[data-testid="details-out-of-sale-window"]')?.textContent).toContain(
      'dish.outOfSaleWindow',
    );
    expect(host.querySelector('[data-testid="details-sold-out"]')).toBeNull();
    expect((host.querySelector('.cta') as HTMLButtonElement).disabled).toBe(true);

    await comp.addToCart();
    expect(cart.add).not.toHaveBeenCalled();
  });

  it('preselects a portion that can be bought right now over an earlier one waiting for its window', async () => {
    const { comp } = await setUp(
      product({
        variants: [
          variantOf({ id: 'v1', onSaleNow: false }),
          variantOf({ id: 'v2', name: 'Katta' }),
        ],
      }),
    );

    expect(comp.variantId()).toBe('v2');
    expect(comp.canAdd()).toBe(true);
  });

  it('preselects the authored default when it can be bought, even if an earlier portion can be too -- the portion the card priced', async () => {
    const { comp } = await setUp(
      product({
        variants: [
          variantOf({ id: 'v1', name: 'Kichik', price: 10_000 }),
          variantOf({ id: 'v2', name: 'Katta', price: 18_000, isDefault: true }),
        ],
      }),
    );

    expect(comp.variantId()).toBe('v2');
  });

  it('skips an authored default that is off its window for the portion that is on sale', async () => {
    const { comp } = await setUp(
      product({
        variants: [
          variantOf({
            id: 'v1',
            name: 'Nonushta',
            price: 10_000,
            isDefault: true,
            onSaleNow: false,
          }),
          variantOf({ id: 'v2', name: 'Kunlik', price: 18_000 }),
        ],
      }),
    );

    expect(comp.variantId()).toBe('v2');
    expect(comp.canAdd()).toBe(true);
  });

  it('follows the chosen portion: picking the off-window one blocks the add, picking the other frees it', async () => {
    const { comp, fixture } = await setUp(
      product({
        variants: [
          variantOf({ id: 'v1' }),
          variantOf({ id: 'v2', name: 'Katta', onSaleNow: false }),
        ],
      }),
    );
    const host = fixture.nativeElement as HTMLElement;
    expect(comp.canAdd()).toBe(true);

    const chips = host.querySelectorAll<HTMLButtonElement>('.option');
    chips[1].click();
    fixture.detectChanges();
    expect(comp.canAdd()).toBe(false);
    expect(host.querySelector('[data-testid="details-out-of-sale-window"]')).not.toBeNull();

    chips[0].click();
    fixture.detectChanges();
    expect(comp.canAdd()).toBe(true);
    expect(host.querySelector('[data-testid="details-out-of-sale-window"]')).toBeNull();
  });

  it('shows neither notice for an ordinary dish', async () => {
    const { fixture } = await setUp();
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="details-sold-out"]')).toBeNull();
    expect(host.querySelector('[data-testid="details-out-of-sale-window"]')).toBeNull();
  });
});

describe('DetailsComponent.addToCart -- a refusal stays on the page and says why', () => {
  it('goes to the basket when the platform took the line', async () => {
    const { comp, router } = await setUp();
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    await comp.addToCart();

    expect(navigate).toHaveBeenCalledWith(['/cart']);
    expect(comp.addError()).toBeNull();
  });

  it('a sale-window refusal (the menu was read a moment before the window closed) shows its sentence and does not navigate away', async () => {
    const { comp, cart, router, fixture } = await setUp();
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    cart.add.mockImplementation(async () => {
      cart.errorKey.set('errors.reason.itemOutOfSaleWindow');
      return false;
    });

    await comp.addToCart();
    fixture.detectChanges();

    expect(navigate).not.toHaveBeenCalled();
    expect(comp.addError()).toBe('errors.reason.itemOutOfSaleWindow');
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')?.textContent,
    ).toContain('errors.reason.itemOutOfSaleWindow');
  });

  it('a sold-out refusal is named too', async () => {
    const { comp, cart } = await setUp();
    cart.add.mockImplementation(async () => {
      cart.errorKey.set('errors.reason.itemUnavailable');
      return false;
    });

    await comp.addToCart();

    expect(comp.addError()).toBe('errors.reason.itemUnavailable');
  });

  it('a failure with nothing more specific to say keeps this screen\'s own "could not add" sentence', async () => {
    const { comp, cart } = await setUp();
    cart.add.mockImplementation(async () => {
      cart.errorKey.set('errors.generic');
      return false;
    });

    await comp.addToCart();

    expect(comp.addError()).toBe('details.addFailed');
  });

  it('lets the customer try again after a refusal (the busy flag is released)', async () => {
    const { comp, cart } = await setUp();
    cart.add.mockImplementationOnce(async () => {
      cart.errorKey.set('errors.offline');
      return false;
    });

    await comp.addToCart();
    expect(comp.canAdd()).toBe(true);
  });
});

describe('DetailsComponent -- a combo (ADR 0136)', () => {
  const comboGroup = (overrides: Partial<MenuItemComboGroup> = {}): MenuItemComboGroup => ({
    id: 'g-main',
    name: 'Main',
    minimumSelections: 1,
    maximumSelections: 1,
    allowSameComponentMultipleTimes: false,
    components: [
      {
        id: 'c-burger',
        name: 'Burger',
        variantName: null,
        defaultQuantity: 1,
        active: true,
        amountMinor: 25_000,
      },
      {
        id: 'c-wrap',
        name: 'Wrap',
        variantName: null,
        defaultQuantity: 1,
        active: true,
        amountMinor: 22_000,
      },
    ],
    ...overrides,
  });
  const combo = (groups: MenuItemComboGroup[] = [comboGroup()]) =>
    product({ variants: [product().variants[0]], comboGroups: groups });
  const q = (fixture: { nativeElement: unknown }, testId: string) =>
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${testId}"]`);

  it('draws its choices instead of the modifier groups, and says they are incomplete until the minimum is met', async () => {
    const { fixture, comp } = await setUp(combo());

    expect(q(fixture, 'details-combo')).not.toBeNull();
    expect(q(fixture, 'details-combo-incomplete')).not.toBeNull();
    expect(comp.canAdd()).toBe(false);
  });

  it('refuses the add until the choices are made, and never writes a bare container', async () => {
    const { comp, cart } = await setUp(combo());

    await comp.addToCart();

    expect(cart.add).not.toHaveBeenCalled();
  });

  it('puts the container into the basket with the picks, once the minimum is met', async () => {
    const { fixture, comp, cart } = await setUp(combo());

    comp.setComboPicks({ 'c-burger': 1 });
    fixture.detectChanges();
    comp.step(1);
    await comp.addToCart();

    expect(cart.add).toHaveBeenCalledWith(
      'v1',
      2,
      undefined,
      [],
      [{ componentId: 'c-burger', quantity: 1 }],
    );
  });

  it('shows what one combo costs once complete', async () => {
    const { fixture, comp } = await setUp(combo());

    comp.setComboPicks({ 'c-wrap': 1 });
    fixture.detectChanges();

    expect(q(fixture, 'details-combo-total')?.textContent).toContain('combo.total');
    expect(q(fixture, 'details-combo-incomplete')).toBeNull();
  });

  it('shows no total while a picked component has no price', async () => {
    const { fixture, comp } = await setUp(
      combo([
        comboGroup({
          components: [
            {
              id: 'c-burger',
              name: 'Burger',
              variantName: null,
              defaultQuantity: 1,
              active: true,
              amountMinor: null,
            },
          ],
        }),
      ]),
    );

    comp.setComboPicks({ 'c-burger': 1 });
    fixture.detectChanges();

    expect(comp.comboComplete()).toBe(true);
    expect(q(fixture, 'details-combo-total')).toBeNull();
  });

  it('cannot be added when a choice has no orderable component, and says so', async () => {
    const { fixture, comp, cart } = await setUp(
      combo([
        comboGroup({
          components: [
            {
              id: 'c-burger',
              name: 'Burger',
              variantName: null,
              defaultQuantity: 1,
              active: false,
              amountMinor: 25_000,
            },
          ],
        }),
      ]),
    );

    expect(comp.comboUnavailable()).toBe(true);
    expect(q(fixture, 'details-combo-unavailable')).not.toBeNull();
    expect(comp.canAdd()).toBe(false);
    await comp.addToCart();
    expect(cart.add).not.toHaveBeenCalled();
  });

  it('adds a dish that is no combo exactly as before, with no picks argument', async () => {
    const { comp, cart } = await setUp();

    await comp.addToCart();

    expect(cart.add).toHaveBeenCalledWith('v1', 1, undefined, []);
  });
});

describe('DetailsComponent -- portions and weighed items (ADR 0137)', () => {
  const NBSP = '\u00a0';
  const SPLITTABLE = { catchweight: false, splittable: true, portionSize: 0.5 } as const;
  const CAKE = {
    catchweight: true,
    catchweightQuantumGrams: 100,
    catchweightNominalGrams: 1_200,
    splittable: false,
    netWeightGrams: 1_200,
  } as const;

  /** A single-portion dish whose one variant carries the given physical facts. */
  function withPhysical(physical: unknown, price = 48_000): MenuItem {
    const base = product();
    return product({
      variants: [{ ...base.variants[0], price, physical } as MenuItem['variants'][number]],
    });
  }

  it('starts a splittable variant at one whole portion', async () => {
    const { comp } = await setUp(withPhysical(SPLITTABLE));

    expect(comp.quantity()).toBe(1);
  });

  it('starts a variant whose portion does not divide one at the first quantity the cart accepts', async () => {
    const { comp } = await setUp(
      withPhysical({ catchweight: false, splittable: true, portionSize: 0.3 }),
    );

    expect(comp.quantity()).toBe(1.2);
  });

  it('steps by the portion size and never below one portion', async () => {
    const { comp } = await setUp(withPhysical(SPLITTABLE));

    comp.step(1);
    expect(comp.quantity()).toBe(1.5);
    comp.step(-1);
    comp.step(-1);
    expect(comp.quantity()).toBe(0.5);
    comp.step(-1);
    expect(comp.quantity()).toBe(0.5);
  });

  it('sends the fraction the guest chose to the basket', async () => {
    const { comp, cart } = await setUp(withPhysical(SPLITTABLE));

    comp.step(-1);
    await comp.addToCart();

    expect(cart.add).toHaveBeenCalledWith('v1', 0.5, undefined, []);
  });

  it('keeps a plain variant in whole units, as before', async () => {
    const { comp } = await setUp();

    comp.step(1);

    expect(comp.quantity()).toBe(2);
  });

  it('follows the chosen portion: switching to a variant with another step snaps the quantity to it', async () => {
    const base = product();
    const item = product({
      variants: [
        { ...base.variants[0], physical: SPLITTABLE },
        {
          ...base.variants[1],
          physical: { catchweight: false, splittable: true, portionSize: 0.4 },
        },
      ] as MenuItem['variants'],
    });
    const { comp } = await setUp(item);
    comp.step(1);
    expect(comp.quantity()).toBe(1.5);

    comp.selectVariant('v2');

    expect(comp.variantId()).toBe('v2');
    expect(comp.quantity()).toBe(1.2);
  });

  it('keeps the quantity when the next portion accepts it', async () => {
    const base = product();
    const item = product({
      variants: [
        { ...base.variants[0], physical: SPLITTABLE },
        { ...base.variants[1], physical: SPLITTABLE },
      ] as MenuItem['variants'],
    });
    const { comp } = await setUp(item);
    comp.step(2);

    comp.selectVariant('v2');

    expect(comp.quantity()).toBe(2);
  });

  it('writes the quantity with the language’s decimal mark', async () => {
    const { comp, fixture } = await setUp(withPhysical(SPLITTABLE));
    comp.step(-1);
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('.qty__value')?.textContent?.trim(),
    ).toBe('0,5');
  });

  it('shows the selected portion’s weight and nutrition', async () => {
    const { fixture } = await setUp(
      withPhysical({
        catchweight: false,
        splittable: false,
        netWeightGrams: 350,
        nutrition: { caloriesKcalPer100: 215 },
      }),
    );
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="physical-measure"]')?.textContent?.trim()).toBe(
      `350${NBSP}g`,
    );
    expect(host.querySelector('[data-testid="physical-nutrition"]')).not.toBeNull();
  });

  it('says a weighed portion is priced per quantum and its final weight is set at handover', async () => {
    const { fixture } = await setUp(withPhysical(CAKE, 15_000));
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="physical-price-per-quantum"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="physical-final-weight-notice"]')).not.toBeNull();
  });

  it('says nothing physical about a fixed unit', async () => {
    const { fixture } = await setUp();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="physical-facts"]'),
    ).toBeNull();
  });
});
