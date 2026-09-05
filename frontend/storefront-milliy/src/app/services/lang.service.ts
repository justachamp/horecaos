import { Injectable, signal, inject } from '@angular/core';
import { StorageService } from './storage.service';

const LANG_KEY = 'lang';

export const LANG_LABELS: Record<string, string> = {
  ru: 'Русский',
  uz: "O'zbek",
  en: 'English'
};

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
