import { Injectable, Signal, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../api/api-client';
import { settingsPaths } from '../api/settings-paths';
import { CurrentBrand } from '../auth/current-brand';
import { LOCALES } from './i18n';
import { PlatformLocales } from './platform-locales';

/** One locale a brand has chosen to support, and whether it is the default. */
export interface LocaleOption {
  /**
   * A BCP 47 tag of a language the brand's content can be in (the registry's content tier), not the
   * console's own `Locale`: a brand may author in a language this build has no staff catalogue for.
   */
  readonly locale: string;
  readonly isDefault: boolean;
}

/** The one field this module reads from `OperationsBrandController.get`'s `BrandView`. */
interface BrandLocalesView {
  readonly locales: readonly { readonly locale: string; readonly isDefault: boolean }[];
}

/** A brand's supported-language set as an editor consumes it, with the platform fallback already applied. */
export interface ResolvedLocaleSet {
  /** Default first; every language live in the registry's content tier when the brand has chosen none. */
  readonly locales: readonly string[];
  /** The brand's own default, or the platform default when it has chosen none. */
  readonly defaultLocale: string;
  /** Whether the brand has chosen its own set, as opposed to sitting on the platform fallback. */
  readonly isConfigured: boolean;
}

/**
 * Applies the "empty means not configured yet" fallback and the "default
 * first" ordering to a brand's `locales` list — the one rule every localized
 * field editor in this console shares.
 *
 * {@link LocaleSet} runs it over the operator's own brand. A screen that edits
 * <em>another</em> brand's content behind its own brand picker (the terms of
 * service, which the tenant owner authors per brand and who has no brand scope
 * of their own) runs it over the picked brand's `BrandView.locales` instead,
 * so it offers the same languages, in the same order, with the same default,
 * as every screen that reads {@link LocaleSet}.
 */
export function resolveLocaleSet(
  configured:
    readonly { readonly locale: string; readonly isDefault: boolean }[] | null | undefined,
  registry: PlatformLocales,
): ResolvedLocaleSet {
  const options = orderDefaultFirst(configured ?? [], registry);
  const own = options.find((option) => option.isDefault);
  const content = registry.active('CONTENT');
  return {
    // The registry's content tier; before it has been read, the languages this build has catalogues for.
    locales:
      options.length > 0
        ? options.map((option) => option.locale)
        : content.length > 0
          ? content
          : LOCALES,
    defaultLocale: own ? own.locale : registry.fallback(),
    isConfigured: options.length > 0,
  };
}

/**
 * Row 10.12: a brand's own supported-language set and default language
 * (`tenant.brand_locales`, batch 9's `10.1` brand-profile screen), read once
 * and shared by every localized-field editor in this console instead of each
 * one hard-coding the platform's ru/uz-Latn/en triple.
 *
 * **Fallback.** `BrandProfile`'s own doc comment is explicit: an empty
 * `locales` list means "not configured yet", not "supports nothing" — every
 * localized field falls back to the platform triple, `ru` default, until a
 * brand actually chooses its own set. {@link locales} and {@link
 * defaultLocale} encode exactly that fallback so a caller never has to ask
 * "is this brand configured" before it can render a form.
 *
 * **Resolution.** Mirrors `CurrentBrand`'s own shape: fetch `GET
 * .../brands/{brandId}` once `CurrentBrand` has resolved a scope, cache the
 * promise, and settle on the fallback on any failure (denied, not found, or
 * simply no brand scope at all) rather than leaving every editor waiting
 * forever on a locale list that will never arrive.
 *
 * **Ordering.** {@link locales} always returns the default locale first —
 * every editor built against this service gets "the default tab is the
 * first tab" for free, which is also where {@link locales}' first element
 * doc points a required-marker at.
 */
@Injectable({ providedIn: 'root' })
export class LocaleSet {
  private readonly api = inject(ApiClient);
  private readonly currentBrand = inject(CurrentBrand);
  private readonly registry = inject(PlatformLocales);

  /** `null` before load settles; `[]` once settled but the brand has configured nothing (or resolution failed). */
  private readonly configured = signal<readonly LocaleOption[] | null>(null);

  private readonly resolved = computed(() => resolveLocaleSet(this.configured(), this.registry));

  /**
   * The brand's own supported locales, default first — or, unconfigured (or
   * before load settles), the platform's full `ru`/`uz-Latn`/`en` triple,
   * `ru` first.
   */
  readonly locales: Signal<readonly string[]> = computed(() => this.resolved().locales);

  /** The brand's own chosen default, or the platform default when unconfigured. */
  readonly defaultLocale: Signal<string> = computed(() => this.resolved().defaultLocale);

  /** Whether the brand has chosen its own set, as opposed to still sitting on the platform fallback. */
  readonly isConfigured: Signal<boolean> = computed(() => this.resolved().isConfigured);

  private loadPromise: Promise<void> | null = null;

  /** Fetches the brand's locale set once; every later call replays the same promise. */
  ensureLoaded(): Promise<void> {
    if (this.loadPromise === null) {
      this.loadPromise = this.load();
    }
    return this.loadPromise;
  }

  /** Whether the brand's set (or, unconfigured, the platform triple) includes this locale. */
  supports(locale: string): boolean {
    return this.locales().includes(locale);
  }

  private async load(): Promise<void> {
    await this.currentBrand.ensureLoaded();
    const scope = this.currentBrand.scope();
    if (!scope) {
      this.configured.set([]);
      return;
    }
    try {
      const result = await firstValueFrom(
        this.api.get<BrandLocalesView>(settingsPaths.brand({ ...scope, locationId: '' })),
      );
      this.configured.set(orderDefaultFirst(result.value.locales ?? [], this.registry));
    } catch {
      this.configured.set([]);
    }
  }
}

/** The default locale first, then the rest in the platform's own canonical order. */
function orderDefaultFirst(
  options: readonly { readonly locale: string; readonly isDefault: boolean }[],
  registry: PlatformLocales,
): readonly LocaleOption[] {
  const order = registry.fallbackOrder();
  // A tag the registry does not know sorts after every one it does, then by tag: stable and total.
  const rank = (tag: string): number => (order.includes(tag) ? order.indexOf(tag) : order.length);
  return [...options].sort((a, b) => {
    if (a.isDefault !== b.isDefault) {
      return a.isDefault ? -1 : 1;
    }
    return rank(a.locale) - rank(b.locale) || a.locale.localeCompare(b.locale);
  });
}
