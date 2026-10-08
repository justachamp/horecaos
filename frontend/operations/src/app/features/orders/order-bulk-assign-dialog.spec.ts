import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { RosterEntryResponse } from '../couriers/couriers-api';
import {
  BulkAssignSubmission,
  DEFAULT_BULK_ASSIGN_REASON,
  OrderBulkAssignDialog,
} from './order-bulk-assign-dialog';

function courier(overrides: Partial<RosterEntryResponse>): RosterEntryResponse {
  return {
    courierId: 'c1',
    displayReference: 'K-001',
    status: 'ACTIVE',
    courierTypeId: 't1',
    courierTypeName: 'Scooter',
    vehicleClass: 'SCOOTER',
    activeAssignments: 1,
    concurrencyCeiling: 3,
    engagementStatus: 'ACTIVE',
    online: true,
    ...overrides,
  };
}

function render(couriers: readonly RosterEntryResponse[], busy = false) {
  const fixture = TestBed.createComponent(OrderBulkAssignDialog);
  fixture.componentRef.setInput('couriers', couriers);
  fixture.componentRef.setInput('count', 4);
  fixture.componentRef.setInput('busy', busy);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const byTestId = (id: string) => host.querySelector<HTMLElement>(`[data-testid="${id}"]`);
  return { fixture, host, byTestId };
}

describe('OrderBulkAssignDialog (ADR 0172)', () => {
  beforeEach(() => {
    TestBed.inject(I18n).setLocale('en');
  });

  it('lists each courier with their load against the type’s ceiling', () => {
    const { host } = render([
      courier({
        courierId: 'a',
        displayReference: 'K-001',
        activeAssignments: 1,
        concurrencyCeiling: 3,
      }),
    ]);

    expect(host.querySelector('[data-testid="order-bulk-assign-load"]')?.textContent?.trim()).toBe(
      '1 of 3 carried',
    );
  });

  it('pre-fills the reason code with OPERATIONS_BULK_ASSIGN and sends the picked courier with it', () => {
    const { fixture, host, byTestId } = render([courier({ courierId: 'a' })]);
    const sent: BulkAssignSubmission[] = [];
    fixture.componentInstance.confirmed.subscribe((value) => sent.push(value));

    expect((byTestId('order-bulk-assign-reason') as HTMLInputElement).value).toBe(
      DEFAULT_BULK_ASSIGN_REASON,
    );
    expect((byTestId('order-bulk-assign-confirm') as HTMLButtonElement).disabled).toBe(true);

    (host.querySelector('[data-testid="order-bulk-assign-courier"]') as HTMLInputElement).click();
    fixture.detectChanges();
    (byTestId('order-bulk-assign-confirm') as HTMLButtonElement).click();

    expect(sent).toEqual([{ courierId: 'a', reasonCode: 'OPERATIONS_BULK_ASSIGN' }]);
  });

  it('cannot pick a courier whose engagement is not active — the server would refuse every item', () => {
    const { fixture, host, byTestId } = render([
      courier({ courierId: 'a', engagementStatus: 'SUSPENDED_COMPLIANCE' }),
    ]);

    const radio = host.querySelector(
      '[data-testid="order-bulk-assign-courier"]',
    ) as HTMLInputElement;
    expect(radio.disabled).toBe(true);
    expect(byTestId('order-bulk-assign-inactive')).not.toBeNull();
    radio.click();
    fixture.detectChanges();
    expect((byTestId('order-bulk-assign-confirm') as HTMLButtonElement).disabled).toBe(true);
  });

  it('refuses a reason that is not a code, and says so', () => {
    const { fixture, host, byTestId } = render([courier({ courierId: 'a' })]);
    (host.querySelector('[data-testid="order-bulk-assign-courier"]') as HTMLInputElement).click();
    const reason = byTestId('order-bulk-assign-reason') as HTMLInputElement;
    reason.value = 'free text with spaces';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(byTestId('order-bulk-assign-reason-invalid')).not.toBeNull();
    expect((byTestId('order-bulk-assign-confirm') as HTMLButtonElement).disabled).toBe(true);
  });

  it('upper-cases what is typed so a code is never rejected for its case', () => {
    const { fixture, host, byTestId } = render([courier({ courierId: 'a' })]);
    const sent: BulkAssignSubmission[] = [];
    fixture.componentInstance.confirmed.subscribe((value) => sent.push(value));
    (host.querySelector('[data-testid="order-bulk-assign-courier"]') as HTMLInputElement).click();
    const reason = byTestId('order-bulk-assign-reason') as HTMLInputElement;
    reason.value = 'rush_hour';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (byTestId('order-bulk-assign-confirm') as HTMLButtonElement).click();

    expect(sent).toEqual([{ courierId: 'a', reasonCode: 'RUSH_HOUR' }]);
  });

  it('says so when the roster is empty, and dismisses without sending anything', () => {
    const { fixture, byTestId } = render([]);
    let dismissed = 0;
    fixture.componentInstance.dismissed.subscribe(() => (dismissed += 1));

    expect(byTestId('order-bulk-assign-empty')).not.toBeNull();
    byTestId('order-bulk-assign-dismiss')?.click();

    expect(dismissed).toBe(1);
  });

  it('locks every control while a request is in flight', () => {
    const { byTestId } = render([courier({ courierId: 'a' })], true);

    expect((byTestId('order-bulk-assign-confirm') as HTMLButtonElement).disabled).toBe(true);
    expect((byTestId('order-bulk-assign-dismiss') as HTMLButtonElement).disabled).toBe(true);
    expect((byTestId('order-bulk-assign-reason') as HTMLInputElement).disabled).toBe(true);
  });
});
