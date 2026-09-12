// This app's tsconfig carries no "node" types (a browser app has no business
// importing Node built-ins at runtime) — these three imports are the
// exception: they run only under Vitest's Node test runner, never shipped
// to a browser, so `@ts-expect-error` opts out of that check here alone.
// @ts-expect-error node:fs is available at Vitest's Node runtime
import { readFileSync } from 'node:fs';
// @ts-expect-error node:path is available at Vitest's Node runtime
import { dirname, resolve } from 'node:path';
// @ts-expect-error node:url is available at Vitest's Node runtime
import { fileURLToPath } from 'node:url';

const TOKENS_CSS_PATH = resolve(dirname(fileURLToPath(import.meta.url)), '../../tokens.css');

let injected = false;

/**
 * Loads the app's real global stylesheet (`src/tokens.css`, the file
 * `styles.css` actually `@import`s and every build ships) into the test
 * document, so `getComputedStyle` resolves the true design-token values
 * instead of an empty string.
 *
 * Angular's Vitest-based unit-test builder does not carry the `styles` array
 * from `angular.json`'s build target into the jsdom document the way a real
 * browser navigation would — verified empirically: before this runs,
 * `document.head` holds zero `<style>` tags. Component-scoped CSS
 * (`styleUrl`) still renders correctly, because Angular's own renderer
 * inserts that per-component, independent of the global stylesheet; a
 * *global* utility class like `.q-display-tv` does not, and asserting
 * `getComputedStyle(...).fontSize` against it would otherwise read `''`
 * forever — passing or failing for the wrong reason, never for the one this
 * wave exists to catch (`wallboard-shell.ts`'s own doc: the freshness stamp
 * rendered at caption size instead of the TV step).
 *
 * Reads and injects the exact file the running app serves, once per test
 * process, so the assertion tracks the real token rather than a duplicated
 * literal that could drift from it unnoticed.
 */
export function ensureDesignTokensLoaded(): void {
  if (injected) {
    return;
  }
  const style = document.createElement('style');
  style.textContent = readFileSync(TOKENS_CSS_PATH, 'utf-8');
  document.head.appendChild(style);
  injected = true;
}
