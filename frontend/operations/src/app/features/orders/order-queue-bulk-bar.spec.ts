import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { OrderQueueBulkBar } from './order-queue-bulk-bar';

interface Inputs {
  selectionCount: number;
  bulkAdvanceTargetStatus: string | null;
  bulkAdvanceLabel: string;
  canBulkCancel: boolean;
  bulkCancelUnavailableMessage: string | null;
  bulkBusy: boolean;
}

function render(overrides: Partial<Inputs> = {}) {
  const inputs: Inputs = {
    selectionCount: 2,
    bulkAdvanceTargetStatus: 'PREPARING',
    bulkAdvanceLabel: 'Start preparing',
    canBulkCancel: true,
    bulkCancelUnavailableMessage: null,
    bulkBusy: false,
    ...overrides,
  };
  const fixture = TestBed.createComponent(OrderQueueBulkBar);
  for (const [name, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(name, value);
  }
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const byTestId = (id: string) => host.querySelector<HTMLElement>(`[data-testid="${id}"]`);
  return { fixture, host, byTestId };
}

describe('OrderQueueBulkBar', () => {
  beforeEach(() => {
    TestBed.inject(I18n).setLocale('en');
  });

  it('says how many orders are selected', () => {
    const { byTestId } = render({ selectionCount: 5 });

    expect(byTestId('order-queue-bulk-count')?.textContent?.trim()).toBe('Selected 5');
  });

  it('offers the advance and cancel buttons the selection allows', () => {
    const { byTestId } = render();

    expect(byTestId('order-queue-bulk-advance')?.textContent?.trim()).toBe('Start preparing');
    expect(byTestId('order-queue-bulk-cancel')).not.toBeNull();
    expect(byTestId('order-queue-bulk-cancel-unavailable')).toBeNull();
  });

  it('explains instead of offering advance when the selection has no common next step', () => {
    const { host, byTestId } = render({ bulkAdvanceTargetStatus: null, selectionCount: 3 });

    expect(byTestId('order-queue-bulk-advance')).toBeNull();
    expect(host.querySelector('.order-queue__bulk-unavailable')).not.toBeNull();
  });

  it('stays quiet about advance when a single order is selected and cannot advance', () => {
    const { host } = render({ bulkAdvanceTargetStatus: null, selectionCount: 1 });

    expect(host.querySelector('.order-queue__bulk-unavailable')).toBeNull();
  });

  it('gives the reason cancel is unavailable in place of the button', () => {
    const { byTestId } = render({
      canBulkCancel: false,
      bulkCancelUnavailableMessage: '2 of 3 cannot be cancelled',
    });

    expect(byTestId('order-queue-bulk-cancel')).toBeNull();
    expect(byTestId('order-queue-bulk-cancel-unavailable')?.textContent?.trim()).toBe(
      '2 of 3 cannot be cancelled',
    );
  });

  it('disables both buttons while a bulk action runs', () => {
    const { byTestId } = render({ bulkBusy: true });

    expect((byTestId('order-queue-bulk-advance') as HTMLButtonElement).disabled).toBe(true);
    expect((byTestId('order-queue-bulk-cancel') as HTMLButtonElement).disabled).toBe(true);
  });

  it('raises a request for advance, cancel and clear', () => {
    const { fixture, byTestId } = render();
    const requests: string[] = [];
    const instance = fixture.componentInstance;
    instance.advanceRequested.subscribe(() => requests.push('advance'));
    instance.cancelRequested.subscribe(() => requests.push('cancel'));
    instance.clearRequested.subscribe(() => requests.push('clear'));

    byTestId('order-queue-bulk-advance')?.click();
    byTestId('order-queue-bulk-cancel')?.click();
    byTestId('order-queue-bulk-clear')?.click();

    expect(requests).toEqual(['advance', 'cancel', 'clear']);
  });
});
