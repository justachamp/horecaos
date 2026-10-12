import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { FiscalReceiptCard } from './fiscal-receipt-card';
import { el } from './printing-dom';
import { printer, route } from './printing-fixtures';
import { RouteView } from './printing-api';

async function render(routes: readonly RouteView[]): Promise<ComponentFixture<FiscalReceiptCard>> {
  await TestBed.configureTestingModule({ imports: [FiscalReceiptCard] }).compileComponents();
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(FiscalReceiptCard);
  fixture.componentRef.setInput('routes', routes);
  fixture.componentRef.setInput('printers', [printer()]);
  fixture.detectChanges();
  return fixture;
}

describe('FiscalReceiptCard', () => {
  it('states what is printed and that the order summary is never a receipt', async () => {
    const fixture = await render([]);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('exactly as the fiscal operator returned them');
    expect(text).toContain('not a fiscal document');
    expect(text).toContain('marked as a copy');
    expect(el(fixture, '[data-testid="print-fiscal-none"]')).not.toBeNull();
    expect(el(fixture, '[data-testid="print-fiscal-caution"]')).toBeNull();
  });

  it('lists the branch’s fiscal receipt routes and cautions about the legal wording only when one is automatic', async () => {
    const manual = await render([
      route({ id: 'r1', documentType: 'FISCAL_RECEIPT', autoPrint: false }),
    ]);
    expect(el(manual, '[data-testid="print-fiscal-routes"]').textContent).toContain(
      'Goes to Grill pass when someone asks',
    );
    expect(el(manual, '[data-testid="print-fiscal-caution"]')).toBeNull();

    TestBed.resetTestingModule();
    const automatic = await render([
      route({ id: 'r1', documentType: 'FISCAL_RECEIPT', autoPrint: true }),
    ]);
    expect(el(automatic, '[data-testid="print-fiscal-routes"]').textContent).toContain(
      'Prints by itself on Grill pass',
    );
    expect(el(automatic, '[data-testid="print-fiscal-caution"]').textContent).toContain(
      'ask your accountant',
    );
  });

  it('ignores an inactive route and routes for other documents', async () => {
    const fixture = await render([
      route({ id: 'r1', documentType: 'FISCAL_RECEIPT', autoPrint: true, status: 'INACTIVE' }),
      route({ id: 'r2', documentType: 'ORDER_SLIP', autoPrint: true }),
    ]);
    expect(el(fixture, '[data-testid="print-fiscal-none"]')).not.toBeNull();
    expect(el(fixture, '[data-testid="print-fiscal-caution"]')).toBeNull();
  });
});
