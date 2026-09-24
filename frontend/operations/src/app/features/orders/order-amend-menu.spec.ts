import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import {
  AMENDMENT_COMMAND_TYPES,
  BUILT_AMENDMENT_COMMAND_TYPES,
  BuiltAmendmentCommandType,
} from './order-amendments';
import { OrderAmendMenu } from './order-amend-menu';

function render(): { fixture: ReturnType<typeof TestBed.createComponent<OrderAmendMenu>> } {
  const fixture = TestBed.createComponent(OrderAmendMenu);
  fixture.detectChanges();
  return { fixture };
}

/** `AmendmentCommandType.built() == false` — the one command this client must never offer. See the class doc. */
const NOT_BUILT_COMMAND_TYPES = AMENDMENT_COMMAND_TYPES.filter(
  (type): type is Exclude<(typeof AMENDMENT_COMMAND_TYPES)[number], BuiltAmendmentCommandType> =>
    !(BUILT_AMENDMENT_COMMAND_TYPES as readonly string[]).includes(type),
);

describe('OrderAmendMenu', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('renders exactly the eleven built commands, never REMOVE_LINES', () => {
    const { fixture } = render();
    const host: HTMLElement = fixture.nativeElement;

    expect(host.querySelectorAll('[data-testid^="order-amend-menu-"]')).toHaveLength(
      BUILT_AMENDMENT_COMMAND_TYPES.length,
    );
    expect(NOT_BUILT_COMMAND_TYPES).toEqual(['REMOVE_LINES']);
    for (const notBuilt of NOT_BUILT_COMMAND_TYPES) {
      expect(host.querySelector(`[data-testid="order-amend-menu-${notBuilt}"]`)).toBeNull();
    }
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
