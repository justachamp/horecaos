import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { PrintingApi } from './printing-api';
import { check, click, el, flush, type } from './printing-dom';
import { profile } from './printing-fixtures';
import { ReceiptProfileCard } from './receipt-profile-card';

const BRAND: BrandScope = { tenantId: 't1', brandId: 'b1' };

async function render(api: Partial<PrintingApi>): Promise<ComponentFixture<ReceiptProfileCard>> {
  await TestBed.configureTestingModule({
    imports: [ReceiptProfileCard],
    providers: [{ provide: PrintingApi, useValue: api }],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(ReceiptProfileCard);
  fixture.componentRef.setInput('scope', BRAND);
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  return fixture;
}

describe('ReceiptProfileCard', () => {
  it('says what cannot be changed and offers only the logo and the footer', async () => {
    const fixture = await render({ receiptProfile: vi.fn().mockResolvedValue(profile()) });

    expect(el(fixture, '[data-testid="print-profile-fixed"]').textContent).toContain(
      'cannot be moved, resized or removed',
    );
    expect(el(fixture, '[data-testid="print-profile-logo"]')).not.toBeNull();
    // Three lines in each of the three languages, and no other field.
    expect(
      (fixture.nativeElement as HTMLElement).querySelectorAll('input[type="text"]'),
    ).toHaveLength(9);
    expect(el<HTMLButtonElement>(fixture, '[data-testid="print-profile-save"]').disabled).toBe(
      true,
    );
  });

  it('shows the stored footer and saves only what changed, trimmed, against the stored version', async () => {
    const saveReceiptProfile = vi
      .fn()
      .mockResolvedValue(
        profile({ printLogo: true, version: 4, footerTexts: { ru: ['Спасибо'] } }),
      );
    const fixture = await render({
      receiptProfile: vi
        .fn()
        .mockResolvedValue(
          profile({ version: 3, footerTexts: { ru: ['Спасибо'], en: ['Thanks'] } }),
        ),
      saveReceiptProfile,
    });
    expect(el<HTMLInputElement>(fixture, '[data-testid="print-profile-footer-ru-0"]').value).toBe(
      'Спасибо',
    );

    check(fixture, '[data-testid="print-profile-logo"]', true);
    type(fixture, '[data-testid="print-profile-footer-en-0"]', '');
    type(fixture, '[data-testid="print-profile-footer-uz-Latn-1"]', '  Rahmat  ');
    await click(fixture, '[data-testid="print-profile-save"]');

    // An emptied line and an untouched language are left out; the new line is trimmed.
    expect(saveReceiptProfile).toHaveBeenCalledWith(
      BRAND,
      { printLogo: true, footerTexts: { ru: ['Спасибо'], 'uz-Latn': ['Rahmat'] } },
      3,
    );
    expect(el(fixture, '[data-testid="print-profile-notice"]').textContent).toContain('Saved');
  });

  it('keeps a footer line to the width of an 80 mm roll', async () => {
    const fixture = await render({ receiptProfile: vi.fn().mockResolvedValue(profile()) });
    expect(
      el<HTMLInputElement>(fixture, '[data-testid="print-profile-footer-ru-0"]').maxLength,
    ).toBe(48);
  });

  it('reads the profile again when someone else saved first, so the next save is against what is stored', async () => {
    const receiptProfile = vi
      .fn()
      .mockResolvedValueOnce(profile({ version: 1 }))
      .mockResolvedValueOnce(profile({ version: 2, printLogo: true }));
    const saveReceiptProfile = vi
      .fn()
      .mockRejectedValue(
        new ApiError(
          ApiErrorCode.STALE_VERSION,
          412,
          { status: 412, code: ApiErrorCode.STALE_VERSION, expected: 1, actual: 2 },
          null,
        ),
      );
    const fixture = await render({ receiptProfile, saveReceiptProfile });

    type(fixture, '[data-testid="print-profile-footer-ru-0"]', 'Привет');
    await click(fixture, '[data-testid="print-profile-save"]');

    expect(receiptProfile).toHaveBeenCalledTimes(2);
    expect(el(fixture, '[data-testid="print-profile-error"]')).not.toBeNull();
    expect(el<HTMLInputElement>(fixture, '[data-testid="print-profile-logo"]').checked).toBe(true);
  });
});
