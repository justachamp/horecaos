import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { OrderAddressReveal } from './order-detail';
import { OrderChangeAddressDialog } from './order-change-address-dialog';

function reveal(overrides: Partial<OrderAddressReveal> = {}): OrderAddressReveal {
  return {
    line1: 'Чиланзар, 12',
    line2: null,
    city: 'Ташкент',
    district: 'Чиланзарский',
    postalCode: null,
    entrance: '2',
    floor: '5',
    apartment: '48',
    landmark: null,
    latitude: 41.2995,
    longitude: 69.2401,
    deliveryInstructions: 'Домофон не работает',
    ...overrides,
  };
}

function render(
  options: {
    initial?: OrderAddressReveal | null;
    initialRecipientName?: string;
    initialRecipientPhone?: string;
  } = {},
): { fixture: ReturnType<typeof TestBed.createComponent<OrderChangeAddressDialog>> } {
  const fixture = TestBed.createComponent(OrderChangeAddressDialog);
  fixture.componentRef.setInput('initial', options.initial ?? null);
  fixture.componentRef.setInput('initialRecipientName', options.initialRecipientName ?? '');
  fixture.componentRef.setInput('initialRecipientPhone', options.initialRecipientPhone ?? '');
  fixture.detectChanges();
  return { fixture };
}

describe('OrderChangeAddressDialog', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('seeds every field from the revealed current address', () => {
    const { fixture } = render({
      initial: reveal(),
      initialRecipientName: 'Азиз Каримов',
      initialRecipientPhone: '+998901234567',
    });
    const host: HTMLElement = fixture.nativeElement;

    expect(
      (host.querySelector('[data-testid="order-change-address-dialog-line1"]') as HTMLInputElement)
        .value,
    ).toBe('Чиланзар, 12');
    expect(
      (host.querySelector('[data-testid="order-change-address-dialog-city"]') as HTMLInputElement)
        .value,
    ).toBe('Ташкент');
    expect(
      (
        host.querySelector(
          '[data-testid="order-change-address-dialog-latitude"]',
        ) as HTMLInputElement
      ).value,
    ).toBe('41.2995');
    expect(
      (
        host.querySelector(
          '[data-testid="order-change-address-dialog-recipient-name"]',
        ) as HTMLInputElement
      ).value,
    ).toBe('Азиз Каримов');
  });

  it('starts blank with a 0/0 coordinate when the order has no address revealed yet', () => {
    const { fixture } = render({ initial: null });
    const host: HTMLElement = fixture.nativeElement;

    expect(
      (host.querySelector('[data-testid="order-change-address-dialog-line1"]') as HTMLInputElement)
        .value,
    ).toBe('');
    expect(
      (
        host.querySelector(
          '[data-testid="order-change-address-dialog-latitude"]',
        ) as HTMLInputElement
      ).value,
    ).toBe('0');
  });

  it('disables confirm until line1, city, name and phone are all filled', () => {
    const { fixture } = render({ initial: null });
    const host: HTMLElement = fixture.nativeElement;
    const confirmButton = host.querySelector(
      '[data-testid="order-change-address-dialog-confirm"]',
    ) as HTMLButtonElement;
    expect(confirmButton.disabled).toBe(true);

    const set = (testid: string, value: string): void => {
      const el = host.querySelector(`[data-testid="${testid}"]`) as HTMLInputElement;
      el.value = value;
      el.dispatchEvent(new Event('input'));
    };
    set('order-change-address-dialog-line1', 'Чиланзар, 12');
    set('order-change-address-dialog-city', 'Ташкент');
    set('order-change-address-dialog-recipient-name', 'Азиз Каримов');
    set('order-change-address-dialog-recipient-phone', '+998901234567');
    fixture.detectChanges();

    expect(confirmButton.disabled).toBe(false);
  });

  it('emits confirm with the full structured address, coordinates as numbers', () => {
    const { fixture } = render({
      initial: reveal(),
      initialRecipientName: 'Азиз Каримов',
      initialRecipientPhone: '+998901234567',
    });
    const host: HTMLElement = fixture.nativeElement;
    let emitted: unknown = null;
    fixture.componentInstance.confirm.subscribe((submission) => (emitted = submission));

    (
      host.querySelector('[data-testid="order-change-address-dialog-confirm"]') as HTMLButtonElement
    ).click();

    expect(emitted).toEqual({
      line1: 'Чиланзар, 12',
      line2: '',
      city: 'Ташкент',
      district: 'Чиланзарский',
      postalCode: '',
      entrance: '2',
      floor: '5',
      apartment: '48',
      landmark: '',
      latitude: 41.2995,
      longitude: 69.2401,
      deliveryInstructions: 'Домофон не работает',
      recipientName: 'Азиз Каримов',
      recipientPhone: '+998901234567',
    });
  });

  it('emits dismiss', () => {
    const { fixture } = render();
    const host: HTMLElement = fixture.nativeElement;
    let dismissed = false;
    fixture.componentInstance.dismiss.subscribe(() => (dismissed = true));

    const dismissButton = [...host.querySelectorAll('button')].find(
      (b) => b.textContent?.trim() === 'Dismiss',
    ) as HTMLButtonElement;
    dismissButton.click();

    expect(dismissed).toBe(true);
  });
});
