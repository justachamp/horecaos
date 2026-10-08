import { Injectable, signal, inject } from '@angular/core';
import { StorageService } from './storage.service';

const LANG_KEY = 'lang';

export const LANG_LABELS: Record<string, string> = {
  ru: 'Русский',
  uz: "O'zbek",
  en: 'English',
};

/**
 * The platform's tag for a language id of this app (ADR 0149).
 *
 * This app's ids are its catalogue files' names (`uz.json`, `ru.json`, `en.json`); the platform's
 * tag for Uzbek carries its script (`uz-Latn`), because Uzbek is written in two and a bare `uz` does
 * not say which. The two spellings differ for Uzbek only, and this is the one place the storefront
 * says so: the legal-document, channel-page and preset-wording locales and the customer's stored
 * language all go through it, where each used to carry a conversion of its own. The platform still
 * accepts a bare `uz` on input and answers with the tag, so an older build keeps working.
 */
const PLATFORM_TAGS: Readonly<Record<string, string>> = { uz: 'uz-Latn' };

export function platformTag(langId: string): string {
  return PLATFORM_TAGS[langId] ?? langId;
}

/** The inverse: this app's id for a platform tag the customer's record carries, or the tag as it was. */
export function langIdOf(tag: string): string {
  const found = Object.entries(PLATFORM_TAGS).find(([, platform]) => platform === tag);
  return found ? found[0] : tag;
}

@Injectable({ providedIn: 'root' })
export class LangService {
  private readonly storage = inject(StorageService);

  readonly langId = signal<string>('uz');

  /** Load language from storage (Cloud Storage or localStorage) */
  async load(): Promise<void> {
    const stored = await this.storage.getItem(LANG_KEY);
    if (stored && LANG_LABELS[stored]) {
      this.langId.set(stored);
    }
  }

  /** Set language and persist */
  setLang(id: string): void {
    this.langId.set(id);
    this.storage.setItem(LANG_KEY, id);
  }
}
