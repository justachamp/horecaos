import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { LocalizedFieldGroup } from './localized-field-group';

function render(): ReturnType<typeof TestBed.createComponent<LocalizedFieldGroup>> {
  const fixture = TestBed.createComponent(LocalizedFieldGroup);
  fixture.componentRef.setInput('locales', ['ru', 'uz', 'en']);
  fixture.componentRef.setInput('activeLocale', 'ru');
  fixture.detectChanges();
  return fixture;
}

function tab(
  fixture: ReturnType<typeof TestBed.createComponent<LocalizedFieldGroup>>,
  locale: string,
): HTMLElement {
  return (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
    `[data-testid="q-localized-field-group-tab-${locale}"]`,
  )!;
}

describe('LocalizedFieldGroup', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    // The UI itself is running in Russian throughout this suite — the whole
    // point of the test below is that the marker ignores this.
    TestBed.inject(I18n).setLocale('ru');
  });

  it('renders one tab per given locale', () => {
    const fixture = render();

    expect(tab(fixture, 'ru')).not.toBeNull();
    expect(tab(fixture, 'uz')).not.toBeNull();
    expect(tab(fixture, 'en')).not.toBeNull();
  });

  it('marks the active tab', () => {
    const fixture = render();

    expect(tab(fixture, 'ru').getAttribute('aria-selected')).toBe('true');
    expect(tab(fixture, 'uz').getAttribute('aria-selected')).toBe('false');
  });

  it('emits activeLocaleChange when a tab is clicked', () => {
    const fixture = render();
    let emitted: string | undefined;
    fixture.componentInstance.activeLocaleChange.subscribe((l) => (emitted = l));

    tab(fixture, 'en').click();

    expect(emitted).toBe('en');
  });

  it("puts the default marker on the entity's default locale (uz), even though the UI locale is ru", () => {
    const fixture = render();
    fixture.componentRef.setInput('defaultLocale', 'uz');
    fixture.detectChanges();

    expect(
      tab(fixture, 'uz').querySelector('[data-testid="q-localized-field-group-default-marker"]'),
    ).not.toBeNull();
    expect(
      tab(fixture, 'ru').querySelector('[data-testid="q-localized-field-group-default-marker"]'),
    ).toBeNull();
  });

  it('shows no default marker at all when none is given', () => {
    const fixture = render();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="q-localized-field-group-default-marker"]',
      ),
    ).toBeNull();
  });

  it('shows a completeness dot only for locales the caller reported on', () => {
    const fixture = render();
    fixture.componentRef.setInput('completeness', { ru: true, uz: false });
    fixture.detectChanges();

    expect(
      tab(fixture, 'ru').querySelector('[data-testid="q-localized-field-group-complete"]'),
    ).not.toBeNull();
    expect(
      tab(fixture, 'uz').querySelector('[data-testid="q-localized-field-group-incomplete"]'),
    ).not.toBeNull();
    // `en` was never reported on — unknown, not incomplete, so no dot at all.
    expect(
      tab(fixture, 'en').querySelector('[data-testid="q-localized-field-group-complete"]'),
    ).toBeNull();
    expect(
      tab(fixture, 'en').querySelector('[data-testid="q-localized-field-group-incomplete"]'),
    ).toBeNull();
  });
});
