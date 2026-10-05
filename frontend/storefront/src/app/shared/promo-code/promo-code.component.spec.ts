import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { TranslateService } from '../../services/translate.service';
import { UiCartService } from '../../services/ui-cart.service';
import { PromoCodeComponent } from './promo-code.component';

class FakeTranslateService {
  get(key: string): string {
    return key;
  }
  getWithParams(key: string, params?: Record<string, string | number>): string {
    return params ? `${key}|${Object.values(params).join('|')}` : key;
  }
  current(): Record<string, unknown> {
    return {};
  }
}

class FakeUiCartService {
  readonly appliedPromoCode = signal<string | null>(null);
  readonly promoBusy = signal(false);
  readonly promoError = signal<string | null>(null);
  readonly promoOutcomeKey = signal<string | null>(null);
  readonly promoCodeDiscountFormatted = signal<string | null>(null);
  applyPromoCode = vi.fn().mockResolvedValue(true);
  removePromoCode = vi.fn().mockResolvedValue(undefined);
}

function render(configure?: (cart: FakeUiCartService) => void) {
  const cart = new FakeUiCartService();
  configure?.(cart);
  TestBed.configureTestingModule({
    providers: [
      { provide: UiCartService, useValue: cart },
      { provide: TranslateService, useValue: new FakeTranslateService() },
    ],
  });
  const fixture = TestBed.createComponent(PromoCodeComponent);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const byId = <T extends HTMLElement>(id: string) =>
    host.querySelector<T>(`[data-testid="${id}"]`);
  return { fixture, host, cart, byId };
}

async function type(
  fixture: { detectChanges(): void; whenStable(): Promise<unknown> },
  input: HTMLInputElement,
  value: string,
): Promise<void> {
  input.value = value;
  input.dispatchEvent(new Event('input'));
  fixture.detectChanges();
  await fixture.whenStable();
}

describe('PromoCodeComponent (the cart’s code entry, ADR 0072)', () => {
  it('asks for a code while the cart carries none, and will not apply a blank one', () => {
    const { byId } = render();

    expect(byId('promo-input')).not.toBeNull();
    expect(byId<HTMLButtonElement>('promo-apply')!.disabled).toBe(true);
    expect(byId('promo-applied')).toBeNull();
  });

  it('applies the typed code, then clears the field once the platform accepted it', async () => {
    const { fixture, cart, byId } = render();
    const input = byId<HTMLInputElement>('promo-input')!;
    await type(fixture, input, 'SAVE10');

    byId<HTMLButtonElement>('promo-apply')!.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(cart.applyPromoCode).toHaveBeenCalledWith('SAVE10');
    expect(input.value).toBe('');
  });

  it('applies on Enter, as a code field is expected to', async () => {
    const { fixture, cart, byId } = render();
    const input = byId<HTMLInputElement>('promo-input')!;
    await type(fixture, input, 'SAVE10');

    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    await fixture.whenStable();

    expect(cart.applyPromoCode).toHaveBeenCalledWith('SAVE10');
  });

  it('keeps what was typed when the code is refused, and prints the refusal', async () => {
    const { fixture, cart, byId } = render((c) => {
      c.applyPromoCode.mockResolvedValue(false);
      c.promoError.set('errors.reason.codeExpired');
    });
    const input = byId<HTMLInputElement>('promo-input')!;
    await type(fixture, input, 'OLD');

    byId<HTMLButtonElement>('promo-apply')!.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(cart.applyPromoCode).toHaveBeenCalledTimes(1);
    expect(input.value).toBe('OLD');
    expect(byId('promo-error')!.textContent).toContain('errors.reason.codeExpired');
    expect(byId('promo-error')!.getAttribute('role')).toBe('alert');
  });

  it('shows the code that applied with what it took off, and lets it be removed', () => {
    const { cart, byId } = render((c) => {
      c.appliedPromoCode.set('SAVE10');
      c.promoCodeDiscountFormatted.set('5 000 so’m');
    });

    expect(byId('promo-applied')!.textContent).toContain('cart.promoApplied|SAVE10');
    expect(byId('promo-applied')!.textContent).toContain('5 000 so’m');
    expect(byId('promo-input')).toBeNull();

    byId<HTMLButtonElement>('promo-remove')!.click();
    expect(cart.removePromoCode).toHaveBeenCalledTimes(1);
  });

  it('says the offers are already better when the code did not move the price, and does not claim it applied', () => {
    const { byId } = render((c) => {
      c.appliedPromoCode.set('SMALL5');
      c.promoOutcomeKey.set('cart.promoOffersBetter');
    });

    expect(byId('promo-outcome')!.textContent).toContain('cart.promoOffersBetter');
    expect(byId('promo-outcome')!.getAttribute('role')).toBe('status');
    expect(byId('promo-applied')!.textContent).toContain('cart.promoCodeLabel|SMALL5');
    expect(byId('promo-applied')!.textContent).not.toContain('cart.promoApplied');
    // The code stays on the cart, so it can still be taken off.
    expect(byId('promo-remove')).not.toBeNull();
  });

  it('says nothing about an outcome when the code applied', () => {
    const { byId } = render((c) => c.appliedPromoCode.set('BIG30'));

    expect(byId('promo-outcome')).toBeNull();
  });

  it('disables both buttons while the platform is answering', () => {
    const typing = render((c) => c.promoBusy.set(true));
    expect(typing.byId<HTMLButtonElement>('promo-apply')!.disabled).toBe(true);
    TestBed.resetTestingModule();

    const applied = render((c) => {
      c.appliedPromoCode.set('SAVE10');
      c.promoBusy.set(true);
    });
    expect(applied.byId<HTMLButtonElement>('promo-remove')!.disabled).toBe(true);
  });
});
