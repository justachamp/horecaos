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

  it('shows "no code issued" rather than a blank card when no token was ever supplied', () => {
    const host = render({ qrToken: null });

    expect(host.querySelector('[data-testid="table-print-card-mark"]')).toBeNull();
    expect(host.querySelector('[data-testid="table-print-card-token"]')).toBeNull();
    expect(host.querySelector('[data-testid="table-print-card-none"]')?.textContent).toContain(
      'No code issued for this table yet.',
    );
  });
});
