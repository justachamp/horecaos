import { Injectable } from '@angular/core';

/** Telegram Cloud Storage API (callback-based) */
interface TelegramCloudStorage {
  setItem(key: string, value: string, callback?: (err: unknown, stored: boolean) => void): TelegramCloudStorage;
  getItem(key: string, callback: (err: unknown, value: string | null) => void): TelegramCloudStorage;
  removeItem(key: string, callback?: (err: unknown, removed: boolean) => void): TelegramCloudStorage;
}

/** CloudStorage requires Telegram Web App 6.9+ */
function isCloudStorageSupported(): boolean {
  if (typeof window === 'undefined') return false;
  const version = (window as unknown as { Telegram?: { WebApp?: { version?: string } } })
    ?.Telegram?.WebApp?.version;
  if (!version) return false;
  const [major, minor] = version.split('.').map((n) => parseInt(n, 10) || 0);
  return major > 6 || (major === 6 && minor >= 9);
}

/**
 * Unified storage for Telegram mini app state persistence.
 * Uses Telegram Cloud Storage when available (syncs across devices),
 * falls back to localStorage for web or when Cloud Storage is unavailable.
 */
@Injectable({ providedIn: 'root' })
export class StorageService {
  private get cloudStorage(): TelegramCloudStorage | null {
    if (typeof window === 'undefined') return null;
    if (!isCloudStorageSupported()) return null;
    const tg = (window as unknown as { Telegram?: { WebApp?: { CloudStorage?: TelegramCloudStorage } } })
      ?.Telegram?.WebApp?.CloudStorage;
    return tg ?? null;
  }

  /** Whether Telegram Cloud Storage is available */
  get isCloudStorageAvailable(): boolean {
    return !!this.cloudStorage;
  }

  /**
   * Get a value. Uses Cloud Storage in Telegram, localStorage otherwise.
   * When reading from Cloud Storage, mirrors to localStorage for sync reads.
   * Falls back to localStorage if CloudStorage throws (e.g. WebAppMethodUnsupported).
   */
  async getItem(key: string): Promise<string | null> {
    const cloud = this.cloudStorage;
    if (cloud) {
      try {
        return await new Promise<string | null>((resolve, reject) => {
          try {
            cloud.getItem(key, (err, value) => {
              if (err) {
                resolve(this.getFromLocal(key));
              } else {
                const v = value ?? null;
                if (v) this.setInLocal(key, v); // Mirror for getItemSync
                resolve(v);
              }
            });
          } catch (e) {
            reject(e);
          }
        });
      } catch {
        return this.getFromLocal(key);
      }
    }
    return Promise.resolve(this.getFromLocal(key));
  }

  /**
   * Set a value. Uses Cloud Storage in Telegram, localStorage otherwise.
   * Keys: 1-128 chars, A-Z a-z 0-9 _ -
   * Values: max 4096 chars for Cloud Storage
   */
  async setItem(key: string, value: string): Promise<void> {
    const cloud = this.cloudStorage;
    if (cloud) {
      try {
        await new Promise<void>((resolve, reject) => {
          try {
            cloud.setItem(key, value, (err) => {
              this.setInLocal(key, value); // Mirror for getItemSync; fallback on error
              resolve();
            });
          } catch (e) {
            reject(e);
          }
        });
      } catch {
        this.setInLocal(key, value);
      }
      return;
    }
    this.setInLocal(key, value);
    return Promise.resolve();
  }

  /**
   * Remove a value.
   */
  async removeItem(key: string): Promise<void> {
    const cloud = this.cloudStorage;
    if (cloud) {
      try {
        await new Promise<void>((resolve, reject) => {
          try {
            cloud.removeItem(key, () => resolve());
          } catch (e) {
            reject(e);
          }
        });
      } catch {
        localStorage.removeItem(key);
      }
      return;
    }
    localStorage.removeItem(key);
    return Promise.resolve();
  }

  /**
   * Synchronous get for localStorage fallback.
   * Use when you need the value immediately (e.g. before first async load).
   */
  getItemSync(key: string): string | null {
    return this.getFromLocal(key);
  }

  /**
   * Synchronous set for localStorage fallback.
   */
  setItemSync(key: string, value: string): void {
    this.setInLocal(key, value);
  }

  private getFromLocal(key: string): string | null {
    try {
      return localStorage.getItem(key);
    } catch {
      return null;
    }
  }

  private setInLocal(key: string, value: string): void {
    try {
      localStorage.setItem(key, value);
    } catch {
      // Quota exceeded or disabled
    }
  }
}
