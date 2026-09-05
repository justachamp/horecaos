import { Injectable, signal, computed, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { LangService } from './lang.service';

export type TranslationDict = Record<string, unknown>;

@Injectable({ providedIn: 'root' })
export class TranslateService {
  private readonly http = inject(HttpClient);
  private readonly lang = inject(LangService);

  private readonly translations = signal<TranslationDict | null>(null);

  /** Current translations - reactive to language changes */
  readonly current = computed(() => {
    const dict = this.translations();
    const id = this.lang.langId();
    if (!dict || !id) return null;
    return dict as TranslationDict;
  });

  constructor() {
    this.lang.load().then(() => this.loadTranslations());
  }

  /**
   * Initialize translations before app renders. Call from APP_INITIALIZER.
   */
  async init(): Promise<void> {
    await this.lang.load();
    await this.loadTranslationsAsync();
  }

  /** Load translations for current language */
  loadTranslations(): void {
    const id = this.lang.langId();
    const path = `i18n/${id}.json`;
    this.http.get<TranslationDict>(path).subscribe({
      next: (data) => this.translations.set(data),
      error: () => this.translations.set(null),
    });
  }

  private async loadTranslationsAsync(): Promise<void> {
    const id = this.lang.langId();
    const path = `i18n/${id}.json`;
    try {
      const data = await firstValueFrom(this.http.get<TranslationDict>(path));
      this.translations.set(data);
    } catch {
      this.translations.set(null);
    }
  }

  /**
   * Get translation by dot-notation key (e.g. 'common.loading', 'nav.home').
   * Returns the key if translation not found.
   */
  get(key: string): string {
    const dict = this.translations();
    if (!dict) return key;
    const value = this.getNested(dict, key);
    return typeof value === 'string' ? value : key;
  }

  /**
   * Get translation with interpolation: t('greeting', { name: 'John' }) for "Hello {{name}}"
   */
  getWithParams(key: string, params?: Record<string, string | number>): string {
    let str = this.get(key);
    if (params) {
      for (const [k, v] of Object.entries(params)) {
        str = str.replace(new RegExp(`\\{\\{\\s*${k}\\s*\\}\\}`, 'g'), String(v));
      }
    }
    return str;
  }

  /** Switch language and reload translations */
  setLang(id: string): void {
    this.lang.setLang(id);
    this.loadTranslations();
  }

  private getNested(obj: Record<string, unknown>, path: string): unknown {
    const parts = path.split('.');
    let current: unknown = obj;
    for (const part of parts) {
      if (current == null || typeof current !== 'object') return undefined;
      current = (current as Record<string, unknown>)[part];
    }
    return current;
  }
}
