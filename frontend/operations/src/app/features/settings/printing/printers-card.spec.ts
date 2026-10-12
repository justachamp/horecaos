import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { I18n } from '../../../core/i18n/i18n';
import { BrowserPrinter } from './browser-print';
import { AgentView, PrinterView, PrintingApi } from './printing-api';
import { click, el, flush, pick, type } from './printing-dom';
import { agent, browserPrinter, job, printer } from './printing-fixtures';
import { PrintersCard } from './printers-card';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

async function render(
  api: Partial<PrintingApi>,
  printers: readonly PrinterView[],
  agents: readonly AgentView[] = [agent()],
  browser: Partial<BrowserPrinter> = { print: vi.fn().mockResolvedValue(undefined) },
): Promise<{ fixture: ComponentFixture<PrintersCard>; changed: ReturnType<typeof vi.fn> }> {
  await TestBed.configureTestingModule({
    imports: [PrintersCard],
    providers: [
      { provide: PrintingApi, useValue: api },
      { provide: BrowserPrinter, useValue: browser },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(PrintersCard);
  fixture.componentRef.setInput('scope', SCOPE);
  fixture.componentRef.setInput('printers', printers);
  fixture.componentRef.setInput('agents', agents);
  const changed = vi.fn();
  fixture.componentInstance.changed.subscribe(changed);
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  return { fixture, changed };
}

describe('PrintersCard', () => {
  it('says there are no printers, and where the first one comes from', async () => {
    const { fixture } = await render({}, []);
    expect(el(fixture, '[data-testid="print-printers-empty"]').textContent).toContain(
      'No printers yet',
    );
  });

  it('lists a printer with its roll, its agent and the status the agent last reported', async () => {
    const { fixture } = await render({}, [printer()]);
    const row = el(fixture, '[data-printer="printer-1"]');
    expect(row.textContent).toContain('Grill pass');
    expect(row.textContent).toContain('80 mm');
    expect(row.textContent).toContain('Pilot branch mini PC');
    expect(row.textContent).toContain('grill');
    expect(el(fixture, '[data-testid="print-printer-status-printer-1"]').textContent).toContain(
      'Online',
    );
  });

  it('registers a browser printer without an agent and tells the page to read again', async () => {
    const registerPrinter = vi.fn().mockResolvedValue(printer());
    const { fixture, changed } = await render({ registerPrinter }, []);

    await click(fixture, '[data-testid="print-printer-add"]');
    type(fixture, '[data-testid="print-printer-name"]', ' Counter ');
    pick(fixture, '[data-testid="print-printer-tape"]', '58');
    await click(fixture, '[data-testid="print-printer-save"]');

    expect(registerPrinter).toHaveBeenCalledWith(SCOPE, {
      displayName: 'Counter',
      tapeWidthMm: 58,
      agentDeviceId: null,
      agentPrinterKey: null,
    });
    expect(changed).toHaveBeenCalledOnce();
  });

  it('binds an agent printer to an alias the agent advertises, and to nothing it does not', async () => {
    const registerPrinter = vi.fn().mockResolvedValue(printer());
    const { fixture } = await render({ registerPrinter }, []);

    await click(fixture, '[data-testid="print-printer-add"]');
    type(fixture, '[data-testid="print-printer-name"]', 'Grill');
    pick(fixture, '[data-testid="print-printer-kind"]', 'AGENT');
    // One active agent is chosen for the manager, and so is its one advertised alias.
    expect(el<HTMLSelectElement>(fixture, '[data-testid="print-printer-agent"]').value).toBe(
      'agent-1',
    );
    expect(el<HTMLSelectElement>(fixture, '[data-testid="print-printer-alias"]').value).toBe(
      'grill',
    );
    expect(
      Array.from(el<HTMLSelectElement>(fixture, '[data-testid="print-printer-alias"]').options).map(
        (option) => option.value,
      ),
    ).toEqual(['', 'grill']);

    await click(fixture, '[data-testid="print-printer-save"]');
    expect(registerPrinter).toHaveBeenCalledWith(SCOPE, {
      displayName: 'Grill',
      tapeWidthMm: 80,
      agentDeviceId: 'agent-1',
      agentPrinterKey: 'grill',
    });
  });

  it('cannot save an agent printer until an alias is chosen, and says when there is no agent', async () => {
    const { fixture } = await render({}, [], []);
    await click(fixture, '[data-testid="print-printer-add"]');
    type(fixture, '[data-testid="print-printer-name"]', 'Grill');
    pick(fixture, '[data-testid="print-printer-kind"]', 'AGENT');

    expect(el(fixture, '[data-testid="print-printer-no-agent"]').textContent).toContain(
      'No print agent is approved',
    );
    expect(el<HTMLButtonElement>(fixture, '[data-testid="print-printer-save"]').disabled).toBe(
      true,
    );
  });

  it('retires a printer against the version it was read at', async () => {
    const updatePrinter = vi.fn().mockResolvedValue(printer({ status: 'RETIRED' }));
    const { fixture, changed } = await render({ updatePrinter }, [printer({ version: 7 })]);

    await click(fixture, '[data-testid="print-printer-retire-printer-1"]');

    expect(updatePrinter).toHaveBeenCalledWith(
      SCOPE,
      'printer-1',
      {
        displayName: 'Grill pass',
        tapeWidthMm: 80,
        agentDeviceId: 'agent-1',
        agentPrinterKey: 'grill',
      },
      'RETIRED',
      7,
    );
    expect(changed).toHaveBeenCalledOnce();
  });

  it('prints the test page of a browser printer through the browser at its roll width', async () => {
    const testPage = vi
      .fn()
      .mockResolvedValue(job({ id: 'job-t', status: 'HANDED_OFF', documentType: 'TEST_PAGE' }));
    const picture = new Blob(['png']);
    const preview = vi.fn().mockResolvedValue(picture);
    const print = vi.fn().mockResolvedValue(undefined);
    const { fixture } = await render(
      { testPage, preview },
      [browserPrinter({ tapeWidthMm: 58 })],
      [],
      { print },
    );

    await click(fixture, '[data-testid="print-printer-test-printer-browser"]');

    expect(testPage).toHaveBeenCalledWith(SCOPE, 'printer-browser');
    expect(preview).toHaveBeenCalledWith(SCOPE, { jobId: 'job-t' });
    expect(print).toHaveBeenCalledWith(picture, 58);
  });

  it('queues the test page of an agent printer and does not open a print dialog', async () => {
    const testPage = vi
      .fn()
      .mockResolvedValue(job({ id: 'job-t', status: 'QUEUED', documentType: 'TEST_PAGE' }));
    const preview = vi.fn();
    const print = vi.fn();
    const { fixture } = await render({ testPage, preview }, [printer()], [agent()], { print });

    await click(fixture, '[data-testid="print-printer-test-printer-1"]');

    expect(preview).not.toHaveBeenCalled();
    expect(print).not.toHaveBeenCalled();
    expect(el(fixture, '[data-testid="print-printers-notice"]').textContent).toContain('queued');
  });

  it('says plainly that printing is not switched on when the platform refuses a write', async () => {
    const { ApiError, ApiErrorCode } = await import('../../../core/api/problem-details');
    const registerPrinter = vi
      .fn()
      .mockRejectedValue(
        new ApiError(
          ApiErrorCode.UNPROCESSABLE_STATE,
          422,
          { status: 422, code: ApiErrorCode.UNPROCESSABLE_STATE, reason: 'PRINTING_OFF' },
          null,
        ),
      );
    const { fixture } = await render({ registerPrinter }, []);
    await click(fixture, '[data-testid="print-printer-add"]');
    type(fixture, '[data-testid="print-printer-name"]', 'Counter');
    await click(fixture, '[data-testid="print-printer-save"]');

    expect(el(fixture, '[data-testid="print-printers-error"]').textContent).toContain(
      'not switched on',
    );
  });
});
