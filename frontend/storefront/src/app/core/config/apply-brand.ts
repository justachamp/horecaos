import type { BrandConfig } from './app-config';

/**
 * Pushes a brand's identity onto the document itself: the tab title, and the
 * accent colours the shared token set reads (`--brand-accent`/
 * `--brand-accent-deep` in `styles.scss`).
 *
 * Called once, in `main.ts`, right after `loadAppConfig` resolves and before
 * `bootstrapApplication` -- so the very first paint already carries the
 * tenant's name and colours instead of showing `index.html`'s neutral
 * placeholder and then jumping to the brand's a frame later.
 *
 * There is deliberately no fallback branch here: `AppConfig.brand` is never
 * absent by the time this runs (`load-config.ts` fills every gap with
 * `NEUTRAL_BRAND`), so a brand's colours reaching the page and a missing
 * config degrading to the neutral grey are the same code path, not two.
 */
export function applyBrand(brand: BrandConfig, doc: Document = document): void {
  doc.title = brand.displayName;
  const root = doc.documentElement;
  root.style.setProperty('--brand-accent', brand.theme.accent);
  root.style.setProperty('--brand-accent-deep', brand.theme.accentDeep);
}
