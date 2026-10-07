import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { QQrCode } from './qr-code';
import { QR_MAX_BYTE_CAPACITY } from './qr-encode';

function render(value: string, label: string | null = null) {
  const fixture = TestBed.createComponent(QQrCode);
  fixture.componentRef.setInput('value', value);
  fixture.componentRef.setInput('label', label);
  fixture.componentRef.setInput('tooLongText', 'Too long for a QR code');
  fixture.detectChanges();
  return fixture;
}

describe('QQrCode (control plane copy)', () => {
  it('renders an SVG for a payload that fits, labelled for a screen reader when given a label', async () => {
    await TestBed.configureTestingModule({ imports: [QQrCode] }).compileComponents();
    const fixture = render('otpauth://totp/HorecaOS?secret=ABCDEFGH&issuer=HorecaOS', 'Scan me');

    const svg = fixture.nativeElement.querySelector('[data-testid="qr-code-svg"]');
    expect(svg).not.toBeNull();
    expect(svg.getAttribute('aria-label')).toBe('Scan me');
    expect(fixture.nativeElement.querySelectorAll('rect').length).toBeGreaterThan(50);
  });

  it('says so, in the caller’s words, when the payload does not fit, and renders nothing for an empty one', async () => {
    await TestBed.configureTestingModule({ imports: [QQrCode] }).compileComponents();

    const tooLong = render('x'.repeat(QR_MAX_BYTE_CAPACITY + 1));
    expect(
      tooLong.nativeElement.querySelector('[data-testid="qr-code-too-long"]')?.textContent,
    ).toContain('Too long for a QR code');

    const empty = render('   ');
    expect(empty.nativeElement.querySelector('svg')).toBeNull();
    expect(empty.nativeElement.querySelector('[data-testid="qr-code-too-long"]')).toBeNull();
  });
});
