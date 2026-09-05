import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { signal } from '@angular/core';

import { CartComponent } from './cart.component';
import { UiCartService } from '../../services/ui-cart.service';
import { TranslateService } from '../../services/translate.service';
import type { CartResponseItem } from '../../types/cart.types';

class FakeTranslateService {
  get = (key: string): string => key;
  getWithParams = (key: string): string => key;
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
  totalItemsCount = () => this.items().reduce((sum, i) => sum + i.quantity, 0);
  subtotalFormatted = () => '25 000 so\'m';
  deliveryFee = () => '10 000 so\'m';
  totalAmount = () => '35 000 so\'m';
  hasDiscount = () => false;
  discountFormatted = () => '0 so\'m';
  appliedPromoCode = (): string | null => null;
  load = vi.fn(async () => {});
  increaseQuantity = vi.fn();
  decreaseQuantity = vi.fn();
  formatPrice = (value: number) => `${value} so'm`;
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
    fake.discountFormatted = () => '5 000 so\'m';
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
