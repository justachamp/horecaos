import { Injectable, Optional, Inject, InjectionToken } from '@angular/core';

export const YANDEX_MAPS_API_KEY = new InjectionToken<string>('YANDEX_MAPS_API_KEY');

declare global {
  interface Window {
    ymaps?: unknown;
  }
}

/**
 * The slice of the Yandex Maps 2.1 script API this service calls. The script
 * ships no types of its own, so the members are declared here, optional where
 * the calling code already tolerates their absence.
 */
interface YMapsGeoObject {
  getAddressLine?(): string;
  geometry?: { getCoordinates?(): number[] };
  properties?: { get?(key: string): unknown };
}

interface YMapsApi {
  ready(callback: () => void): void;
  geocode?(query: string | number[]): Promise<{
    geoObjects: {
      get(index: number): YMapsGeoObject | undefined;
      each(callback: (object: YMapsGeoObject) => void): void;
    };
  }>;
}

/** Set your API key in app.config: provide(YANDEX_MAPS_API_KEY, { useValue: 'your-key' }). Get key: https://developer.tech.yandex.ru/ */
const DEFAULT_API_KEY = '';

@Injectable({ providedIn: 'root' })
export class YandexMapsService {
  private loadPromise: Promise<void> | null = null;
  private ymapsReady = false;

  constructor(@Optional() @Inject(YANDEX_MAPS_API_KEY) private apiKey: string | null = null) {}

  /** The script's global, once it has loaded. */
  private get ymaps(): YMapsApi | undefined {
    return window.ymaps as YMapsApi | undefined;
  }

  /** Load Yandex Maps script and resolve when ymaps is ready. */
  load(apiKey?: string): Promise<void> {
    const key = apiKey ?? this.apiKey ?? DEFAULT_API_KEY;
    if (this.ymapsReady && window.ymaps) {
      return Promise.resolve();
    }
    if (this.loadPromise) {
      return this.loadPromise;
    }
    this.loadPromise = new Promise((resolve, reject) => {
      if (this.ymaps) {
        this.ymaps.ready(() => {
          this.ymapsReady = true;
          resolve();
        });
        return;
      }
      const script = document.createElement('script');
      script.src = `https://api-maps.yandex.ru/2.1/?apikey=${encodeURIComponent(key)}&lang=ru_RU`;
      script.async = true;
      script.onload = () => {
        this.ymaps?.ready(() => {
          this.ymapsReady = true;
          resolve();
        });
      };
      script.onerror = () => reject(new Error('Yandex Maps script failed to load'));
      document.head.appendChild(script);
    });
    return this.loadPromise;
  }

  /** Reverse geocode coordinates to address string. */
  async getAddressFromCoords(latitude: number, longitude: number): Promise<string> {
    await this.load();
    const ymaps = this.ymaps;
    if (!ymaps?.geocode) {
      return `${latitude.toFixed(6)}, ${longitude.toFixed(6)}`;
    }
    const res = await ymaps.geocode([latitude, longitude]);
    const first = res.geoObjects.get(0);
    if (!first) {
      return `${latitude.toFixed(6)}, ${longitude.toFixed(6)}`;
    }
    return first.getAddressLine?.() || `${latitude.toFixed(6)}, ${longitude.toFixed(6)}`;
  }

  isLoaded(): boolean {
    return this.ymapsReady && !!window.ymaps;
  }

  /** Geocode search - returns suggested locations with coordinates. */
  async suggestAddresses(
    query: string,
    limit = 8,
  ): Promise<{ address: string; lat: number; lng: number }[]> {
    if (!query || query.trim().length < 2) return [];
    await this.load();
    const ymaps = this.ymaps;
    if (!ymaps?.geocode) return [];
    const res = await ymaps.geocode(query.trim());
    const items: { address: string; lat: number; lng: number }[] = [];
    res.geoObjects.each((obj) => {
      const coords = obj.geometry?.getCoordinates?.();
      const named = obj.properties?.get?.('name');
      const addr = obj.getAddressLine?.() || (typeof named === 'string' ? named : '');
      if (coords && addr) {
        items.push({ address: addr, lat: coords[0], lng: coords[1] });
      }
    });
    return items.slice(0, limit);
  }
}
