import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { BulkActionResponse } from './order-bulk-actions-api';
import { OrderQueueBulkResult } from './order-queue-bulk-result';

function response(overrides: Partial<BulkActionResponse>): BulkActionResponse {
  return {
    appliedCount: 0,
    failedCount: 0,
    items: [],
    ...overrides,
  } as BulkActionResponse;
}

function render(result: BulkActionResponse, busy = false) {
  const fixture = TestBed.createComponent(OrderQueueBulkResult);
  fixture.componentRef.setInput('result', result);
  fixture.componentRef.setInput('bulkBusy', busy);
  fixture.componentRef.setInput('itemLabel', (orderId: string) => `#${orderId}`);
  fixture.componentRef.setInput('problemLabel', (code: string | null | undefined) => `why:${code}`);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const byTestId = (id: string) => host.querySelector<HTMLButtonElement>(`[data-testid="${id}"]`);
  return { fixture, host, byTestId };
}

describe('OrderQueueBulkResult', () => {
  beforeEach(() => {
    TestBed.inject(I18n).setLocale('en');
  });

  it('says so, plainly, when every order was applied', () => {
    const { host, byTestId } = render(response({ appliedCount: 4 }));

    expect(host.querySelector('p')?.textContent?.trim()).toBe('All 4 applied');
    expect(byTestId('order-queue-bulk-retry')).toBeNull();
  });

  it('names each failed order and why, and leaves the successes out of the list', () => {
    const { host } = render(
      response({
        appliedCount: 1,
        failedCount: 2,
        items: [
          { orderId: 'a', itemStatus: 'APPLIED' },
          { orderId: 'b', itemStatus: 'FAILED', itemProblemCode: 'STALE_VERSION' },
          { orderId: 'c', itemStatus: 'FAILED', itemProblemCode: 'ILLEGAL_TRANSITION' },
        ],
      } as Partial<BulkActionResponse>),
    );

    expect(host.querySelector('p')?.textContent?.trim()).toBe('1 applied · 2 problems');
    const rows = [...host.querySelectorAll('[data-testid="order-queue-bulk-result-item"]')].map(
      (row) => row.textContent?.replace(/\s+/g, ' ').trim(),
    );
    expect(rows).toEqual(['#b — why:STALE_VERSION', '#c — why:ILLEGAL_TRANSITION']);
  });

  it('disables Retry while a bulk action is already running', () => {
    const failing = response({
      failedCount: 1,
      items: [{ orderId: 'b', itemStatus: 'FAILED', itemProblemCode: 'STALE_VERSION' }],
    } as Partial<BulkActionResponse>);

    expect(render(failing, true).byTestId('order-queue-bulk-retry')?.disabled).toBe(true);
    expect(render(failing, false).byTestId('order-queue-bulk-retry')?.disabled).toBe(false);
  });

  it('raises a request for retry and for dismiss', () => {
    const { fixture, byTestId } = render(
      response({
        failedCount: 1,
        items: [{ orderId: 'b', itemStatus: 'FAILED', itemProblemCode: 'STALE_VERSION' }],
      } as Partial<BulkActionResponse>),
    );
    const requests: string[] = [];
    fixture.componentInstance.retryRequested.subscribe(() => requests.push('retry'));
    fixture.componentInstance.dismissRequested.subscribe(() => requests.push('dismiss'));

    byTestId('order-queue-bulk-retry')?.click();
    byTestId('order-queue-bulk-result-dismiss')?.click();

    expect(requests).toEqual(['retry', 'dismiss']);
  });
});
