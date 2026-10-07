import { Injectable, Provider, inject } from '@angular/core';

import { I18n } from '../../../core/i18n/i18n';
import { MapConfigService } from './map-config';
import {
  MAP_PROVIDER,
  MapHandle,
  MapOptions,
  MapProvider,
  MapUnavailableError,
} from './map-provider';

/**
 * The application's {@link MapProvider}: it decides which vendor to use from the platform's own
 * configuration and fetches that vendor's adapter, and then the vendor's script, only when a map
 * is first asked for (ADR 0145 decision 1: "loaded from the vendor's own origin the first time a
 * map screen opens, never bundled").
 *
 * Two lazy steps, both deliberate. The adapter is a dynamic `import()` so the code that knows a
 * vendor's types is its own chunk, absent from the initial bundle and from every screen that never
 * draws a map; the vendor's script is then injected from the vendor's origin by that adapter.
 * Neither is a static import anywhere in the application.
 *
 * Which adapter is data, not a branch in a component: the one `switch` on the provider code in the
 * whole front end is here, and a second vendor is one more `case` and one more file.
 */
@Injectable({ providedIn: 'root' })
export class LazyMapProvider implements MapProvider {
  private readonly configuration = inject(MapConfigService);
  private readonly i18n = inject(I18n);

  private loaded: Promise<MapProvider> | null = null;
  private inner: MapProvider | null = null;

  get code(): string {
    return this.inner?.code ?? this.configuration.config()?.provider ?? 'NONE';
  }

  load(): Promise<void> {
    // The same promise for everybody, but a failure is forgotten: the next screen to open a map
    // after the vendor's origin came back should get one.
    this.loaded ??= this.resolve().catch((failure: unknown) => {
      this.loaded = null;
      throw failure;
    });
    return this.loaded.then(() => undefined);
  }

  createMap(element: HTMLElement, options: MapOptions): MapHandle {
    if (this.inner === null) {
      throw new Error('createMap was called before load() resolved');
    }
    return this.inner.createMap(element, options);
  }

  private async resolve(): Promise<MapProvider> {
    const config = await this.configuration.ensureLoaded();
    if (config === null || !config.configured || config.provider === 'NONE') {
      throw new MapUnavailableError('NOT_CONFIGURED');
    }
    if (!config.features.includes('TILES') || !config.browserKey) {
      // Address search without a map, which is what the fake provider is and what Yandex is
      // before a browser key is set. Not a failure, and not drawable.
      throw new MapUnavailableError('NO_TILES');
    }

    let provider: MapProvider;
    switch (config.provider) {
      case 'YANDEX': {
        const { YandexMapProvider } = await import('./yandex-map-provider');
        provider = new YandexMapProvider(config.browserKey, vendorLanguage(this.i18n.locale()));
        break;
      }
      default:
        // A provider this build has no adapter for. The platform and the console are deployed
        // separately, so this is a real state, and the honest answer is "cannot draw it".
        throw new MapUnavailableError('NOT_CONFIGURED');
    }

    await provider.load();
    this.inner = provider;
    return provider;
  }
}

/**
 * The language to ask the vendor's map for. The vendor documents Russian and English; there is
 * no Uzbek locale to ask for, so `uz-Latn` gets Russian, whose names Tashkent maps carry.
 */
function vendorLanguage(locale: string): string {
  return locale === 'en' ? 'en_US' : 'ru_RU';
}

/**
 * Registers {@link LazyMapProvider} as the application's {@link MAP_PROVIDER}. Called once, from
 * `app.config.ts`; a spec that contains a map provides `provideNullMapProvider()` instead.
 */
export function provideMapProvider(): Provider {
  return { provide: MAP_PROVIDER, useExisting: LazyMapProvider };
}
