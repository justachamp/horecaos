import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../config/app-config';
import {
  DEFAULT_LOCALE,
  I18nService,
  LOCALES,
  STORAGE_KEY,
  peekStoredLocale,
  preloadLocale,
  resetCatalogueCacheForTesting,
  setLoaderForTesting,
} from './i18n.service';
import { en } from './messages.en';
import { ru } from './messages.ru';
import { uzLatn } from './messages.uz-latn';

/**
 * The loading model: `ru` ships in the initial bundle, `en` and `uz-Latn` are
 * fetched when somebody asks for them. The suite-wide setup file
 * (`src/testing/i18n-preload.setup.ts`) warms every locale so other specs can
 * switch and assert in one tick; these specs empty the cache to exercise the
 * paths that warm-up makes unreachable, and restore it afterwards because the
 * cache is process-global.
 */
function service(): I18nService {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '', displayTimeZone: 'Asia/Tashkent' } },
    ],
  });
  return TestBed.inject(I18nService);
}

/** A loader whose promise the spec settles by hand, and that counts its calls. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe('I18nService loading model', () => {
  const restores: Array<() => void> = [];

  beforeEach(() => {
    localStorage.clear();
    resetCatalogueCacheForTesting();
  });

  afterEach(async () => {
    while (restores.length > 0) {
      restores.pop()?.();
    }
    localStorage.clear();
    vi.restoreAllMocks();
    // Put the suite-wide warm cache back for every spec that runs after this one.
    resetCatalogueCacheForTesting();
    await Promise.all(LOCALES.map((locale) => preloadLocale(locale)));
  });

  it('starts in Russian with nothing fetched', () => {
    const i18n = service();

    expect(i18n.locale()).toBe('ru');
    expect(i18n.t('money.uzsSuffix')).toBe(ru['money.uzsSuffix']);
  });

  it('keeps showing the previous locale until the requested catalogue has arrived', async () => {
    const pending = deferred<typeof en>();
    restores.push(setLoaderForTesting('en', () => pending.promise));
    const i18n = service();

    i18n.use('en');

    expect(i18n.locale()).toBe('ru');
    expect(i18n.t('money.uzsSuffix')).toBe(ru['money.uzsSuffix']);
    expect(document.documentElement.lang).toBe('ru');

    pending.resolve(en);
    await vi.waitFor(() => expect(i18n.locale()).toBe('en'));
    expect(i18n.t('money.uzsSuffix')).toBe(en['money.uzsSuffix']);
    expect(document.documentElement.lang).toBe('en');
  });

  it('switches synchronously once the catalogue is cached', async () => {
    const i18n = service();
    await preloadLocale('uz-Latn');

    i18n.use('uz-Latn');

    expect(i18n.locale()).toBe('uz-Latn');
    expect(i18n.t('money.uzsSuffix')).toBe(uzLatn['money.uzsSuffix']);
  });

  it('fetches a catalogue once however many times it is asked for', async () => {
    const loader = vi.fn(() => Promise.resolve(en));
    restores.push(setLoaderForTesting('en', loader));
    const i18n = service();

    i18n.use('en');
    i18n.use('en');
    await vi.waitFor(() => expect(i18n.locale()).toBe('en'));
    i18n.use('ru');
    i18n.use('en');

    expect(loader).toHaveBeenCalledTimes(1);
    expect(i18n.locale()).toBe('en');
  });

  it('lets a later request supersede one still in flight', async () => {
    const slowEnglish = deferred<typeof en>();
    restores.push(setLoaderForTesting('en', () => slowEnglish.promise));
    const i18n = service();

    i18n.use('en');
    i18n.use('uz-Latn');
    await vi.waitFor(() => expect(i18n.locale()).toBe('uz-Latn'));

    slowEnglish.resolve(en);
    await preloadLocale('en');

    expect(i18n.locale()).toBe('uz-Latn');
    expect(i18n.t('money.uzsSuffix')).toBe(uzLatn['money.uzsSuffix']);
  });

  it('stays on the previous locale when the fetch fails, and tries again on the next request', async () => {
    const report = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    let attempt = 0;
    restores.push(
      setLoaderForTesting('en', () =>
        ++attempt === 1 ? Promise.reject(new Error('chunk fetch failed')) : Promise.resolve(en),
      ),
    );
    const i18n = service();

    i18n.use('en');
    await vi.waitFor(() => expect(report).toHaveBeenCalledOnce());
    expect(i18n.locale()).toBe('ru');

    i18n.use('en');
    await vi.waitFor(() => expect(i18n.locale()).toBe('en'));
    expect(attempt).toBe(2);
  });

  it('opens in Russian and follows to the stored locale when nothing has warmed it', async () => {
    localStorage.setItem(STORAGE_KEY, 'uz-Latn');

    const i18n = service();

    expect(i18n.locale()).toBe('ru');
    await vi.waitFor(() => expect(i18n.locale()).toBe('uz-Latn'));
    expect(i18n.t('money.uzsSuffix')).toBe(uzLatn['money.uzsSuffix']);
  });

  it('opens straight in the stored locale when its catalogue was awaited before bootstrap', async () => {
    localStorage.setItem(STORAGE_KEY, 'en');
    await preloadLocale('en');

    const i18n = service();

    expect(i18n.locale()).toBe('en');
    expect(i18n.t('money.uzsSuffix')).toBe(en['money.uzsSuffix']);
  });

  it('remembers the choice at once, before the catalogue arrives', () => {
    const pending = deferred<typeof en>();
    restores.push(setLoaderForTesting('en', () => pending.promise));
    const i18n = service();

    i18n.use('en');

    expect(localStorage.getItem(STORAGE_KEY)).toBe('en');
    expect(peekStoredLocale()).toBe('en');
  });

  it('reads an unusable stored value as the default', () => {
    localStorage.setItem(STORAGE_KEY, 'de');

    expect(peekStoredLocale()).toBe(DEFAULT_LOCALE);
  });

  it('knows which strings are catalogued messages', () => {
    const i18n = service();

    expect(i18n.hasMessage('money.uzsSuffix')).toBe(true);
    expect(i18n.hasMessage('wallet.entry.NOT_A_REAL_ENTRY')).toBe(false);
    expect(i18n.hasMessage('constructor')).toBe(false);
  });
});
