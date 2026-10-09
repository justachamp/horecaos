import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { ReasonResponse } from '../settings/reference-data/reference-data-api';
import { OrderLine } from './order-detail';
import { OrderRemoveLinesDialog, RemoveLinesSubmission } from './order-remove-lines-dialog';

function line(overrides: Partial<OrderLine> = {}): OrderLine {
  return {
    lineNumber: 1,
    productName: 'Burger',
    quantity: 2,
    finalAmountMinor: 40_000,
    modifiers: [],
    commentPresets: [],
    lineId: 'line-1',
    hasNote: false,
    ...overrides,
  };
}

const REASON: ReasonResponse = {
  id: 'reason-1',
  kind: 'LINE_REMOVAL',
  systemCategory: 'CUSTOMER_CHANGED_MIND',
  internalName: 'Customer changed their mind',
  stockDisposition: 'RETURN_TO_STOCK',
  liabilityParty: 'CUSTOMER',
  customerRefund: null,
  allowedFulfillmentModes: null,
  customerTexts: {},
  status: 'ACTIVE',
  version: 1,
  updatedAt: '2026-10-08T00:00:00Z',
};

function render(lines: readonly OrderLine[], reasons: readonly ReasonResponse[] = [REASON]) {
  const fixture = TestBed.createComponent(OrderRemoveLinesDialog);
  fixture.componentRef.setInput('lines', lines);
  fixture.componentRef.setInput('reasons', reasons);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return {
    fixture,
    host,
    tick: (key: string) => {
      (
        host.querySelector(
          `[data-testid="order-remove-lines-dialog-choice-${key}"]`,
        ) as HTMLInputElement
      ).click();
      fixture.detectChanges();
    },
    pickReason: (id: string) => {
      const select = host.querySelector(
        '[data-testid="removal-reason-select"]',
      ) as HTMLSelectElement;
      select.value = id;
      select.dispatchEvent(new Event('change'));
      fixture.detectChanges();
    },
    confirm: () =>
      host.querySelector('[data-testid="order-remove-lines-dialog-confirm"]') as HTMLButtonElement,
  };
}

const COMBO = { selectionId: 'sel-1', containerVariantId: 'cv-1', name: 'Lunch box', quantity: 2 };

describe('OrderRemoveLinesDialog (ADR 0169)', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    TestBed.inject(I18n).setLocale('en');
  });

  it('cannot be confirmed until something is ticked and a reason is picked', () => {
    const { confirm, tick, pickReason } = render([
      line({ lineId: 'a' }),
      line({ lineId: 'b', productName: 'Cola' }),
    ]);
    expect(confirm().disabled).toBe(true);

    tick('a');
    expect(confirm().disabled).toBe(true);

    pickReason('reason-1');
    expect(confirm().disabled).toBe(false);
  });

  it('emits the ticked lines and the reason, and no disposition', () => {
    const { fixture, tick, pickReason, confirm } = render([
      line({ lineId: 'a' }),
      line({ lineId: 'b', productName: 'Cola' }),
    ]);
    const emitted: RemoveLinesSubmission[] = [];
    fixture.componentInstance.confirm.subscribe((submission) => emitted.push(submission));

    tick('b');
    pickReason('reason-1');
    confirm().click();

    expect(emitted).toEqual([{ orderLineIds: ['b'], removalReasonId: 'reason-1' }]);
  });

  it('offers a combo once and removes it whole, by all of its component lines', () => {
    const { fixture, host, tick, pickReason, confirm } = render([
      line({ lineId: 'burger', productName: 'Burger', combo: COMBO }),
      line({ lineId: 'cola', productName: 'Cola', combo: COMBO }),
      line({ lineId: 'plain', productName: 'Fries' }),
    ]);
    const emitted: RemoveLinesSubmission[] = [];
    fixture.componentInstance.confirm.subscribe((submission) => emitted.push(submission));

    expect(host.querySelectorAll('input[type="checkbox"]')).toHaveLength(2);

    tick('sel-1');
    pickReason('reason-1');
    confirm().click();

    expect(emitted[0]?.orderLineIds).toEqual(['burger', 'cola']);
  });

  it('does not offer a line a rule added that the customer could not decline', () => {
    const { host } = render([
      line({ lineId: 'a' }),
      line({
        lineId: 'box',
        productName: 'Delivery box',
        origin: 'AUTO_ADD',
        autoAddMandatory: true,
      }),
    ]);

    expect(host.querySelector('[data-testid="order-remove-lines-dialog-choice-box"]')).toBeNull();
    expect(host.querySelector('[data-testid="order-remove-lines-dialog-choice-a"]')).not.toBeNull();
  });

  it('stops a selection that would leave nothing the customer chose, and points to cancelling', () => {
    const { host, tick, pickReason, confirm } = render([
      line({ lineId: 'a' }),
      line({ lineId: 'b', productName: 'Cola' }),
    ]);

    tick('a');
    tick('b');
    pickReason('reason-1');

    expect(host.querySelector('[data-testid="order-remove-lines-dialog-empty"]')).not.toBeNull();
    expect(confirm().disabled).toBe(true);
  });

  it('treats a rule’s optional line as not the customer’s: removing the customer’s own line would empty the order', () => {
    const { tick, pickReason, confirm } = render([
      line({ lineId: 'a' }),
      line({
        lineId: 'gift',
        productName: 'Free cookie',
        origin: 'AUTO_ADD',
        autoAddMandatory: false,
      }),
    ]);

    tick('a');
    pickReason('reason-1');

    expect(confirm().disabled).toBe(true);
  });

  it('says there is no reason to pick when the tenant has none yet', () => {
    const { host, tick, confirm } = render([line({ lineId: 'a' }), line({ lineId: 'b' })], []);

    tick('a');

    expect(host.querySelector('[data-testid="removal-reason-empty"]')).not.toBeNull();
    expect(confirm().disabled).toBe(true);
  });
});
