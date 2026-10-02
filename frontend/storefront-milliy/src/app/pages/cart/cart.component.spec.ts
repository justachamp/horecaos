import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { signal } from '@angular/core';

import { CartComponent } from './cart.component';
import { UiCartService } from '../../services/ui-cart.service';
import { TranslateService } from '../../services/translate.service';
import type { CartResponseItem } from '../../types/cart.types';

class FakeTranslateService {
  get = (key: string): string => key;
  getWithParams = (key: string, params?: Record<string, string | number>): string =>
    params ? `${key}|${Object.values(params).join('|')}` : key;
  current = (): Record<string, unknown> => ({});
}

function line(overrides: Partial<CartResponseItem> = {}): CartResponseItem {
  return {
    variant_id: 'v1',
    item_id: 'v1',
    name: 'Osh',
    active: true,
    image: null,
    price: 25_000,
    quantity: 2,
    note: null,
    modifierOptionIds: [],
    modifiers: [],
    ...overrides,
  };
}

class FakeUiCartService {
  readonly items = signal<CartResponseItem[]>([]);
  readonly error = signal<string | null>(null);
  readonly errorKey = signal<string | null>(null);
  readonly priceRefusalKey = signal<string | null>(null);
  deliveryUnresolvedMessage = (): string | null => null;
  totalItemsCount = () => this.items().reduce((sum, i) => sum + i.quantity, 0);
  subtotalFormatted = () => "25 000 so'm";
  deliveryFee = () => "10 000 so'm";
  totalAmount = () => "35 000 so'm";
  hasDiscount = () => false;
  discountFormatted = () => "0 so'm";
  appliedPromoCode = (): string | null => null;
  load = vi.fn(async () => {});
  increaseQuantity = vi.fn();
  decreaseQuantity = vi.fn();
  formatPrice = (value: number) => `${value} so'm`;
  hasProvisionalLines = () => false;
  lineAmount = vi.fn((item: CartResponseItem) => item.price * item.quantity);
}

async function setUp(fake = new FakeUiCartService()) {
  TestBed.configureTestingModule({
    imports: [CartComponent],
    providers: [
      provideRouter([]),
      { provide: UiCartService, useValue: fake },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });
  const fixture = TestBed.createComponent(CartComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return { fixture, fake, router: TestBed.inject(Router) };
}

describe('CartComponent', () => {
  it('shows the empty state and a way back to the menu when the basket has nothing in it', async () => {
    const { fixture, fake, router } = await setUp();
    const navigateSpy = vi.spyOn(router, 'navigate');

    expect(fixture.nativeElement.textContent).toContain('cart.emptyTitle');
    const goToMenu = fixture.nativeElement.querySelector('.btn') as HTMLButtonElement;
    expect(goToMenu.textContent).toContain('cart.goToMenu');

    goToMenu.click();
    expect(navigateSpy).toHaveBeenCalledWith(['/home']);
    expect(fake.load).toHaveBeenCalled();
  });

  it('surfaces a load failure as a translated message, never a raw state', async () => {
    const fake = new FakeUiCartService();
    fake.error.set('errors.generic');
    const { fixture } = await setUp(fake);

    expect(fixture.nativeElement.textContent).toContain('cart.loadError');
    expect(fixture.nativeElement.textContent).not.toContain('errors.generic');
  });

  it('renders each line with its exact quantity and total, and the summary breakdown', async () => {
    const fake = new FakeUiCartService();
    fake.items.set([line({ item_id: 'a', name: 'Osh', price: 25_000, quantity: 2 })]);
    const { fixture } = await setUp(fake);

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Osh');
    expect(text).toContain('2');
    expect(text).toContain('cart.subtotalLabel');
    expect(text).toContain('cart.total');
  });

  it('increase and decrease send exactly the line the customer touched, never a different one', async () => {
    const fake = new FakeUiCartService();
    const a = line({ item_id: 'a', name: 'Osh' });
    const b = line({ item_id: 'b', name: 'Norin' });
    fake.items.set([a, b]);
    const { fixture } = await setUp(fake);

    const rows = fixture.nativeElement.querySelectorAll('.line');
    const secondRow = rows[1] as HTMLElement;
    (secondRow.querySelector('.qty__btn--add') as HTMLButtonElement).click();

    expect(fake.increaseQuantity).toHaveBeenCalledWith(b);
    expect(fake.increaseQuantity).not.toHaveBeenCalledWith(a);
  });

  it('shows the applied promo discount only when the platform actually priced one', async () => {
    const fake = new FakeUiCartService();
    fake.items.set([line()]);
    fake.hasDiscount = () => true;
    fake.appliedPromoCode = () => 'OSH2026';
    fake.discountFormatted = () => "5 000 so'm";
    const { fixture } = await setUp(fake);

    expect(fixture.nativeElement.textContent).toContain('OSH2026');
    expect(fixture.nativeElement.textContent).toContain('5 000');
  });

  it('does not offer a checkout button over an empty basket', async () => {
    const { fixture } = await setUp();

    expect(fixture.nativeElement.querySelector('.cta')).toBeNull();
  });

  it('the checkout button navigates to /checkout', async () => {
    const fake = new FakeUiCartService();
    fake.items.set([line()]);
    const { fixture, router } = await setUp(fake);
    const navigateSpy = vi.spyOn(router, 'navigate');

    (fixture.nativeElement.querySelector('.cta') as HTMLButtonElement).click();

    expect(navigateSpy).toHaveBeenCalledWith(['/checkout']);
  });
});

describe('CartComponent -- failures and unavailable lines say why', () => {
  it('a load failure with a specific reason names it under the headline', async () => {
    const fake = new FakeUiCartService();
    fake.error.set('errors.offline');
    fake.errorKey.set('errors.offline');
    const { fixture } = await setUp(fake);

    expect(fixture.nativeElement.textContent).toContain('cart.loadError');
    expect(
      fixture.nativeElement.querySelector('[data-testid="cart-error-detail"]')?.textContent,
    ).toContain('errors.offline');
  });

  it('a load failure with only the generic sentence adds no second, redundant line', async () => {
    const fake = new FakeUiCartService();
    fake.error.set('errors.generic');
    fake.errorKey.set('errors.generic');
    const { fixture } = await setUp(fake);

    expect(fixture.nativeElement.querySelector('[data-testid="cart-error-detail"]')).toBeNull();
  });

  it('a refused quantity change shows its sentence over the basket instead of failing silently', async () => {
    const fake = new FakeUiCartService();
    fake.items.set([line()]);
    const { fixture } = await setUp(fake);
    expect(fixture.nativeElement.querySelector('[data-testid="cart-error"]')).toBeNull();

    fake.errorKey.set('errors.reason.itemUnavailable');
    fixture.detectChanges();

    const alert = fixture.nativeElement.querySelector('[data-testid="cart-error"]') as HTMLElement;
    expect(alert.textContent).toContain('errors.reason.itemUnavailable');
    expect(alert.getAttribute('role')).toBe('alert');
  });

  it('explains an unresolved delivery fee beside the delivery line', async () => {
    const fake = new FakeUiCartService();
    fake.items.set([line()]);
    fake.deliveryUnresolvedMessage = () => 'errors.reason.outOfZone';
    const { fixture } = await setUp(fake);

    expect(
      fixture.nativeElement.querySelector('[data-testid="delivery-unresolved"]')?.textContent,
    ).toContain('errors.reason.outOfZone');
  });

  it('marks a line that has gone out of its sale window, and says that rather than "sold out"', async () => {
    const fake = new FakeUiCartService();
    fake.items.set([
      line({ item_id: 'a', active: false, unavailableReason: 'OUT_OF_SALE_WINDOW' }),
    ]);
    const { fixture } = await setUp(fake);

    const note = fixture.nativeElement.querySelector('[data-testid="cart-line-unavailable"]');
    expect(note?.textContent).toContain('errors.reason.itemOutOfSaleWindow');
    expect(fixture.nativeElement.querySelector('.line')?.classList).toContain('is-unavailable');
  });

  it('marks a line that has sold out', async () => {
    const fake = new FakeUiCartService();
    fake.items.set([line({ item_id: 'a', active: false, unavailableReason: 'SOLD_OUT' })]);
    const { fixture } = await setUp(fake);

    const note = fixture.nativeElement.querySelector('[data-testid="cart-line-unavailable"]');
    expect(note?.textContent).toContain('errors.reason.itemUnavailable');
  });

  it('leaves an ordinary line unmarked', async () => {
    const fake = new FakeUiCartService();
    fake.items.set([line()]);
    const { fixture } = await setUp(fake);

    expect(fixture.nativeElement.querySelector('[data-testid="cart-line-unavailable"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('.line')?.classList).not.toContain('is-unavailable');
  });
});

describe('CartComponent -- a basket the platform would not price', () => {
  it('says why the total is a dash, over the basket, without turning the page into a load error', async () => {
    const fake = new FakeUiCartService();
    fake.items.set([line({ active: false, unavailableReason: 'OUT_OF_SALE_WINDOW' })]);
    fake.priceRefusalKey.set('errors.reason.itemOutOfSaleWindow');
    const { fixture } = await setUp(fake);

    const note = fixture.nativeElement.querySelector(
      '[data-testid="cart-pricing-error"]',
    ) as HTMLElement;
    expect(note.textContent).toContain('errors.reason.itemOutOfSaleWindow');
    expect(note.getAttribute('role')).toBe('alert');
    expect(fixture.nativeElement.textContent).not.toContain('cart.loadError');
    expect(fixture.nativeElement.querySelector('.line')).not.toBeNull();
  });

  it('shows no pricing note for a basket that priced', async () => {
    const fake = new FakeUiCartService();
    fake.items.set([line()]);
    const { fixture } = await setUp(fake);

    expect(fixture.nativeElement.querySelector('[data-testid="cart-pricing-error"]')).toBeNull();
  });

  it('does not offer checkout while a line cannot be bought, and offers it again once that line is gone', async () => {
    const fake = new FakeUiCartService();
    fake.items.set([
      line({ item_id: 'a' }),
      line({ item_id: 'b', active: false, unavailableReason: 'OUT_OF_SALE_WINDOW' }),
    ]);
    const { fixture, router } = await setUp(fake);
    const navigateSpy = vi.spyOn(router, 'navigate');
    const cta = fixture.nativeElement.querySelector('.cta') as HTMLButtonElement;

    expect(cta.disabled).toBe(true);
    cta.click();
    expect(navigateSpy).not.toHaveBeenCalled();

    fake.items.set([line({ item_id: 'a' })]);
    fixture.detectChanges();

    expect((fixture.nativeElement.querySelector('.cta') as HTMLButtonElement).disabled).toBe(false);
  });
});

describe('CartComponent -- portions and weighed items (ADR 0137)', () => {
  const NBSP = '\u00a0';
  const SPLITTABLE = { catchweight: false, splittable: true, portionSize: 0.5 } as const;
  const CAKE = {
    catchweight: true,
    catchweightQuantumGrams: 100,
    catchweightNominalGrams: 1_200,
    splittable: false,
  } as const;

  const normalised = (text: string | null | undefined) => (text ?? '').replace(/\s+/g, ' ');

  it('writes a half portion with the language’s decimal mark', async () => {
    const fake = new FakeUiCartService();
    fake.items.set([line({ quantity: 0.5, physical: SPLITTABLE })]);
    const { fixture } = await setUp(fake);

    expect(fixture.nativeElement.querySelector('.qty__value')?.textContent?.trim()).toBe('0,5');
  });

  it('writes a whole quantity without decimals', async () => {
    const fake = new FakeUiCartService();
    fake.items.set([line({ quantity: 3 })]);
    const { fixture } = await setUp(fake);

    expect(fixture.nativeElement.querySelector('.qty__value')?.textContent?.trim()).toBe('3');
  });

  it('prices a line through the cart, so a portion is priced as the platform will price it', async () => {
    const fake = new FakeUiCartService();
    fake.lineAmount.mockReturnValue(12_501);
    fake.items.set([line({ price: 25_000, quantity: 0.5, physical: SPLITTABLE })]);
    const { fixture } = await setUp(fake);

    expect(normalised(fixture.nativeElement.querySelector('.line__price')?.textContent)).toBe(
      "12501 so'm",
    );
  });

  it('marks a weighed line’s amount as an estimate and says what weight it is estimated at', async () => {
    const fake = new FakeUiCartService();
    fake.lineAmount.mockReturnValue(360_000);
    fake.items.set([line({ price: 15_000, quantity: 2, physical: CAKE })]);
    const { fixture } = await setUp(fake);

    expect(normalised(fixture.nativeElement.querySelector('.line__price')?.textContent)).toContain(
      '≈ 360000',
    );
    expect(
      fixture.nativeElement.querySelector('[data-testid="cart-line-estimate"]')?.textContent,
    ).toContain(`2,4${NBSP}kg`);
  });

  it('says the total is an estimate while the basket holds anything sold by weight', async () => {
    const fake = new FakeUiCartService();
    fake.hasProvisionalLines = () => true;
    fake.items.set([line()]);
    const { fixture } = await setUp(fake);

    expect(
      fixture.nativeElement.querySelector('[data-testid="cart-provisional-notice"]'),
    ).not.toBeNull();
  });

  it('says nothing of the kind for a basket of fixed units', async () => {
    const fake = new FakeUiCartService();
    fake.items.set([line()]);
    const { fixture } = await setUp(fake);

    expect(
      fixture.nativeElement.querySelector('[data-testid="cart-provisional-notice"]'),
    ).toBeNull();
  });
});
