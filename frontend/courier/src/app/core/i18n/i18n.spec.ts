import { TestBed } from '@angular/core/testing';

import { I18n, interpolate } from './i18n';
import { localeFromBrowser, parseLocale } from './locales';

describe('I18n', () => {
  let i18n: I18n;

  beforeEach(async () => {
    localStorage.clear();
    TestBed.configureTestingModule({});
    i18n = TestBed.inject(I18n);
    await i18n.use('en');
  });

  afterEach(() => localStorage.clear());

  it('looks a message up in the active language', () => {
    expect(i18n.t('common.retry')).toBe('Try again');
  });

  it('switches language, and every area already in use follows', async () => {
    await i18n.require(['home']);
    expect(i18n.t('home.title')).toBe('Today');

    await i18n.use('ru');

    expect(i18n.t('home.title')).toBe('Сегодня');
    expect(i18n.t('common.retry')).toBe('Повторить');
    expect(i18n.htmlLang()).toBe('ru');
  });

  it('keeps an area out of memory until a route asks for it', () => {
    expect(i18n.has('home.title')).toBe(false);
    expect(i18n.has('common.retry')).toBe(true);
  });

  it('fills placeholders, and leaves a missing one visible rather than silently blank', () => {
    expect(interpolate('Hello, {name}', { name: 'Alisher' })).toBe('Hello, Alisher');
    expect(interpolate('Hello, {name}', {})).toBe('Hello, {name}');
    expect(interpolate('No placeholders', { name: 'x' })).toBe('No placeholders');
  });

  it('renders an unknown key as the key itself and reports it once', () => {
    const report = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    // A key outside the union is a compile error in a template; the cast is what a runtime-built key looks like.
    const first = i18n.t('nonexistent.key' as never);
    i18n.t('nonexistent.key' as never);

    expect(first).toBe('nonexistent.key');
    expect(report).toHaveBeenCalledTimes(1);
    report.mockRestore();
  });

  it('maps a runtime code to its message, or to the fallback when the catalogue has none', async () => {
    await i18n.require(['home']);

    expect(i18n.tOr('home.engagement.ACTIVE', 'error.generic')).toBe('Cleared to work');
    expect(i18n.tOr('home.engagement.SOMETHING_NEW', 'error.generic')).toBe(
      'Something went wrong. Please try again.',
    );
  });

  it('remembers the language and starts in it', async () => {
    await i18n.use('uz-Latn');
    TestBed.resetTestingModule();

    const fresh = TestBed.inject(I18n);
    await fresh.init();

    expect(fresh.locale()).toBe('uz-Latn');
    expect(fresh.t('common.retry')).toBe('Qayta urinish');
  });

  it("starts in the browser's language when nothing was remembered", async () => {
    localStorage.clear();
    TestBed.resetTestingModule();
    vi.spyOn(globalThis.navigator, 'languages', 'get').mockReturnValue(['uz-UZ', 'en']);

    const fresh = TestBed.inject(I18n);
    await fresh.init();

    expect(fresh.locale()).toBe('uz-Latn');
    vi.restoreAllMocks();
  });

  it('still works in a browser that keeps no storage', async () => {
    const original = Storage.prototype.setItem;
    Storage.prototype.setItem = () => {
      throw new DOMException('blocked', 'SecurityError');
    };
    try {
      await expect(i18n.use('ru')).resolves.toBeUndefined();
    } finally {
      Storage.prototype.setItem = original;
    }

    expect(i18n.locale()).toBe('ru');
  });
});

describe('locales', () => {
  it('accepts exactly our three codes from storage', () => {
    expect(parseLocale('ru')).toBe('ru');
    expect(parseLocale('uz-Latn')).toBe('uz-Latn');
    expect(parseLocale('en')).toBe('en');
    expect(parseLocale('uz')).toBeNull();
    expect(parseLocale('<script>')).toBeNull();
    expect(parseLocale(null)).toBeNull();
  });

  it('maps any Uzbek to the Latin catalogue, the only Uzbek one there is', () => {
    expect(localeFromBrowser(['uz-Cyrl-UZ'])).toBe('uz-Latn');
    expect(localeFromBrowser(['uz'])).toBe('uz-Latn');
  });

  it('takes the first language we speak, and Russian when we speak none', () => {
    expect(localeFromBrowser(['de-DE', 'en-GB', 'ru'])).toBe('en');
    expect(localeFromBrowser(['de-DE'])).toBe('ru');
    expect(localeFromBrowser([])).toBe('ru');
  });
});
