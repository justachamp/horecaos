import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { I18n } from '../../../core/i18n/i18n';
import { BrowserPrinter } from './browser-print';
import { PrintJobView, PrintingApi } from './printing-api';
import { click, el, flush, pick } from './printing-dom';
import { browserPrinter, job, printer } from './printing-fixtures';
import { PrintQueueCard } from './print-queue-card';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

async function render(
  api: Partial<PrintingApi>,
  browser: Partial<BrowserPrinter> = { print: vi.fn().mockResolvedValue(undefined) },
): Promise<ComponentFixture<PrintQueueCard>> {
  await TestBed.configureTestingModule({
    imports: [PrintQueueCard],
    providers: [
      { provide: PrintingApi, useValue: api },
      { provide: BrowserPrinter, useValue: browser },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(PrintQueueCard);
  fixture.componentRef.setInput('scope', SCOPE);
  fixture.componentRef.setInput('printers', [printer(), browserPrinter()]);
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  return fixture;
}

function page(items: readonly PrintJobView[], nextCursor: string | null = null) {
  return { items, nextCursor };
}

describe('PrintQueueCard', () => {
  it('lists a job that died with the reason in words and an offer to print it again', async () => {
    const jobs = vi
      .fn()
      .mockResolvedValue(
        page([job({ id: 'j1', status: 'DEAD', failureCode: 'OUT_OF_PAPER', attempt: 6 })]),
      );
    const fixture = await render({ jobs });

    expect(el(fixture, '[data-testid="print-job-status-j1"]').textContent).toContain('Failed');
    expect(el(fixture, '[data-testid="print-job-failure-j1"]').textContent).toContain(
      'out of paper',
    );
    expect(el(fixture, '[data-job="j1"]').textContent).toContain('attempt 6');
    expect(el(fixture, '[data-testid="print-job-reprint-j1"]')).not.toBeNull();
    expect(el(fixture, '[data-testid="print-job-cancel-j1"]')).toBeNull();
  });

  it('offers cancel only on a queued job, and reprint only on a finished one', async () => {
    const jobs = vi
      .fn()
      .mockResolvedValue(
        page([
          job({ id: 'queued', status: 'QUEUED' }),
          job({ id: 'claimed', status: 'CLAIMED' }),
          job({ id: 'test', status: 'PRINTED', documentType: 'TEST_PAGE' }),
        ]),
      );
    const fixture = await render({ jobs });

    expect(el(fixture, '[data-testid="print-job-cancel-queued"]')).not.toBeNull();
    expect(el(fixture, '[data-testid="print-job-reprint-queued"]')).toBeNull();
    expect(el(fixture, '[data-testid="print-job-cancel-claimed"]')).toBeNull();
    expect(el(fixture, '[data-testid="print-job-reprint-claimed"]')).toBeNull();
    // A test page is a printer's, not a document that can be printed again.
    expect(el(fixture, '[data-testid="print-job-reprint-test"]')).toBeNull();
  });

  it('cancels a queued job and reads the queue again', async () => {
    const jobs = vi.fn().mockResolvedValue(page([job({ id: 'q', status: 'QUEUED' })]));
    const cancelJob = vi.fn().mockResolvedValue(job({ id: 'q', status: 'CANCELLED' }));
    const fixture = await render({ jobs, cancelJob });

    await click(fixture, '[data-testid="print-job-cancel-q"]');

    expect(cancelJob).toHaveBeenCalledWith(SCOPE, 'q');
    expect(jobs).toHaveBeenCalledTimes(2);
  });

  it('starts again from no cursor when the state filter changes', async () => {
    // The first page has a next one, so a filter change that kept its cursor would be visibly wrong.
    const jobs = vi.fn().mockResolvedValue(page([job({ id: 'a' })], 'cursor-1'));
    const fixture = await render({ jobs });
    expect(jobs).toHaveBeenLastCalledWith(SCOPE, null, null);

    pick(fixture, '[data-testid="print-queue-filter"]', 'DEAD');
    await flush();
    fixture.detectChanges();

    expect(jobs).toHaveBeenLastCalledWith(SCOPE, 'DEAD', null);
  });

  it('appends the next page under its cursor', async () => {
    const jobs = vi
      .fn()
      .mockResolvedValueOnce(page([job({ id: 'a' })], 'cursor-1'))
      .mockResolvedValueOnce(page([job({ id: 'b' })], null));
    const fixture = await render({ jobs });

    await click(fixture, '[data-testid="print-queue-more"]');

    expect(jobs).toHaveBeenLastCalledWith(SCOPE, null, 'cursor-1');
    expect(el(fixture, '[data-job="a"]')).not.toBeNull();
    expect(el(fixture, '[data-job="b"]')).not.toBeNull();
    expect(el(fixture, '[data-testid="print-queue-more"]')).toBeNull();
  });

  it('reprints with the reason the person chose and the printer, and refreshes', async () => {
    const jobs = vi.fn().mockResolvedValue(page([job({ id: 'j1', status: 'DEAD' })]));
    const reprintJob = vi.fn().mockResolvedValue(job({ id: 'j2', status: 'QUEUED' }));
    const fixture = await render({ jobs, reprintJob });

    await click(fixture, '[data-testid="print-job-reprint-j1"]');
    pick(fixture, '[data-testid="print-reprint-reason"]', 'PAPER_JAM');
    await click(fixture, '[data-testid="print-reprint-confirm"]');

    expect(reprintJob).toHaveBeenCalledWith(SCOPE, 'j1', 'PAPER_JAM', 'printer-1');
    expect(el(fixture, '[data-testid="print-queue-notice"]').textContent).toContain(
      'new print job',
    );
    expect(jobs).toHaveBeenCalledTimes(2);
  });

  it('prints a reprint on a browser printer through the dialog at that roll width', async () => {
    const jobs = vi.fn().mockResolvedValue(page([job({ id: 'j1', status: 'PRINTED' })]));
    const reprintJob = vi
      .fn()
      .mockResolvedValue(
        job({ id: 'j2', status: 'HANDED_OFF', printerId: 'printer-browser', tapeWidthMm: 58 }),
      );
    const picture = new Blob(['png']);
    const preview = vi.fn().mockResolvedValue(picture);
    const print = vi.fn().mockResolvedValue(undefined);
    const fixture = await render({ jobs, reprintJob, preview }, { print });

    await click(fixture, '[data-testid="print-job-reprint-j1"]');
    pick(fixture, '[data-testid="print-reprint-printer"]', 'printer-browser');
    await click(fixture, '[data-testid="print-reprint-confirm"]');

    expect(reprintJob).toHaveBeenCalledWith(SCOPE, 'j1', 'PRINTER_FAULT', 'printer-browser');
    expect(preview).toHaveBeenCalledWith(SCOPE, { jobId: 'j2' });
    expect(print).toHaveBeenCalledWith(picture, 58);
  });

  it('shows the picture of a job on request and not a job whose document was erased', async () => {
    const original = { create: URL.createObjectURL, revoke: URL.revokeObjectURL };
    URL.createObjectURL = vi.fn().mockReturnValue('blob:preview');
    URL.revokeObjectURL = vi.fn();
    const jobs = vi
      .fn()
      .mockResolvedValue(page([job({ id: 'j1' }), job({ id: 'old', documentErased: true })]));
    const preview = vi.fn().mockResolvedValue(new Blob(['png']));
    const fixture = await render({ jobs, preview });

    expect(el(fixture, '[data-testid="print-job-preview-old"]')).toBeNull();
    await click(fixture, '[data-testid="print-job-preview-j1"]');

    expect(preview).toHaveBeenCalledWith(SCOPE, { jobId: 'j1' });
    expect(el<HTMLImageElement>(fixture, '[data-testid="print-job-picture"]').src).toContain(
      'blob:preview',
    );
    URL.createObjectURL = original.create;
    URL.revokeObjectURL = original.revoke;
  });

  it('says the queue is empty when there is nothing', async () => {
    const fixture = await render({ jobs: vi.fn().mockResolvedValue(page([])) });
    expect(el(fixture, '[data-testid="print-queue-empty"]')).not.toBeNull();
  });
});
