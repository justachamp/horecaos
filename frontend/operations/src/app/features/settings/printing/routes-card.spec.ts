import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { I18n } from '../../../core/i18n/i18n';
import { StationResponse } from '../../kitchen/kitchen-api';
import { PrinterView, PrintingApi, RouteView } from './printing-api';
import { check, click, el, flush, pick } from './printing-dom';
import { browserPrinter, printer, route, station } from './printing-fixtures';
import { RoutesCard } from './routes-card';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

interface Inputs {
  printers?: readonly PrinterView[];
  routes?: readonly RouteView[];
  stations?: readonly StationResponse[];
  posMayPrint?: boolean;
}

async function render(
  api: Partial<PrintingApi>,
  inputs: Inputs = {},
): Promise<{ fixture: ComponentFixture<RoutesCard>; changed: ReturnType<typeof vi.fn> }> {
  await TestBed.configureTestingModule({
    imports: [RoutesCard],
    providers: [{ provide: PrintingApi, useValue: api }],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(RoutesCard);
  fixture.componentRef.setInput('scope', SCOPE);
  fixture.componentRef.setInput('printers', inputs.printers ?? [printer()]);
  fixture.componentRef.setInput('routes', inputs.routes ?? []);
  fixture.componentRef.setInput('stations', inputs.stations ?? [station()]);
  fixture.componentRef.setInput('posMayPrint', inputs.posMayPrint ?? false);
  const changed = vi.fn();
  fixture.componentInstance.changed.subscribe(changed);
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  return { fixture, changed };
}

describe('RoutesCard', () => {
  it('says that a branch with no routes prints nothing', async () => {
    const { fixture } = await render({}, { routes: [] });
    expect(el(fixture, '[data-testid="print-routes-empty"]').textContent).toContain(
      'nothing prints at this branch',
    );
  });

  it('cannot add a route before there is a printer, and says why', async () => {
    const { fixture } = await render({}, { printers: [] });
    expect(el(fixture, '[data-testid="print-routes-no-printer"]')).not.toBeNull();
    expect(el<HTMLButtonElement>(fixture, '[data-testid="print-route-add"]').disabled).toBe(true);
  });

  it('warns that the point of sale may already print, beside the kitchen-ticket route', async () => {
    const { fixture } = await render(
      {},
      {
        routes: [route(), route({ id: 'route-2', documentType: 'ORDER_SLIP' })],
        posMayPrint: true,
      },
    );
    const kitchenRow = el(fixture, '[data-route="route-1"]');
    const slipRow = el(fixture, '[data-route="route-2"]');
    expect(kitchenRow.textContent).toContain('Your point of sale may already print this');
    expect(slipRow.textContent).not.toContain('point of sale');
  });

  it('shows the warning above the table while there is still no kitchen-ticket route, and creates no route itself', async () => {
    const createRoute = vi.fn();
    const { fixture } = await render({ createRoute }, { routes: [], posMayPrint: true });
    expect(el(fixture, '[data-testid="print-routes-pos-note"]').textContent).toContain(
      'may already print this',
    );
    expect(createRoute).not.toHaveBeenCalled();
  });

  it('shows no warning when the till does not receive orders', async () => {
    const { fixture } = await render({}, { routes: [route()], posMayPrint: false });
    expect(el(fixture, '[data-route="route-1"]').textContent).not.toContain('point of sale');
    expect(el(fixture, '[data-testid="print-routes-pos-note"]')).toBeNull();
  });

  it('creates a station route on an agent printer, automatic, with two copies', async () => {
    const createRoute = vi.fn().mockResolvedValue(route());
    const { fixture, changed } = await render({ createRoute }, { routes: [] });

    await click(fixture, '[data-testid="print-route-add"]');
    pick(fixture, '[data-testid="print-route-station"]', 'station-grill');
    pick(fixture, '[data-testid="print-route-copies"]', '2');
    check(fixture, '[data-testid="print-route-auto"]', true);
    await click(fixture, '[data-testid="print-route-save"]');

    expect(createRoute).toHaveBeenCalledWith(SCOPE, {
      documentType: 'KITCHEN_TICKET',
      stationId: 'station-grill',
      printerId: 'printer-1',
      copies: 2,
      autoPrint: true,
      status: 'ACTIVE',
    });
    expect(changed).toHaveBeenCalledOnce();
  });

  it('cannot make a route automatic when its printer is a browser', async () => {
    const createRoute = vi.fn().mockResolvedValue(route());
    const { fixture } = await render({ createRoute }, { printers: [browserPrinter()] });

    await click(fixture, '[data-testid="print-route-add"]');
    expect(el<HTMLInputElement>(fixture, '[data-testid="print-route-auto"]').disabled).toBe(true);
    await click(fixture, '[data-testid="print-route-save"]');

    expect(createRoute).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({ printerId: 'printer-browser', autoPrint: false }),
    );
  });

  it('drops the automatic switch when the printer is changed to a browser one after it was ticked', async () => {
    const createRoute = vi.fn().mockResolvedValue(route());
    const { fixture } = await render(
      { createRoute },
      { printers: [printer(), browserPrinter()], routes: [] },
    );

    await click(fixture, '[data-testid="print-route-add"]');
    pick(fixture, '[data-testid="print-route-printer"]', 'printer-1');
    check(fixture, '[data-testid="print-route-auto"]', true);
    pick(fixture, '[data-testid="print-route-printer"]', 'printer-browser');

    const auto = el<HTMLInputElement>(fixture, '[data-testid="print-route-auto"]');
    expect(auto.disabled).toBe(true);
    expect(auto.checked).toBe(false);
    await click(fixture, '[data-testid="print-route-save"]');
    expect(createRoute).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({ printerId: 'printer-browser', autoPrint: false }),
    );
  });

  it('sends no station for a document that has none', async () => {
    const createRoute = vi.fn().mockResolvedValue(route());
    const { fixture } = await render({ createRoute }, { routes: [] });

    await click(fixture, '[data-testid="print-route-add"]');
    pick(fixture, '[data-testid="print-route-station"]', 'station-grill');
    pick(fixture, '[data-testid="print-route-document"]', 'ORDER_SLIP');
    expect(el(fixture, '[data-testid="print-route-station"]')).toBeNull();
    await click(fixture, '[data-testid="print-route-save"]');

    expect(createRoute).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({ documentType: 'ORDER_SLIP', stationId: null }),
    );
  });

  it('changes a route against the version it was read at', async () => {
    const updateRoute = vi.fn().mockResolvedValue(route());
    const { fixture } = await render(
      { updateRoute },
      { routes: [route({ version: 5, copies: 1 })] },
    );

    await click(fixture, '[data-route="route-1"] .link');
    pick(fixture, '[data-testid="print-route-copies"]', '3');
    await click(fixture, '[data-testid="print-route-save"]');

    expect(updateRoute).toHaveBeenCalledWith(
      SCOPE,
      'route-1',
      expect.objectContaining({ copies: 3, documentType: 'KITCHEN_TICKET' }),
      5,
    );
  });

  it('deletes a route only on the second press', async () => {
    const deleteRoute = vi.fn().mockResolvedValue(undefined);
    const { fixture, changed } = await render({ deleteRoute }, { routes: [route({ version: 3 })] });

    await click(fixture, '[data-testid="print-route-delete-route-1"]');
    expect(deleteRoute).not.toHaveBeenCalled();
    await click(fixture, '[data-testid="print-route-delete-confirm-route-1"]');

    expect(deleteRoute).toHaveBeenCalledWith(SCOPE, 'route-1', 3);
    expect(changed).toHaveBeenCalledOnce();
  });
});
