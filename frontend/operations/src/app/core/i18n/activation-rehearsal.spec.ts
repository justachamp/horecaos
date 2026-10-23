import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { REGISTRY_FIXTURE } from '../../../testing/platform-locales.fixture';
import { documentLocales } from '../../features/settings/printing/document-locales';
import { FakeBrandLocales, profile } from '../../features/settings/printing/printing-fixtures';
import { PrintingApi } from '../../features/settings/printing/printing-api';
import { ReceiptProfileCard } from '../../features/settings/printing/receipt-profile-card';
import { I18n } from './i18n';
import { LocaleSet, resolveLocaleSet } from './locale-set';
import { localeDisplayName, platformLocaleSet } from './locale-labels';
import {
  PlatformLocaleEntry,
  PlatformLocales,
  seedPlatformLocalesForTesting,
} from './platform-locales';

/**
 * ADR 0149's exit criterion, rehearsed in the console: declaring a language live in a tier is a change
 * to the registry's entry, and the pickers, the brand's set, the document languages and the screens
 * that were made registry-driven follow with no change to any of them.
 *
 * No language is activated, so the claim has never been seen to hold. This seeds a synthetic fifth
 * language live in the content and messages tiers (and not in the staff-interface tier: the console
 * has no catalogue for it, which is the one thing it must add) and checks each reader. The backend's
 * `ActivationRehearsalTests` rehearses the schema and the template requirement.
 */
describe('activating a fifth language (ADR 0149 exit criteria)', () => {
  const FIFTH: PlatformLocaleEntry = {
    tag: 'zz',
    catalogCode: 'zz',
    inputAliases: [],
    script: 'LATN',
    direction: 'LTR',
    face: 'ibm-plex-sans',
    fallbackRank: 5,
    tiers: ['CONTENT', 'MESSAGES'],
    names: { ru: 'Пятый', en: 'Fifth', zz: 'Zzzz' },
  };

  beforeEach(() => {
    seedPlatformLocalesForTesting({
      ...REGISTRY_FIXTURE,
      locales: [...REGISTRY_FIXTURE.locales, FIFTH],
    });
  });

  afterEach(() => seedPlatformLocalesForTesting(REGISTRY_FIXTURE));

  it('is live in the tiers it was declared in and offered nowhere it was not', () => {
    const registry = TestBed.inject(PlatformLocales);

    expect(registry.active('CONTENT')).toEqual(['ru', 'uz-Latn', 'en', 'zz']);
    expect(registry.active('MESSAGES')).toEqual(['ru', 'uz-Latn', 'en', 'zz']);
    expect(registry.active('STAFF_UI')).toEqual(['ru', 'uz-Latn', 'en']);
    expect(registry.directionOf('zz')).toBe('ltr');
  });

  it('is what a brand that chose nothing is authored in, in every editor that reads the fallback', () => {
    const registry = TestBed.inject(PlatformLocales);

    expect(platformLocaleSet(registry).locales).toEqual(['ru', 'uz-Latn', 'en', 'zz']);
    expect(resolveLocaleSet(null, registry).locales).toEqual(['ru', 'uz-Latn', 'en', 'zz']);
    expect(
      resolveLocaleSet(
        [
          { locale: 'zz', isDefault: true },
          { locale: 'ru', isDefault: false },
        ],
        registry,
      ).locales,
    ).toEqual(['zz', 'ru']);
  });

  it('is named to an operator by the registry, in the console’s language, with no key of its own', () => {
    const registry = TestBed.inject(PlatformLocales);
    const i18n = TestBed.inject(I18n);
    i18n.setLocale('en');

    expect(localeDisplayName(i18n, 'zz', registry)).toBe('Fifth');
  });

  it('is a document language for a brand that serves it, and for no other', () => {
    const registry = TestBed.inject(PlatformLocales);

    expect(documentLocales(registry, ['zz', 'ru'])).toEqual(['zz', 'ru']);
    expect(documentLocales(registry, ['ru', 'en'])).toEqual(['ru', 'en']);
  });

  it('gives a brand that serves it a receipt footer in it, on a screen that was not touched for it', async () => {
    const brandLocales = new FakeBrandLocales();
    brandLocales.locales.set(['ru', 'zz']);
    await TestBed.configureTestingModule({
      imports: [ReceiptProfileCard],
      providers: [
        { provide: PrintingApi, useValue: { receiptProfile: () => Promise.resolve(profile()) } },
        { provide: LocaleSet, useValue: brandLocales },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(ReceiptProfileCard);
    fixture.componentRef.setInput('scope', { tenantId: 't1', brandId: 'b1' });
    fixture.detectChanges();
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();

    const fieldsets = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('fieldset.locale'),
    ).map((fieldset) => fieldset.getAttribute('data-locale'));
    expect(fieldsets).toEqual(['ru', 'zz']);
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('fieldset[data-locale="zz"] legend')
        ?.textContent,
    ).toContain('Fifth');
  });
});
