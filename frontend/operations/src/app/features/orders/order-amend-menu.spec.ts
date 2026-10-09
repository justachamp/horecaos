import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { BUILT_AMENDMENT_COMMAND_TYPES, BuiltAmendmentCommandType } from './order-amendments';
import { OrderAmendMenu } from './order-amend-menu';

function render(lineRemovalEnabled = false): {
  fixture: ReturnType<typeof TestBed.createComponent<OrderAmendMenu>>;
} {
  const fixture = TestBed.createComponent(OrderAmendMenu);
  fixture.componentRef.setInput('lineRemovalEnabled', lineRemovalEnabled);
  fixture.detectChanges();
  return { fixture };
}

describe('OrderAmendMenu', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('offers the eleven ungated commands and not REMOVE_LINES while the brand has the removal path off', () => {
    const { fixture } = render(false);
    const host: HTMLElement = fixture.nativeElement;

    expect(host.querySelectorAll('[data-testid^="order-amend-menu-"]')).toHaveLength(
      BUILT_AMENDMENT_COMMAND_TYPES.length - 1,
    );
    expect(host.querySelector('[data-testid="order-amend-menu-REMOVE_LINES"]')).toBeNull();
  });

  it('offers all twelve once the brand has switched the removal path on (ADR 0169)', () => {
    const { fixture } = render(true);
    const host: HTMLElement = fixture.nativeElement;

    expect(host.querySelectorAll('[data-testid^="order-amend-menu-"]')).toHaveLength(
      BUILT_AMENDMENT_COMMAND_TYPES.length,
    );
    expect(host.querySelector('[data-testid="order-amend-menu-REMOVE_LINES"]')).not.toBeNull();
  });

  it('renders each of the six wave-10 financial commands', () => {
    const { fixture } = render();
    const host: HTMLElement = fixture.nativeElement;

    for (const financial of [
      'ADD_LINES',
      'CHANGE_LINE_QUANTITY',
      'CHANGE_PAYMENT_METHOD',
      'CHANGE_DELIVERY_ADDRESS',
      'CHANGE_FULFILLMENT_TIME',
      'CHANGE_CONTACT',
    ] as const) {
      expect(host.querySelector(`[data-testid="order-amend-menu-${financial}"]`)).not.toBeNull();
    }
  });

  it('emits the selected command type', () => {
    const { fixture } = render();
    const host: HTMLElement = fixture.nativeElement;
    const selections: BuiltAmendmentCommandType[] = [];
    fixture.componentInstance.select.subscribe((type) => selections.push(type));

    (
      host.querySelector('[data-testid="order-amend-menu-SET_COURIER_NOTE"]') as HTMLButtonElement
    ).click();

    expect(selections).toEqual(['SET_COURIER_NOTE']);
  });

  it('emits dismiss when the operator closes the menu', () => {
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
