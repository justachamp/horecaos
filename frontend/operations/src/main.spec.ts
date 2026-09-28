import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  STORAGE_KEY,
  resetCatalogueCacheForTesting,
  setLoaderForTesting,
} from './app/core/i18n/i18n';

/**
 * The Angular unit-test builder refuses `vi.mock` on a relative import (see
 * its own error if you try), so `./app/core/i18n/i18n`, `./app/app` and
 * `./app/app.config` are used for real here — this test drives the actual
 * `preloadLocale` code path through {@link setLoaderForTesting}, a
 * production-code test seam, rather than a mocked stand-in for it. Only
 * `@angular/platform-browser` (a bare package specifier) is mocked, and only
 * to observe whether `bootstrapApplication` gets called — its other exports
 * are passed through untouched via `importOriginal`, since other modules in
 * the app's import graph may reach for them too.
 *
 * `main.ts` runs `bootstrap()` as a side effect of being imported, and that
 * side effect only fires once per module instance — so this file imports
 * `./main` exactly once (no `vi.resetModules()` churn) to keep that one
 * shared instance of the i18n module in play for both the setup below and
 * the code under test.
 */
const mocks = vi.hoisted(() => ({
  bootstrapApplication: vi.fn().mockResolvedValue(undefined),
}));

vi.mock('@angular/platform-browser', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@angular/platform-browser')>();
  return Object.assign({}, actual, { bootstrapApplication: mocks.bootstrapApplication });
});

describe('bootstrap', () => {
  let restoreLoader: (() => void) | undefined;

  beforeEach(() => {
    localStorage.clear();
    resetCatalogueCacheForTesting();
  });

  afterEach(() => {
    restoreLoader?.();
    restoreLoader = undefined;
    localStorage.clear();
  });

  it('still bootstraps the app when the persisted locale fails to preload', async () => {
    // A returning operator on `en` or `uz-Latn` whose locale-chunk fetch
    // 404s or times out (bad deploy, flaky connection) must still get an
    // app, not a permanently empty <q-root> — see the finding this guards.
    localStorage.setItem(STORAGE_KEY, 'en');
    restoreLoader = setLoaderForTesting('en', () => Promise.reject(new Error('chunk load failed')));

    await import('./main');
    // Let bootstrap()'s async chain (the rejected preload, its handling, and
    // the subsequent bootstrapApplication call) settle.
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(mocks.bootstrapApplication).toHaveBeenCalled();
  });
});
