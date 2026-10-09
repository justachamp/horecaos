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

describe('OrderQueueBulkResult: bulk courier assignment notes (ADR 0172)', () => {
  beforeEach(() => {
    TestBed.inject(I18n).setLocale('en');
  });

  function renderWithNotes(result: BulkActionResponse) {
    const fixture = TestBed.createComponent(OrderQueueBulkResult);
    fixture.componentRef.setInput('result', result);
    fixture.componentRef.setInput('bulkBusy', false);
    fixture.componentRef.setInput('itemLabel', (orderId: string) => `#${orderId}`);
    fixture.componentRef.setInput(
      'problemLabel',
      (code: string | null | undefined) => `why:${code}`,
    );
    fixture.componentRef.setInput('noteLabel', (note: string | null | undefined) => `note:${note}`);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows an applied item’s note beside the row, so an override is never silent', () => {
    const host = renderWithNotes(
      response({
        appliedCount: 2,
        failedCount: 0,
        items: [
          { orderId: 'a', itemStatus: 'APPLIED', note: 'OVERRODE_DISPATCH_RULE' },
          { orderId: 'b', itemStatus: 'APPLIED' },
        ],
      } as Partial<BulkActionResponse>),
    );

    const notes = [
      ...host.querySelectorAll('[data-testid="order-queue-bulk-result-applied-note"]'),
    ].map((row) => row.textContent?.replace(/\s+/g, ' ').trim());
    expect(notes).toEqual(['#a — note:OVERRODE_DISPATCH_RULE']);
  });

  it('names the rule an assignment overrode, and only when the note says it was overridden (ADR 0172)', () => {
    const host = renderWithNotes(
      response({
        appliedCount: 3,
        failedCount: 0,
        items: [
          { orderId: 'a', itemStatus: 'APPLIED', note: 'OVERRODE_DISPATCH_RULE', ruleId: 'FAR-ZONE' },
          { orderId: 'b', itemStatus: 'APPLIED', note: 'NO_OPEN_SHIFT', ruleId: 'FAR-ZONE' },
          { orderId: 'c', itemStatus: 'APPLIED', note: 'NO_OPEN_SHIFT,OVERRODE_DISPATCH_RULE', ruleId: 'DEFAULT' },
        ],
      } as Partial<BulkActionResponse>),
    );

    const rules = [...host.querySelectorAll('[data-testid="order-queue-bulk-result-applied-note"]')].map(
      (row) => row.querySelector('[data-testid="order-queue-bulk-result-rule"]')?.textContent?.trim() ?? null,
    );
    expect(rules).toEqual(['rule FAR-ZONE', null, 'rule DEFAULT']);
  });

  it('shows no notes list at all when no applied item has one', () => {
    const host = renderWithNotes(
      response({
        appliedCount: 1,
        items: [{ orderId: 'a', itemStatus: 'APPLIED' }],
      } as Partial<BulkActionResponse>),
    );

    expect(host.querySelector('[data-testid="order-queue-bulk-result-notes"]')).toBeNull();
  });

  it('puts a refusal’s named reasons next to its problem', () => {
    const host = renderWithNotes(
      response({
        failedCount: 1,
        items: [
          {
            orderId: 'c',
            itemStatus: 'FAILED',
            itemProblemCode: 'COURIER_NOT_ELIGIBLE',
            note: 'REGISTRATION_LAPSED',
          },
        ],
      } as Partial<BulkActionResponse>),
    );

    const row = host.querySelector('[data-testid="order-queue-bulk-result-item"]');
    expect(row?.textContent?.replace(/\s+/g, ' ').trim()).toBe(
      '#c — why:COURIER_NOT_ELIGIBLE (note:REGISTRATION_LAPSED)',
    );
  });
});
