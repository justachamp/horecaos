import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { AMENDMENT_COMMAND_TYPES, BuiltAmendmentCommandType } from './order-amendments';
import { OrderAmendMenu } from './order-amend-menu';

function render(): { fixture: ReturnType<typeof TestBed.createComponent<OrderAmendMenu>> } {
  const fixture = TestBed.createComponent(OrderAmendMenu);
  fixture.detectChanges();
  return { fixture };
}

/** ADR 0039 names seven commands this client must never offer — see the class doc. */
const FINANCIAL_COMMAND_TYPES = AMENDMENT_COMMAND_TYPES.filter(
  (type) =>
    ![
      'SET_KITCHEN_NOTE',
      'SET_CALLBACK_REQUESTED',
      'SET_CASH_TENDERED',
      'SET_COURIER_NOTE',
      'SET_INTERNAL_NOTE',
    ].includes(type),
);

describe('OrderAmendMenu', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('renders exactly the five built commands, never the seven financial ones', () => {
    const { fixture } = render();
    const host: HTMLElement = fixture.nativeElement;

    expect(host.querySelectorAll('[data-testid^="order-amend-menu-"]')).toHaveLength(5);
    for (const financial of FINANCIAL_COMMAND_TYPES) {
      expect(host.querySelector(`[data-testid="order-amend-menu-${financial}"]`)).toBeNull();
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
