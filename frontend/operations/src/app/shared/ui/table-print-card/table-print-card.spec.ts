import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { encodeQrMatrix } from '../qr-encode';
import { TablePrintCard, tableQrUrl } from './table-print-card';

/** The dark modules the rendered `q-qr-code` SVG actually draws, as sorted `row,col` pairs. */
function renderedModules(host: HTMLElement): string[] {
  const svg = host.querySelector('[data-testid="qr-code-svg"]');
  return Array.from(svg?.querySelectorAll('rect[width="1"]') ?? [])
    .map((cell) => `${cell.getAttribute('y')},${cell.getAttribute('x')}`)
    .sort();
}

/** The dark modules `encodeQrMatrix(text)` produces, in the same `row,col` form. */
function expectedModules(text: string): string[] {
  const { modules } = encodeQrMatrix(text);
  const cells: string[] = [];
  modules.forEach((row, rowIndex) =>
    row.forEach((dark, colIndex) => {
      if (dark) {
        cells.push(`${rowIndex},${colIndex}`);
      }
    }),
  );
  return cells.sort();
}

describe('TablePrintCard', () => {
  let fixture: ComponentFixture<TablePrintCard>;

  function render(
    inputs: {
      branchName?: string;
      tableCode?: string;
      tableDisplayName?: string;
      qrToken?: string | null;
      storefrontHostname?: string | null;
    } = {},
  ): HTMLElement {
    fixture = TestBed.createComponent(TablePrintCard);
    TestBed.inject(I18n).setLocale('en');
    fixture.componentRef.setInput('branchName', inputs.branchName ?? 'Chilonzor');
    fixture.componentRef.setInput('tableCode', inputs.tableCode ?? 'T7');
    fixture.componentRef.setInput('tableDisplayName', inputs.tableDisplayName ?? 'Table 7');
    fixture.componentRef.setInput('qrToken', inputs.qrToken ?? null);
    fixture.componentRef.setInput('storefrontHostname', inputs.storefrontHostname ?? null);
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

  // Batch 14: the card used to encode only the raw token, which a phone
  // camera cannot open. It now encodes the absolute storefront address on the
  // tenant's verified hostname, and says so when it cannot.
  describe('the scan address', () => {
    it('encodes the absolute storefront URL on the verified hostname, not the bare token', () => {
      const host = render({
        qrToken: 'tok_live_abc123',
        storefrontHostname: 'acme.stores.horecaos.uz',
      });

      const url = 'https://acme.stores.horecaos.uz/dine-in/tok_live_abc123';
      expect(renderedModules(host)).toEqual(expectedModules(url));
      // The failure this guards: a card that renders a perfectly good QR of
      // the wrong payload. The bare-token symbol is a different set of modules.
      expect(renderedModules(host)).not.toEqual(expectedModules('tok_live_abc123'));
    });

    it('says which site a scan opens, and prints no warning', () => {
      const host = render({
        qrToken: 'tok_live_abc123',
        storefrontHostname: 'acme.stores.horecaos.uz',
      });

      expect(host.querySelector('[data-testid="table-print-card-host"]')?.textContent).toContain(
        'Scanning opens acme.stores.horecaos.uz',
      );
      expect(host.querySelector('[data-testid="table-print-card-no-host"]')).toBeNull();
      expect(host.querySelector('[data-testid="table-print-card-too-long"]')).toBeNull();
    });

    it('falls back to the bare token with a visible warning when no hostname is configured', () => {
      const host = render({ qrToken: 'tok_live_abc123', storefrontHostname: null });

      expect(renderedModules(host)).toEqual(expectedModules('tok_live_abc123'));
      const warning = host.querySelector('[data-testid="table-print-card-no-host"]');
      expect(warning).not.toBeNull();
      expect(warning?.getAttribute('role')).toBe('alert');
      expect(warning?.textContent).toContain('No verified storefront address');
      expect(host.querySelector('[data-testid="table-print-card-host"]')).toBeNull();
      // The token still prints as text underneath, for the scan-fails case.
      expect(host.querySelector('[data-testid="table-print-card-token"]')?.textContent).toContain(
        'tok_live_abc123',
      );
    });

    it('refuses to print a hostname that is not a DNS name, falling back the same way', () => {
      const host = render({ qrToken: 'tok_live_abc123', storefrontHostname: 'not a host/x' });

      expect(renderedModules(host)).toEqual(expectedModules('tok_live_abc123'));
      expect(host.querySelector('[data-testid="table-print-card-no-host"]')).not.toBeNull();
    });

    it('falls back with its own warning when the address will not fit in the QR symbol', () => {
      // 253 characters is a legal hostname (V0403) and far past the encoder's 106-byte ceiling.
      const longHost = `${'a'.repeat(60)}.${'b'.repeat(60)}.${'c'.repeat(60)}.${'d'.repeat(60)}.uz`;
      const host = render({ qrToken: 'tok_live_abc123', storefrontHostname: longHost });

      expect(host.querySelector('[data-testid="table-print-card-too-long"]')).not.toBeNull();
      expect(host.querySelector('[data-testid="table-print-card-no-host"]')).toBeNull();
      expect(host.querySelector('[data-testid="qr-code-too-long"]')).toBeNull();
      expect(renderedModules(host)).toEqual(expectedModules('tok_live_abc123'));
    });

    it('shows the warning in the operator language', () => {
      const host = render({ qrToken: 'tok_live_abc123' });
      TestBed.inject(I18n).setLocale('ru');
      fixture.detectChanges();

      expect(
        host.querySelector('[data-testid="table-print-card-no-host"]')?.textContent,
      ).toContain('Подтверждённый адрес витрины не настроен');
    });
  });
});

describe('tableQrUrl', () => {
  it('builds https://<hostname>/dine-in/<token>', () => {
    expect(tableQrUrl('acme.stores.horecaos.uz', 'abc_DEF-123')).toBe(
      'https://acme.stores.horecaos.uz/dine-in/abc_DEF-123',
    );
  });

  it('lowercases and trims the hostname, and percent-encodes anything unsafe in the token', () => {
    expect(tableQrUrl('  Orders.Acme.UZ ', 'a/b?c')).toBe(
      'https://orders.acme.uz/dine-in/a%2Fb%3Fc',
    );
  });

  it('is null for no hostname, no token, or a hostname that is not a DNS name', () => {
    expect(tableQrUrl(null, 'tok')).toBeNull();
    expect(tableQrUrl(undefined, 'tok')).toBeNull();
    expect(tableQrUrl('acme.uz', null)).toBeNull();
    expect(tableQrUrl('acme.uz', '')).toBeNull();
    expect(tableQrUrl('localhost', 'tok')).toBeNull();
    expect(tableQrUrl('acme.uz:8443', 'tok')).toBeNull();
    expect(tableQrUrl('https://acme.uz', 'tok')).toBeNull();
  });
});
