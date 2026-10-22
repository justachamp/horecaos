import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { FeatureFlags } from '../../core/feature-flags';
import { I18n } from '../../core/i18n/i18n';
import { BrowserPrinter } from '../settings/printing/browser-print';
import { PrintingApi } from '../settings/printing/printing-api';
import { click, el, flush, pick } from '../settings/printing/printing-dom';
import { browserPrinter, job, printer } from '../settings/printing/printing-fixtures';
import { OrderPrintPanel } from './order-print-panel';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

class FakeFlags {
  constructor(private readonly on: boolean) {}
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
  isOn = (code: string): boolean => this.on && code === 'feature.printing';
}

async function render(
  api: Partial<PrintingApi>,
  options: { flagsOn?: boolean; browser?: Partial<BrowserPrinter> } = {},
): Promise<ComponentFixture<OrderPrintPanel>> {
  await TestBed.configureTestingModule({
    imports: [OrderPrintPanel],
    providers: [
      { provide: PrintingApi, useValue: { printers: vi.fn().mockResolvedValue([]), ...api } },
      { provide: FeatureFlags, useValue: new FakeFlags(options.flagsOn ?? true) },
      {
        provide: BrowserPrinter,
        useValue: options.browser ?? { print: vi.fn().mockResolvedValue(undefined) },
      },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(OrderPrintPanel);
  fixture.componentRef.setInput('scope', SCOPE);
  fixture.componentRef.setInput('orderId', 'order-1');
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  return fixture;
}

describe('OrderPrintPanel', () => {
  it('is absent while printing is switched off for the tenant', async () => {
    const fixture = await render({}, { flagsOn: false });
    expect(el(fixture, '[data-testid="order-detail-print"]')).toBeNull();
  });

  it('reveals itself on request: scrolled to, with the cursor on the first control (the row’s «Печать»)', async () => {
    const fixture = await render({});
    const host = fixture.nativeElement as HTMLElement;
    const scrolled = vi.fn();
    host.scrollIntoView = scrolled;

    fixture.componentInstance.reveal();

    expect(scrolled).toHaveBeenCalledOnce();
    expect(document.activeElement).toBe(host.querySelector('[data-testid="order-print-document"]'));
  });

  it('does not reveal anything while printing is off: a stale click scrolls to nothing', async () => {
    const fixture = await render({}, { flagsOn: false });
    const host = fixture.nativeElement as HTMLElement;
    const scrolled = vi.fn();
    host.scrollIntoView = scrolled;

    fixture.componentInstance.reveal();

    expect(scrolled).not.toHaveBeenCalled();
  });

  it('says it is not the POS export, so nobody presses one believing it is the other', async () => {
    const fixture = await render({});
    expect(el(fixture, '[data-testid="order-detail-print"]').textContent).toContain(
      'This is not “POS export”',
    );
  });

  it('prints the order summary where the route says, with the reason, and says it was queued', async () => {
    const printOrderDocument = vi.fn().mockResolvedValue([job({ status: 'QUEUED' })]);
    const fixture = await render({ printOrderDocument });

    await click(fixture, '[data-testid="order-print-submit"]');

    expect(printOrderDocument).toHaveBeenCalledWith(
      SCOPE,
      'order-1',
      'ORDER_SLIP',
      'CUSTOMER_REQUEST',
      null,
    );
    expect(el(fixture, '[data-testid="order-print-notice"]').textContent).toContain(
      'Queued for printing: 1',
    );
  });

  it('prints another document on a chosen printer for a chosen reason', async () => {
    const printOrderDocument = vi.fn().mockResolvedValue([job({ status: 'QUEUED' })]);
    const fixture = await render({
      printOrderDocument,
      printers: vi
        .fn()
        .mockResolvedValue([printer(), printer({ id: 'retired', status: 'RETIRED' })]),
    });
    // A retired printer is not offered.
    expect(
      Array.from(el<HTMLSelectElement>(fixture, '[data-testid="order-print-printer"]').options).map(
        (option) => option.value,
      ),
    ).toEqual(['', 'printer-1']);

    pick(fixture, '[data-testid="order-print-document"]', 'FISCAL_RECEIPT');
    pick(fixture, '[data-testid="order-print-printer"]', 'printer-1');
    pick(fixture, '[data-testid="order-print-reason"]', 'KITCHEN_REQUEST');
    await click(fixture, '[data-testid="order-print-submit"]');

    expect(printOrderDocument).toHaveBeenCalledWith(
      SCOPE,
      'order-1',
      'FISCAL_RECEIPT',
      'KITCHEN_REQUEST',
      'printer-1',
    );
  });

  it('offers no printer choice to a person who cannot read the printer list', async () => {
    const fixture = await render({ printers: vi.fn().mockRejectedValue(new Error('403')) });
    expect(el(fixture, '[data-testid="order-print-printer"]')).toBeNull();
    expect(el(fixture, '[data-testid="order-print-submit"]')).not.toBeNull();
  });

  it('tells the person what to do when nothing is routed for the document', async () => {
    const printOrderDocument = vi
      .fn()
      .mockRejectedValue(
        new ApiError(
          ApiErrorCode.UNPROCESSABLE_STATE,
          422,
          { status: 422, code: ApiErrorCode.UNPROCESSABLE_STATE, reason: 'NO_ROUTE' },
          null,
        ),
      );
    const fixture = await render({ printOrderDocument });

    await click(fixture, '[data-testid="order-print-submit"]');

    expect(el(fixture, '[data-testid="order-print-error"]').textContent).toContain(
      'A manager adds a route',
    );
    expect(el(fixture, '[data-testid="order-print-notice"]')).toBeNull();
  });

  it('shows the platform’s own reason when a fiscal receipt is refused for the document’s state', async () => {
    const printOrderDocument = vi.fn().mockRejectedValue(
      new ApiError(
        ApiErrorCode.UNPROCESSABLE_STATE,
        422,
        {
          status: 422,
          code: ApiErrorCode.UNPROCESSABLE_STATE,
          detail: 'The fiscal document is PENDING; only an ISSUED sale prints',
        },
        null,
      ),
    );
    const fixture = await render({ printOrderDocument });
    pick(fixture, '[data-testid="order-print-document"]', 'FISCAL_RECEIPT');
    await click(fixture, '[data-testid="order-print-submit"]');

    expect(el(fixture, '[data-testid="order-print-error"]').textContent).toContain('PENDING');
  });

  it('prints a browser printer’s job through this workstation’s dialog at the roll’s width', async () => {
    const picture = new Blob(['png']);
    const print = vi.fn().mockResolvedValue(undefined);
    const printOrderDocument = vi
      .fn()
      .mockResolvedValue([job({ id: 'j9', status: 'HANDED_OFF', tapeWidthMm: 58 })]);
    const handedOffPicture = vi.fn().mockResolvedValue(picture);
    const fixture = await render(
      {
        printOrderDocument,
        handedOffPicture,
        printers: vi.fn().mockResolvedValue([browserPrinter()]),
      },
      { browser: { print } },
    );

    await click(fixture, '[data-testid="order-print-submit"]');

    expect(handedOffPicture).toHaveBeenCalledWith(SCOPE, 'j9');
    expect(print).toHaveBeenCalledWith(picture, 58);
    expect(el(fixture, '[data-testid="order-print-notice"]').textContent).toContain('print dialog');
  });
});
