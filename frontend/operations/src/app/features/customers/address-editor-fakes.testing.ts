import { Provider, signal } from '@angular/core';

import { GeoAnswer, GeoResult, GeoSuggestion } from '../../core/api/geo-lookup-api';
import { GeoLookupApi } from '../../core/api/geo-lookup-api';
import { MapConfigService } from '../../shared/ui/map/map-config';
import { NullMapProvider, provideNullMapProvider } from '../../shared/ui/map/null-map-provider';
import { MapRegionService } from '../delivery/map-region';

/**
 * What a screen's spec needs in order to contain a `q-customer-address-editor` (rows `1.3b`,
 * `5.2c`): a map that draws nothing and records everything, an address lookup that answers from a
 * list instead of the platform, a map configuration with no provider, and a tenant with no region
 * readable (so the editor opens on the fallback centre and sends no region).
 *
 * Not imported by the application; it exists for the specs of the screens that host the editor.
 */
export class FakeAddressLookup {
  suggestions: GeoAnswer<readonly GeoSuggestion[]> = { status: 'ANSWERED', value: [] };
  resolutions: GeoAnswer<readonly GeoResult[]> = { status: 'ANSWERED', value: [] };

  async suggest(): Promise<GeoAnswer<readonly GeoSuggestion[]>> {
    return this.suggestions;
  }

  async resolve(): Promise<GeoAnswer<readonly GeoResult[]>> {
    return this.resolutions;
  }
}

export interface AddressEditorFakes {
  readonly providers: Provider[];
  readonly map: NullMapProvider;
  readonly lookup: FakeAddressLookup;
}

export function addressEditorFakes(): AddressEditorFakes {
  const map = new NullMapProvider();
  const lookup = new FakeAddressLookup();
  return {
    map,
    lookup,
    providers: [
      provideNullMapProvider(map),
      { provide: GeoLookupApi, useValue: lookup },
      {
        provide: MapConfigService,
        useValue: { ensureLoaded: () => Promise.resolve(null), config: signal(null) },
      },
      {
        provide: MapRegionService,
        useValue: { ensureLoaded: () => Promise.resolve(), primary: signal(null) },
      },
    ],
  };
}
