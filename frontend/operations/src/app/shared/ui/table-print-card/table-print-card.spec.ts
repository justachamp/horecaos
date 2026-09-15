import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { TablePrintCard } from './table-print-card';

describe('TablePrintCard', () => {
  let fixture: ComponentFixture<TablePrintCard>;

  function render(
    inputs: {
      branchName?: string;
      tableCode?: string;
      tableDisplayName?: string;
      qrToken?: string | null;
    } = {},
  ): HTMLElement {
    fixture = TestBed.createComponent(TablePrintCard);
    TestBed.inject(I18n).setLocale('en');
    fixture.componentRef.setInput('branchName', inputs.branchName ?? 'Chilonzor');
    fixture.componentRef.setInput('tableCode', inputs.tableCode ?? 'T7');
    fixture.componentRef.setInput('tableDisplayName', inputs.tableDisplayName ?? 'Table 7');
    fixture.componentRef.setInput('qrToken', inputs.qrToken ?? null);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('renders the branch and table identity', () => {
    const host = render();
    expect(host.textContent).toContain('Chilonzor');
    expect(host.textContent).toContain('Table 7');
    expect(host.textContent).toContain('T7');
  });

  it('shows the printable mark and the plaintext token exactly once, when one is supplied', () => {
    const host = render({ qrToken: 'tok_live_abc123' });

    expect(host.querySelector('[data-testid="table-print-card-mark"]')).not.toBeNull();
    const tokenNode = host.querySelector('[data-testid="table-print-card-token"]');
    expect(tokenNode?.textContent).toContain('tok_live_abc123');
    expect(host.querySelector('[data-testid="table-print-card-none"]')).toBeNull();
  });

  it('renders the token as a real scannable q-qr-code, not a decorative placeholder', () => {
    // Row X.35's q-qr-code (P17) landed in this same integration, and the
    // card's own doc says wiring it up is the entire migration — this is the
    // regression test for that wiring actually having been done: a decorative
    // `card__markGrid` of shaded squares would satisfy every other assertion
    // in this file (it also sits inside `table-print-card-mark`) without ever
    // encoding the token into a scannable code.
    const host = render({ qrToken: 'tok_live_abc123' });

    const qrCode = host.querySelector('[data-testid="table-print-card-qr"]');
    expect(qrCode).not.toBeNull();
    const svg = qrCode?.querySelector('[data-testid="qr-code-svg"]');
    expect(svg).not.toBeNull();
    expect(svg?.getAttribute('aria-label')).toBe("This table's QR code");
  });

  it('shows "no code issued" rather than a blank card when no token was ever supplied', () => {
    const host = render({ qrToken: null });

    expect(host.querySelector('[data-testid="table-print-card-mark"]')).toBeNull();
    expect(host.querySelector('[data-testid="table-print-card-token"]')).toBeNull();
    expect(host.querySelector('[data-testid="table-print-card-none"]')?.textContent).toContain(
      'No code issued for this table yet.',
    );
  });
});
