import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { QQrCode } from './qr-code';
import { QR_MAX_BYTE_CAPACITY } from './qr-encode';

describe('QQrCode', () => {
  let fixture: ComponentFixture<QQrCode>;

  async function render(value: string, size?: number): Promise<void> {
    await TestBed.configureTestingModule({ imports: [QQrCode] }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(QQrCode);
    fixture.componentRef.setInput('value', value);
    if (size !== undefined) {
      fixture.componentRef.setInput('size', size);
    }
    fixture.detectChanges();
  }

  it('renders an SVG bitmap for the payments qrPayload field (payments-api.ts:93)', async () => {
    // A realistic PaymentSessionView.qrPayload — a provider checkout URL.
    await render('https://pay.click.uz/services/pay?merchant_id=12345&amount=50000');

    const host = fixture.nativeElement as HTMLElement;
    const svg = host.querySelector('[data-testid="qr-code-svg"]') as SVGElement | null;
    expect(svg).not.toBeNull();
    expect(svg?.querySelectorAll('rect').length).toBeGreaterThan(1);
  });

  it('sizes the rendered box from the size input', async () => {
    await render('device-pairing-code', 220);

    const svg = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="qr-code-svg"]',
    ) as HTMLElement;
    expect(svg.style.width).toBe('220px');
    expect(svg.style.height).toBe('220px');
  });

  it('renders nothing for an empty value rather than an error', async () => {
    await render('');

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="qr-code-svg"]')).toBeNull();
    expect(host.querySelector('[data-testid="qr-code-too-long"]')).toBeNull();
  });

  it('shows a text fallback rather than a wrong code past the 106-byte ceiling', async () => {
    await render('a'.repeat(QR_MAX_BYTE_CAPACITY + 1));

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="qr-code-svg"]')).toBeNull();
    expect(host.querySelector('[data-testid="qr-code-too-long"]')).not.toBeNull();
  });

  it('re-renders a different bitmap when the value input changes', async () => {
    await render('AAAAAAAA');
    const first = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="qr-code-svg"]',
    )?.innerHTML;

    fixture.componentRef.setInput('value', 'BBBBBBBB');
    fixture.detectChanges();
    const second = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="qr-code-svg"]',
    )?.innerHTML;

    expect(first).not.toBe(second);
  });

  it('carries an accessible label only when one is given, never the raw payload as the label', async () => {
    await render('secret-looking-payload');
    const host = fixture.nativeElement as HTMLElement;
    expect(
      host.querySelector('[data-testid="qr-code-svg"]')?.getAttribute('aria-label'),
    ).toBeNull();

    fixture.componentRef.setInput('label', 'Scan to pair this device');
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="qr-code-svg"]')?.getAttribute('aria-label')).toBe(
      'Scan to pair this device',
    );
  });
});
