import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { NumberStepper } from '../../../shared/ui/number-stepper';
import { BasketLineView, NewOrderBasket } from './new-order-basket';

const line = (overrides: Partial<BasketLineView> = {}): BasketLineView => ({
  lineKey: 'k1',
  variantId: 'v1',
  productName: 'Plov',
  quantity: 2,
  unitAmountMinor: 45_000,
  modifiers: [],
  commentPresetCodes: [],
  customerNote: null,
  orderable: true,
  onSaleNow: true,
  modifierText: '',
  presetText: '',
  ...overrides,
});

function render(lines: readonly BasketLineView[]) {
  TestBed.resetTestingModule();
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(NewOrderBasket);
  fixture.componentRef.setInput('lines', lines);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const items = () => [...host.querySelectorAll<HTMLElement>('.new-order__basket-line')];
  return { fixture, host, items };
}

describe('NewOrderBasket', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('says the basket is empty when there are no lines', () => {
    const { host } = render([]);

    expect(host.textContent?.trim()).toBe('No items yet');
    expect(host.querySelector('[data-testid="new-order-basket"]')).toBeNull();
  });

  it('draws a line per item with its name', () => {
    const { items } = render([line(), line({ lineKey: 'k2', productName: 'Samsa' })]);

    expect(items().map((item) => item.querySelector('.q-body-sm')?.textContent?.trim())).toEqual([
      'Plov',
      'Samsa',
    ]);
  });

  it('shows the modifier and preset summaries only when the line has them', () => {
    const plain = render([line()]);
    expect(plain.host.querySelector('.new-order__modifiers')).toBeNull();

    const worded = render([
      line({
        modifiers: [{ optionId: 'o', code: 'LARGE', quantity: 1, amountMinor: 0 }],
        commentPresetCodes: ['NO_ONIONS'],
        modifierText: 'LARGE',
        presetText: 'No onions',
      }),
    ]);
    expect(
      [...worded.host.querySelectorAll('.new-order__modifiers')].map((p) => p.textContent?.trim()),
    ).toEqual(['LARGE', 'No onions']);
    expect(worded.host.querySelector('[data-testid="new-order-line-presets"]')).not.toBeNull();
  });

  it('flags a line that is no longer available, and one outside its sale window', () => {
    const gone = render([line({ orderable: false })]);
    expect(gone.items()[0].classList.contains('new-order__basket-line--unavailable')).toBe(true);
    expect(gone.items()[0].textContent).toContain('No longer available');

    const closed = render([line({ onSaleNow: false })]);
    expect(closed.items()[0].classList.contains('new-order__basket-line--unavailable')).toBe(true);
    expect(
      closed.host.querySelector('[data-testid="new-order-line-out-of-window"]'),
    ).not.toBeNull();

    const fine = render([line()]);
    expect(fine.items()[0].classList.contains('new-order__basket-line--unavailable')).toBe(false);
  });

  it('shows the kitchen note the line carries', () => {
    const { host } = render([line({ customerNote: 'No salt' })]);

    expect(host.querySelector<HTMLInputElement>('.new-order__note')?.value).toBe('No salt');
  });

  it('reports a quantity change, a note typed and a removal, each with the line’s key', () => {
    const { fixture, host } = render([line({ lineKey: 'k7' })]);
    const seen: string[] = [];
    const instance = fixture.componentInstance;
    instance.quantityChanged.subscribe(({ lineKey, quantity }) =>
      seen.push(`qty:${lineKey}:${quantity}`),
    );
    instance.noteChanged.subscribe(({ lineKey, note }) => seen.push(`note:${lineKey}:${note}`));
    instance.removeRequested.subscribe((lineKey) => seen.push(`remove:${lineKey}`));

    fixture.debugElement.query(By.directive(NumberStepper)).componentInstance.valueChange.emit(5);
    const note = host.querySelector<HTMLInputElement>('.new-order__note')!;
    note.value = 'Extra sauce';
    note.dispatchEvent(new Event('input'));
    host.querySelector<HTMLButtonElement>('[data-testid="new-order-remove-line"]')?.click();

    expect(seen).toEqual(['qty:k7:5', 'note:k7:Extra sauce', 'remove:k7']);
  });
});
