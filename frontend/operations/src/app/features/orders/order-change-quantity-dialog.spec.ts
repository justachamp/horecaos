import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { OrderLine } from './order-detail';
import { OrderChangeQuantityDialog, QuantitySubmission } from './order-change-quantity-dialog';

function line(overrides: Partial<OrderLine> = {}): OrderLine {
  return {
    lineNumber: 1,
    productName: 'Лагман',
    quantity: 2,
    finalAmountMinor: 40_000,
    modifiers: [],
    commentPresets: [],
    lineId: 'line-1',
    hasNote: false,
    ...overrides,
  };
}

function render(lines: readonly OrderLine[]): {
  fixture: ReturnType<typeof TestBed.createComponent<OrderChangeQuantityDialog>>;
} {
  const fixture = TestBed.createComponent(OrderChangeQuantityDialog);
  fixture.componentRef.setInput('lines', lines);
  fixture.detectChanges();
  return { fixture };
}

describe('OrderChangeQuantityDialog', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('preselects the first line and defaults the quantity to one above its current one', () => {
    const { fixture } = render([line({ lineId: 'line-1', quantity: 2 })]);
    const host: HTMLElement = fixture.nativeElement;

    expect(
      (
        host.querySelector(
          '[data-testid="order-change-quantity-dialog-quantity"]',
        ) as HTMLInputElement
      ).value,
    ).toBe('3');
  });

  it('re-seeds the minimum and default quantity when the operator picks a different line', () => {
    const { fixture } = render([
      line({ lineId: 'line-1', quantity: 2 }),
      line({ lineId: 'line-2', productName: 'Плов', quantity: 5 }),
    ]);
    const host: HTMLElement = fixture.nativeElement;

    const select = host.querySelector(
      '[data-testid="order-change-quantity-dialog-line"]',
    ) as HTMLSelectElement;
    select.value = 'line-2';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(
      (
        host.querySelector(
          '[data-testid="order-change-quantity-dialog-quantity"]',
        ) as HTMLInputElement
      ).value,
    ).toBe('6');
  });

  it('disables confirm for a quantity at or below the current one', () => {
    const { fixture } = render([line({ lineId: 'line-1', quantity: 2 })]);
    const host: HTMLElement = fixture.nativeElement;
    const quantityInput = host.querySelector(
      '[data-testid="order-change-quantity-dialog-quantity"]',
    ) as HTMLInputElement;
    const confirmButton = host.querySelector(
      '[data-testid="order-change-quantity-dialog-confirm"]',
    ) as HTMLButtonElement;

    quantityInput.value = '2';
    quantityInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(confirmButton.disabled).toBe(true);

    quantityInput.value = '4';
    quantityInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(confirmButton.disabled).toBe(false);
  });

  it('emits confirm with the selected line and quantity', () => {
    const { fixture } = render([line({ lineId: 'line-1', quantity: 2 })]);
    const host: HTMLElement = fixture.nativeElement;
    let emitted: { orderLineId: string; quantity: number } | null = null;
    fixture.componentInstance.confirm.subscribe((submission) => (emitted = submission));

    const quantityInput = host.querySelector(
      '[data-testid="order-change-quantity-dialog-quantity"]',
    ) as HTMLInputElement;
    quantityInput.value = '5';
    quantityInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (
      host.querySelector(
        '[data-testid="order-change-quantity-dialog-confirm"]',
      ) as HTMLButtonElement
    ).click();

    expect(emitted).toEqual({ orderLineId: 'line-1', quantity: 5 });
  });

  it('emits dismiss', () => {
    const { fixture } = render([line()]);
    const host: HTMLElement = fixture.nativeElement;
    let dismissed = false;
    fixture.componentInstance.dismiss.subscribe(() => (dismissed = true));

    const dismissButton = [...host.querySelectorAll('button')].find(
      (b) => b.textContent?.trim() === 'Dismiss',
    ) as HTMLButtonElement;
    dismissButton.click();

    expect(dismissed).toBe(true);
  });

  // ------------------------------------------------------------ ADR 0136

  const combo = {
    selectionId: 'sel-1',
    containerVariantId: 'cv-1',
    name: 'Lunch box',
    quantity: 2,
  };

  it('offers a combo once, counted in combos, however many components it has', () => {
    const { fixture } = render([
      line({ lineId: 'a', productName: 'Burger', quantity: 2, combo }),
      line({ lineId: 'b', productName: 'Cola', quantity: 2, combo }),
      line({ lineId: 'c', productName: 'Soup', quantity: 1 }),
    ]);
    const host: HTMLElement = fixture.nativeElement;

    const options = [
      ...host.querySelectorAll('[data-testid="order-change-quantity-dialog-line"] option'),
    ].map((option) => option.textContent?.replace(/\s+/g, ' ').trim());
    expect(options).toEqual(['Lunch box (combos: 2)', 'Soup (Qty: 1)']);
    expect(
      (
        host.querySelector(
          '[data-testid="order-change-quantity-dialog-quantity"]',
        ) as HTMLInputElement
      ).value,
    ).toBe('3');
  });

  it('sends a whole number of combos as the units that many combos put on the component line', () => {
    const { fixture } = render([
      // Two units of this component per combo: a quantity of 4 on the line is two combos.
      line({ lineId: 'a', productName: 'Wings', quantity: 4, combo }),
      line({ lineId: 'b', productName: 'Cola', quantity: 2, combo }),
    ]);
    const host: HTMLElement = fixture.nativeElement;
    const submissions: QuantitySubmission[] = [];
    fixture.componentInstance.confirm.subscribe((value) => submissions.push(value));

    (
      host.querySelector(
        '[data-testid="order-change-quantity-dialog-confirm"]',
      ) as HTMLButtonElement
    ).click();

    expect(submissions).toEqual([{ orderLineId: 'a', quantity: 6 }]);
  });

  it('sends an ordinary line’s quantity as it always did', () => {
    const { fixture } = render([line({ lineId: 'c', productName: 'Soup', quantity: 1 })]);
    const host: HTMLElement = fixture.nativeElement;
    const submissions: QuantitySubmission[] = [];
    fixture.componentInstance.confirm.subscribe((value) => submissions.push(value));

    (
      host.querySelector(
        '[data-testid="order-change-quantity-dialog-confirm"]',
      ) as HTMLButtonElement
    ).click();

    expect(submissions).toEqual([{ orderLineId: 'c', quantity: 2 }]);
  });

  describe('a line sold by the portion (ADR 0137)', () => {
    it('offers the next whole number above a fractional quantity, since an amendment changes whole units', () => {
      const { fixture } = render([line({ lineId: 'line-1', quantity: 0.5 })]);
      const host: HTMLElement = fixture.nativeElement;
      const input = host.querySelector(
        '[data-testid="order-change-quantity-dialog-quantity"]',
      ) as HTMLInputElement;

      expect(input.value).toBe('1');
      expect(Number(input.min)).toBe(1);
      expect(
        (
          host.querySelector(
            '[data-testid="order-change-quantity-dialog-confirm"]',
          ) as HTMLButtonElement
        ).disabled,
      ).toBe(false);
    });

    it('goes above 1.5 to 2, never to 1.5 + 1', () => {
      const { fixture } = render([line({ lineId: 'line-1', quantity: 1.5 })]);
      const host: HTMLElement = fixture.nativeElement;

      expect(
        (
          host.querySelector(
            '[data-testid="order-change-quantity-dialog-quantity"]',
          ) as HTMLInputElement
        ).value,
      ).toBe('2');
    });

    it('writes a line’s current quantity without float noise or trailing zeros', () => {
      const { fixture } = render([line({ lineId: 'line-1', quantity: 0.5 })]);
      const host: HTMLElement = fixture.nativeElement;

      expect(host.querySelector('option')?.textContent).toContain('0.5');
    });

    describe('with a published portion size', () => {
      const quantityInput = (host: HTMLElement): HTMLInputElement =>
        host.querySelector(
          '[data-testid="order-change-quantity-dialog-quantity"]',
        ) as HTMLInputElement;
      const confirmButton = (host: HTMLElement): HTMLButtonElement =>
        host.querySelector(
          '[data-testid="order-change-quantity-dialog-confirm"]',
        ) as HTMLButtonElement;
      const type = (fixture: ReturnType<typeof render>['fixture'], value: string): void => {
        const input = quantityInput(fixture.nativeElement);
        input.value = value;
        input.dispatchEvent(new Event('input'));
        fixture.detectChanges();
      };

      it('starts a splittable line one portion above its quantity and steps by the portion', () => {
        const { fixture } = render([line({ lineId: 'plov', quantity: 1, portionSize: 0.5 })]);
        const host: HTMLElement = fixture.nativeElement;

        expect(quantityInput(host).value).toBe('1.5');
        expect(Number(quantityInput(host).min)).toBe(1.5);
        expect(Number(quantityInput(host).step)).toBe(0.5);
        expect(
          host.querySelector('[data-testid="order-change-quantity-dialog-step"]')?.textContent,
        ).toContain('0.5');
      });

      it('sends a decimal quantity for a splittable line, as a number and not as text', () => {
        const { fixture } = render([line({ lineId: 'plov', quantity: 1, portionSize: 0.5 })]);
        const submissions: QuantitySubmission[] = [];
        fixture.componentInstance.confirm.subscribe((value) => submissions.push(value));

        type(fixture, '2.5');
        confirmButton(fixture.nativeElement).click();

        expect(submissions).toEqual([{ orderLineId: 'plov', quantity: 2.5 }]);
      });

      it('refuses a quantity that is not a whole number of portions, and says what to type', () => {
        const { fixture } = render([line({ lineId: 'plov', quantity: 1, portionSize: 0.5 })]);
        const host: HTMLElement = fixture.nativeElement;

        type(fixture, '1.7');

        expect(confirmButton(host).disabled).toBe(true);
        const hint = host.querySelector('[data-testid="order-change-quantity-dialog-step"]');
        expect(hint?.getAttribute('role')).toBe('alert');
        expect(hint?.textContent).toContain('0.5');

        type(fixture, '2');
        expect(confirmButton(host).disabled).toBe(false);
      });

      it('does not take a decimal for a line the menu sells in whole units', () => {
        const { fixture } = render([line({ lineId: 'soda', quantity: 2 })]);
        const host: HTMLElement = fixture.nativeElement;
        const submissions: QuantitySubmission[] = [];
        fixture.componentInstance.confirm.subscribe((value) => submissions.push(value));

        type(fixture, '3.5');
        confirmButton(host).click();

        expect(host.querySelector('[data-testid="order-change-quantity-dialog-step"]')).toBeNull();
        expect(submissions).toEqual([{ orderLineId: 'soda', quantity: 3 }]);
      });

      it('moves a line that holds an in-between quantity to the next portion', () => {
        const { fixture } = render([line({ lineId: 'plov', quantity: 1.3, portionSize: 0.5 })]);

        expect(quantityInput(fixture.nativeElement).value).toBe('1.5');
      });

      it('follows the line the operator picks: portion step for the plov, whole units for the soda', () => {
        const { fixture } = render([
          line({ lineId: 'plov', productName: 'Plov', quantity: 1, portionSize: 0.5 }),
          line({ lineId: 'soda', productName: 'Soda', quantity: 2 }),
        ]);
        const host: HTMLElement = fixture.nativeElement;
        const select = host.querySelector(
          '[data-testid="order-change-quantity-dialog-line"]',
        ) as HTMLSelectElement;

        select.value = 'soda';
        select.dispatchEvent(new Event('change'));
        fixture.detectChanges();

        expect(quantityInput(host).value).toBe('3');
        expect(host.querySelector('[data-testid="order-change-quantity-dialog-step"]')).toBeNull();
      });
    });
  });
});
