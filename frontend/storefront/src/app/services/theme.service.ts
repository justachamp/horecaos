import { Injectable, inject, signal } from '@angular/core';
import { StorageService } from './storage.service';

const THEME_KEY = 'theme';
const THEME_COLOR_DARK = '#111111';
const THEME_COLOR_LIGHT = '#f6f1f7';

export type ThemeMode = 'light' | 'dark';

export const THEME_MODES: ThemeMode[] = ['light', 'dark'];

@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly storage = inject(StorageService);

  readonly mode = signal<ThemeMode>(this.readInitialMode());

  constructor() {
    this.apply(this.mode());
  }

  /** Load theme from storage (Cloud Storage or localStorage) */
  async load(): Promise<void> {
    const stored = await this.storage.getItem(THEME_KEY);
    if (stored === 'light' || stored === 'dark') {
      this.setMode(stored);
    }
  }

  setMode(mode: ThemeMode): void {
    this.mode.set(mode);
    this.apply(mode);
    this.storage.setItem(THEME_KEY, mode);
  }

  toggle(): void {
    this.setMode(this.mode() === 'dark' ? 'light' : 'dark');
  }

  private readInitialMode(): ThemeMode {
    const stored = this.storage.getItemSync(THEME_KEY);
    if (stored === 'light' || stored === 'dark') {
      return stored;
    }
    if (typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: light)').matches) {
      return 'light';
    }
    return 'dark';
  }

  private apply(mode: ThemeMode): void {
    if (typeof document === 'undefined') {
      return;
    }
    const root = document.documentElement;
    root.dataset['theme'] = mode;
    root.classList.toggle('dark', mode === 'dark');
    root.classList.toggle('light', mode === 'light');
    root.style.colorScheme = mode;
    this.updateThemeColor(mode);
  }

  private updateThemeColor(mode: ThemeMode): void {
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) {
      meta.setAttribute('content', mode === 'light' ? THEME_COLOR_LIGHT : THEME_COLOR_DARK);
    }
  }
}
