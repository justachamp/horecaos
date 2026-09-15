import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { FiscalApi, FiscalDocumentView, FiscalResolutionView } from '../finance/fiscal/fiscal-api';
import { OrderFiscalPanel } from './order-fiscal-panel';

const TENANT = 't1';
const ORDER = 'order-1';

function document_(overrides: Partial<FiscalDocumentView> = {}): FiscalDocumentView {
  return {
    documentId: 'doc-1',
    orderId: ORDER,
    publicOrderNumber: 'A-1001',
    legalEntityId: 'entity-1',
    documentType: 'SALE',
    responsibility: 'PROVIDER',
    providerType: 'PAYME',
    status: 'FAILED',
    reasonCode: 'PROVIDER_REJECTED',
    reasonNote: null,
    hasEvidence: false,
    attemptCount: 1,
    version: 1,
    submittedAt: '2026-09-15T09:00:00Z',
    reportingDeadlineAt: null,
    blockedAt: null,
    ...overrides,
  };
}

function configure(options: { fiscalApi?: Partial<FiscalApi> }): void {
  TestBed.configureTestingModule({
    providers: [{ provide: FiscalApi, useValue: options.fiscalApi ?? {} }],
  });
  TestBed.inject(I18n).setLocale('en');
}

async function render() {
  const fixture = TestBed.createComponent(OrderFiscalPanel);
  fixture.componentRef.setInput('tenantId', TENANT);
  fixture.componentRef.setInput('orderId', ORDER);
  fixture.detectChanges();
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
  return fixture;
}

describe('OrderFiscalPanel: the order-detail fiscal read (row 1.2l)', () => {
  it('renders a localized status label, never the raw enum token', async () => {
    configure({
      fiscalApi: { forOrder: vi.fn().mockResolvedValue([document_({ status: 'FAILED' })]) },
    });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    const badge = host.querySelector('[data-testid="order-fiscal-document-status"]');
    expect(badge?.textContent).toContain('Failed');
    expect(badge?.textContent).not.toContain('FAILED');
  });

  it('surfaces a FAILED document that never reached the blocked worklist', async () => {
    const forOrder = vi.fn().mockResolvedValue([document_({ status: 'FAILED' })]);
    configure({ fiscalApi: { forOrder } });
    const fixture = await render();

    expect(forOrder).toHaveBeenCalledWith(TENANT, ORDER);
    expect(
      fixture.nativeElement.querySelectorAll('[data-testid="order-fiscal-document-row"]'),
    ).toHaveLength(1);
  });

  it('offers to retry a FAILED document', async () => {
    configure({
      fiscalApi: { forOrder: vi.fn().mockResolvedValue([document_({ status: 'FAILED' })]) },
    });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-fiscal-retry-toggle"]'),
    ).not.toBeNull();
  });

  it('never offers to retry an ISSUED document -- already resolved, per FiscalDocumentService', async () => {
    configure({
      fiscalApi: {
        forOrder: vi.fn().mockResolvedValue([document_({ status: 'ISSUED', hasEvidence: true })]),
      },
    });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-fiscal-retry-toggle"]'),
    ).toBeNull();
  });

  it('shows the empty state when the order has no fiscal documents', async () => {
    configure({ fiscalApi: { forOrder: vi.fn().mockResolvedValue([]) } });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-fiscal-empty"]'),
    ).not.toBeNull();
  });

  it('shows a denied state on a 403 rather than a generic error', async () => {
    configure({
      fiscalApi: {
        forOrder: vi
          .fn()
          .mockRejectedValue(new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null)),
      },
    });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-fiscal-denied"]'),
    ).not.toBeNull();
  });

  it('shows an error state rather than crashing on any other failure', async () => {
    configure({
      fiscalApi: {
        forOrder: vi
          .fn()
          .mockRejectedValue(new ApiError(ApiErrorCode.INTERNAL_ERROR, 500, null, null)),
      },
    });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-fiscal-error"]'),
    ).not.toBeNull();
  });
});

describe('OrderFiscalPanel: manual re-fiscalize (row 1.2l)', () => {
  it('refuses to submit without a reason', async () => {
    configure({ fiscalApi: { forOrder: vi.fn().mockResolvedValue([document_()]) } });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    (host.querySelector('[data-testid="order-fiscal-retry-toggle"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(
      (host.querySelector('[data-testid="order-fiscal-retry-submit"]') as HTMLButtonElement)
        .disabled,
    ).toBe(true);
  });

  it('retries with the aggregate version, then reloads and shows the localized outcome', async () => {
    const retry = vi.fn().mockResolvedValue({
      documentId: 'doc-1',
      outcome: 'ISSUED',
      version: 2,
      warning: null,
    } satisfies FiscalResolutionView);
    const forOrder = vi
      .fn()
      .mockResolvedValueOnce([document_({ status: 'FAILED', version: 1 })])
      .mockResolvedValueOnce([document_({ status: 'ISSUED', version: 2, hasEvidence: true })]);
    configure({ fiscalApi: { forOrder, retry } });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    (host.querySelector('[data-testid="order-fiscal-retry-toggle"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    (host.querySelector('[data-testid="order-fiscal-retry-reason"]') as HTMLInputElement).value =
      'operator asked again after a provider outage';
    host
      .querySelector('[data-testid="order-fiscal-retry-reason"]')
      ?.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (host.querySelector('[data-testid="order-fiscal-retry-submit"]') as HTMLButtonElement).click();
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();

    expect(retry).toHaveBeenCalledWith(
      TENANT,
      'doc-1',
      1,
      'operator asked again after a provider outage',
    );
    expect(forOrder).toHaveBeenCalledTimes(2);
    expect(host.querySelector('[data-testid="order-fiscal-result"]')?.textContent).toContain(
      'Receipt issued',
    );
  });

  it('shows the failure reason rather than a generic error when a retry is refused', async () => {
    const retry = vi
      .fn()
      .mockRejectedValue(new ApiError(ApiErrorCode.RESOURCE_CONFLICT, 409, null, null));
    configure({
      fiscalApi: { forOrder: vi.fn().mockResolvedValue([document_({ status: 'FAILED' })]), retry },
    });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    (host.querySelector('[data-testid="order-fiscal-retry-toggle"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    (host.querySelector('[data-testid="order-fiscal-retry-reason"]') as HTMLInputElement).value =
      'retry after fix';
    host
      .querySelector('[data-testid="order-fiscal-retry-reason"]')
      ?.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (host.querySelector('[data-testid="order-fiscal-retry-submit"]') as HTMLButtonElement).click();
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="order-fiscal-retry-error"]')).not.toBeNull();
  });
});
