import { TestBed } from '@angular/core/testing';

import { TranslateService } from '../../../../services/translate.service';
import { DineInOrderBarComponent, type HiddenChargeLine } from './dine-in-order-bar.component';

class FakeTranslateService {
  get = (key: string): string => key;
  getWithParams = (key: string, params: Record<string, string | number>): string =>
    `${key}:${JSON.stringify(params)}`;
  current = (): Record<string, unknown> => ({});
}

interface Inputs {
  cartCount: number;
  total: string;
  hiddenCharges: readonly HiddenChargeLine[];
  paymentOptions: readonly string[];
  selectedPayment: string | null;
  priceRefusalKey: string | null;
  checkoutErrorKey: string | null;
  updating: boolean;
  checkingOut: boolean;
  priced: boolean;
}

function setUp(overrides: Partial<Inputs> = {}) {
  TestBed.configureTestingModule({
    providers: [{ provide: TranslateService, useClass: FakeTranslateService }],
  });
  const fixture = TestBed.createComponent(DineInOrderBarComponent);
  const inputs: Inputs = {
    cartCount: 3,
    total: '45 000 so‘m',
    hiddenCharges: [],
    paymentOptions: [],
    selectedPayment: null,
    priceRefusalKey: null,
    checkoutErrorKey: null,
    updating: false,
    checkingOut: false,
    priced: true,
    ...overrides,
  };
  for (const [name, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(name, value);
  }
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const component = fixture.componentInstance;
  const emitted = {
    payments: [] as string[],
    clears: 0,
    checkouts: 0,
  };
  component.paymentChosen.subscribe((code) => emitted.payments.push(code));
  component.clearRequested.subscribe(() => (emitted.clears += 1));
  component.checkoutRequested.subscribe(() => (emitted.checkouts += 1));
  const q = (testId: string) => host.querySelector<HTMLElement>(`[data-testid="${testId}"]`);
  const all = (testId: string) =>
    Array.from(host.querySelectorAll<HTMLElement>(`[data-testid="${testId}"]`));
  return { fixture, host, emitted, q, all };
}

describe('DineInOrderBarComponent', () => {
  it('shows the plate count and the total it was given, and nothing else of the basket', () => {
    const { q } = setUp();

    expect(q('dine-in-cart-count')?.textContent).toContain('dineIn.itemsInCart:{"count":3}');
    expect(q('dine-in-cart-total')?.textContent?.trim()).toBe('45 000 so‘m');
  });

  it('keeps the box tree it had inside the page: the host generates no box of its own', () => {
    const { host } = setUp();

    expect(getComputedStyle(host).display).toBe('contents');
    expect(host.children).toHaveLength(1);
    expect(host.querySelector('footer.order')).not.toBeNull();
  });

  describe('the call to action', () => {
    it('asks for the checkout once and says Order', () => {
      const { q, emitted } = setUp();

      expect(q('dine-in-checkout')?.textContent?.trim()).toBe('dineIn.orderRound');
      q('dine-in-checkout')?.click();

      expect(emitted.checkouts).toBe(1);
    });

    it('is disabled while the basket has no price, so a zero is never ordered', () => {
      const { q } = setUp({ priced: false });

      expect((q('dine-in-checkout') as HTMLButtonElement).disabled).toBe(true);
    });

    it('is disabled while the basket is being changed and while an order is going out', () => {
      expect((setUp({ updating: true }).q('dine-in-checkout') as HTMLButtonElement).disabled).toBe(
        true,
      );
      TestBed.resetTestingModule();
      const sending = setUp({ checkingOut: true });
      expect((sending.q('dine-in-checkout') as HTMLButtonElement).disabled).toBe(true);
      expect(sending.q('dine-in-checkout')?.textContent?.trim()).toBe('common.saving');
    });
  });

  describe('payment methods', () => {
    it('offers none when there is only one to choose from', () => {
      const { q } = setUp({ paymentOptions: ['CASH'] });

      expect(q('dine-in-payment-options')).toBeNull();
    });

    it('labels the codes it knows and shows any other by its own code, marking the chosen one', () => {
      const { all } = setUp({
        paymentOptions: ['CASH', 'CLICK', 'MYBANK'],
        selectedPayment: 'CLICK',
      });

      const options = all('dine-in-payment-option');
      expect(options.map((option) => option.textContent?.trim())).toEqual([
        'cart.cash',
        'cart.click',
        'MYBANK',
      ]);
      expect(options.map((option) => option.getAttribute('aria-pressed'))).toEqual([
        'false',
        'true',
        'false',
      ]);
      expect(options[1].classList.contains('is-active')).toBe(true);
    });

    it('says which method the guest touched', () => {
      const { all, emitted } = setUp({
        paymentOptions: ['CASH', 'PAYME'],
        selectedPayment: 'CASH',
      });

      all('dine-in-payment-option')[1].click();

      expect(emitted.payments).toEqual(['PAYME']);
    });
  });

  describe('charges the server added by itself (ADR 0136)', () => {
    it('itemises them under their heading, each with the amount already inside the total', () => {
      const { q, all } = setUp({
        hiddenCharges: [{ optionId: 'o-box', label: 'Delivery box', amount: '4 000 so‘m' }],
      });

      expect(q('dine-in-hidden-charges')).not.toBeNull();
      expect(
        all('dine-in-hidden-charge').map((row) =>
          Array.from(row.querySelectorAll('span')).map((cell) => cell.textContent?.trim()),
        ),
      ).toEqual([['Delivery box', '4 000 so‘m']]);
    });

    it('draws no heading when there are none', () => {
      const { q } = setUp();

      expect(q('dine-in-hidden-charges')).toBeNull();
    });
  });

  describe('when the basket cannot be priced', () => {
    it('says why and offers to clear the order, which it only asks for', () => {
      const { q, emitted } = setUp({ priceRefusalKey: 'errors.generic', priced: false });

      expect(q('dine-in-pricing-error')?.textContent).toContain('errors.generic');
      q('dine-in-clear')?.click();

      expect(emitted.clears).toBe(1);
    });

    it('holds the clear button while the basket is changing or an order is going out', () => {
      const changing = setUp({ priceRefusalKey: 'errors.generic', updating: true });
      expect((changing.q('dine-in-clear') as HTMLButtonElement).disabled).toBe(true);
      TestBed.resetTestingModule();
      const sending = setUp({ priceRefusalKey: 'errors.generic', checkingOut: true });
      expect((sending.q('dine-in-clear') as HTMLButtonElement).disabled).toBe(true);
    });

    it('offers nothing to clear when pricing worked', () => {
      const { q } = setUp();

      expect(q('dine-in-pricing-error')).toBeNull();
      expect(q('dine-in-clear')).toBeNull();
    });
  });

  it('says why the last checkout was refused, in an alert, and clears the message when told to', () => {
    const view = setUp({ checkoutErrorKey: 'dineIn.priceRefreshed' });

    const message = view.q('dine-in-checkout-error');
    expect(message?.textContent).toContain('dineIn.priceRefreshed');
    expect(message?.getAttribute('role')).toBe('alert');

    view.fixture.componentRef.setInput('checkoutErrorKey', null);
    view.fixture.detectChanges();

    expect(view.q('dine-in-checkout-error')).toBeNull();
  });
});
