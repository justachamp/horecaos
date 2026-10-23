import { DOCUMENT } from '@angular/common';
import { Injectable, InjectionToken, computed, effect, inject, signal } from '@angular/core';

import { PlatformLocales, heldPlatformLocales } from './platform-locales.service';
import { StorageService } from './storage.service';

const LANG_KEY = 'lang';

/**
 * The language ids this build has a catalogue for: the names of the files under `src/i18n/`.
 *
 * It is the one list of languages this app keeps, and it is meant to be: a catalogue is compile-time
 * (a screen cannot be drawn in a language nobody wrote the words for), while *which* of them the
 * restaurant serves and which the platform has live is read (ADR 0149, Decision 5; see
 * {@link PlatformLocales}). An id is the registry's `catalogCode`, so `uz` here is `uz-Latn` there.
 * Adding a language means adding its file and its id here; nothing else in the app names it.
 */
export const BUILD_CATALOGUE_IDS: readonly string[] = ['uz', 'ru', 'en'];

/**
 * {@link BUILD_CATALOGUE_IDS} as an injectable, so the activation rehearsal can stand in for a build
 * that has gained a catalogue without adding one. Nothing else overrides it.
 */
export const BUILD_CATALOGUES = new InjectionToken<readonly string[]>('BUILD_CATALOGUES', {
  providedIn: 'root',
  factory: () => BUILD_CATALOGUE_IDS,
});

/**
 * The platform's tag for a language id of this app (ADR 0149).
 *
 * This app's ids are its catalogue files' names, which are the registry's `catalogCode`s; the
 * platform's tag for Uzbek carries its script (`uz-Latn`), because Uzbek is written in two and a bare
 * `uz` does not say which. The registry says which id is which tag, so there is no table here: the
 * legal-document, channel-page and preset-wording locales and the customer's stored language all go
 * through this, where each used to carry a conversion of its own. A tag the registry has not been read
 * for is the id as it was; the platform reads a registered alias on input and answers with the tag.
 */
export function platformTag(langId: string): string {
  return heldPlatformLocales().find((entry) => entry.catalogCode === langId)?.tag ?? langId;
}

/** The inverse: this app's id for a platform tag the customer's record carries, or the tag as it was. */
export function langIdOf(tag: string): string {
  const found = heldPlatformLocales().find(
    (entry) => entry.tag === tag || entry.inputAliases.includes(tag),
  );
  return found ? found.catalogCode : tag;
}

/** One language a picker offers. */
export interface LanguageOption {
  /** This app's id: the catalogue file's name. */
  readonly id: string;
  /** The platform's tag for it. */
  readonly tag: string;
  /** Its name in itself, from the registry. */
  readonly label: string;
}

@Injectable({ providedIn: 'root' })
export class LangService {
  private readonly storage = inject(StorageService);
  private readonly registry = inject(PlatformLocales);
  private readonly document = inject(DOCUMENT);
  private readonly catalogues = inject(BUILD_CATALOGUES);

  /**
   * The active language. `uz` until the registry and the brand's default have been read: it is the
   * language this build opened in before there was a registry, and what a customer reads if the read
   * fails. {@link load} replaces it with the brand's own default.
   */
  readonly langId = signal<string>('uz');

  /** Whether a customer, rather than {@link load}, has chosen the active language. */
  private chosen = false;

  /**
   * The languages to offer: live in the registry's content tier, with a catalogue in this build,
   * narrowed to the brand's own set (its default first). While the registry is unread, or when it
   * cannot be read, the build's own catalogues.
   */
  readonly offered = computed<readonly LanguageOption[]>(() => {
    const entries = this.registry.entries();
    const live = entries.filter(
      (entry) => entry.tiers.includes('CONTENT') && this.catalogues.includes(entry.catalogCode),
    );
    if (live.length === 0) {
      return this.catalogues.map((id) => ({ id, tag: id, label: id }));
    }
    const brand = this.registry.brand();
    const served = brand?.configured ? brand.locales : null;
    let ordered = [...live].sort((a, b) => a.fallbackRank - b.fallbackRank);
    if (served !== null) {
      const own = served.flatMap((tag) => live.filter((entry) => entry.tag === tag));
      // A brand that serves nothing this build can draw is offered everything live rather than nothing.
      ordered = own.length > 0 ? own : ordered;
    }
    return ordered.map((entry) => ({
      id: entry.catalogCode,
      tag: entry.tag,
      label: entry.names[entry.tag] ?? entry.tag,
    }));
  });

  constructor() {
    // `<html lang dir>` follow the active language (ADR 0149, Decision 6): a screen reader and a
    // browser's hyphenation read the page in the language it is drawn in, and a right-to-left language
    // registered later needs no change here.
    effect(() => {
      const id = this.langId();
      const entry = this.registry.entryOf(id);
      const root = this.document.documentElement;
      root.lang = entry?.tag ?? id;
      root.dir = (entry?.direction ?? 'LTR').toLowerCase();
    });
  }

  /** A language's name in itself, for a picker; the id when the registry has not named it. */
  labelOf(id: string): string {
    return this.offered().find((option) => option.id === id)?.label ?? id;
  }

  /**
   * Reads the registry and the brand's set, then the stored language (Cloud Storage or
   * localStorage). A stored language the brand no longer serves is ignored; with none stored the
   * active language is the brand's default.
   */
  async load(): Promise<void> {
    await this.registry.ensureLoaded();
    const stored = await this.storage.getItem(LANG_KEY);
    const offered = this.offered().map((option) => option.id);
    if (stored && offered.includes(stored)) {
      this.langId.set(stored);
      return;
    }
    if (!this.chosen) {
      const initial = this.initialId(offered);
      if (initial !== null) {
        this.langId.set(initial);
      }
    }
  }

  /** Set language and persist */
  setLang(id: string): void {
    this.chosen = true;
    this.langId.set(id);
    this.storage.setItem(LANG_KEY, id);
  }

  /**
   * The brand's default if this build can draw it, else the first language offered; null while the
   * brand's set is unknown, so a failed read leaves the app in the language it opened in.
   */
  private initialId(offered: readonly string[]): string | null {
    const brand = this.registry.brand();
    if (brand === null) {
      return null;
    }
    const own = this.registry.entryOf(brand.defaultLocale)?.catalogCode;
    if (own && offered.includes(own)) {
      return own;
    }
    return offered[0] ?? null;
  }
}
