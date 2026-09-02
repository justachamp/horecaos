import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { FiscalApi, FiscalCoverageView, FiscalDocumentView } from './fiscal-api';
import { FiscalPage } from './fiscal-page';

const TENANT_ID = 'tenant-1';

const BLOCKED_DOCUMENT: FiscalDocumentView = {
  documentId: 'doc-1',
  orderId: 'order-7',
  legalEntityId: 'entity-1',
  documentType: 'SALE',
  responsibility: 'PLATFORM',
  providerType: 'CLICK',
  status: 'BLOCKED',
  reasonCode: 'PROVIDER_REPORT_OVERDUE',
  reasonNote: null,
  hasEvidence: false,
  attemptCount: 2,
  version: 3,
  submittedAt: '2026-08-20T10:00:00Z',
  reportingDeadlineAt: '2026-08-27T10:00:00Z',
  blockedAt: '2026-08-27T10:00:01Z',
};

const COVERAGE: FiscalCoverageView = {
  from: '2026-08-01T00:00:00Z',
  to: '2026-08-30T00:00:00Z',
  saleDocuments: 100,
  issued: 20,
  notApplicable: 70,
  notApplicableCash: 70,
  blocked: 5,
  failed: 3,
  awaitingProvider: 2,
  unreceipted: 10,
  issuedShareBasisPoints: 2000,
  notApplicableShareBasisPoints: 7000,
  unreceiptedShareBasisPoints: 1000,
  providerPathIsMinority: true,
  warning: null,
};

class FakeCurrentTenant {
  readonly tenantId = signal<string | null>(TENANT_ID);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('FiscalPage', () => {
  let fixture: ComponentFixture<FiscalPage>;
  let api: {
    blocked: ReturnType<typeof vi.fn>;
    forOrder: ReturnType<typeof vi.fn>;
    coverage: ReturnType<typeof vi.fn>;
    retry: ReturnType<typeof vi.fn>;
    unblock: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    api = {
      blocked: vi
        .fn()
        .mockResolvedValue({ count: 1, documents: [BLOCKED_DOCUMENT], warning: null }),
      forOrder: vi.fn().mockResolvedValue([]),
      coverage: vi.fn().mockResolvedValue(COVERAGE),
      retry: vi
        .fn()
        .mockResolvedValue({
          documentId: 'doc-1',
          outcome: 'SUBMITTED',
          version: 4,
          warning: null,
        }),
      unblock: vi
        .fn()
        .mockResolvedValue({ documentId: 'doc-1', outcome: 'PENDING', version: 4, warning: null }),
    };

    await TestBed.configureTestingModule({
      imports: [FiscalPage],
      providers: [
        { provide: FiscalApi, useValue: api },
        { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(FiscalPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  });

  it('lists the blocked worklist and the coverage report on load', () => {
    expect(api.blocked).toHaveBeenCalledWith(TENANT_ID, undefined);
    expect(api.coverage).toHaveBeenCalledWith(TENANT_ID, expect.any(String), expect.any(String));
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('order-7');
    expect(text).toContain('PROVIDER_REPORT_OVERDUE');
  });

  it('never renders a fiscal sign, receipt URL or marking code', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('qrCodeURL');
    expect(text).not.toContain('markingCode');
  });

  it('reloads the worklist filtered by reason when a reason code is chosen', async () => {
    const select = fixture.nativeElement.querySelector('select.filter') as HTMLSelectElement;
    select.value = 'MARKS_INCOMPLETE';
    select.dispatchEvent(new Event('change'));
    await flushMicrotasks();

    expect(api.blocked).toHaveBeenLastCalledWith(TENANT_ID, 'MARKS_INCOMPLETE');
  });

  it('retries a blocked document with the reason and its version as If-Match', async () => {
    const retryButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((button) => button.textContent?.trim() === 'Ask again') as HTMLButtonElement;
    retryButton.click();
    fixture.detectChanges();

    const reasonInput = fixture.nativeElement.querySelector('#resolve-reason') as HTMLInputElement;
    reasonInput.value = 'Payme reporting window reopened';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const submit = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
    ).find((button) => button.textContent?.trim() === 'Ask again') as HTMLButtonElement;
    submit.click();
    await flushMicrotasks();

    expect(api.retry).toHaveBeenCalledWith(
      TENANT_ID,
      'doc-1',
      3,
      'Payme reporting window reopened',
    );
  });

  it('reports coverage as counts and shares, never as one collapsed figure', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('20');
    expect(text).toContain('20.0%');
    expect(text).toContain('70');
    expect(text).toContain('70.0%');
    expect(text).toContain('minority');
  });

  it('shows a denied state when the session names no tenant', async () => {
    await TestBed.resetTestingModule()
      .configureTestingModule({
        imports: [FiscalPage],
        providers: [
          { provide: FiscalApi, useValue: api },
          {
            provide: CurrentTenant,
            useValue: {
              tenantId: signal(null),
              denied: signal(true),
              ensureLoaded: vi.fn().mockResolvedValue(undefined),
            },
          },
        ],
      })
      .compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const deniedFixture = TestBed.createComponent(FiscalPage);
    deniedFixture.detectChanges();
    await flushMicrotasks();
    deniedFixture.detectChanges();

    expect((deniedFixture.nativeElement as HTMLElement).textContent).toContain('not permitted');
  });
});
