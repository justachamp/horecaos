import { Injectable, computed, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { centreOf } from '../../shared/ui/map/geometry';
import { LatLng, MapBounds } from '../../shared/ui/map/map-provider';
import { RegionResponse, RegionsApi } from './regions-api';

/**
 * Where Tashkent is, for a screen that has nothing more particular to open its map on: the pilot
 * region's centre (ADR 0145 decision 7: "the pilot region is Tashkent"). A fallback and never a
 * claim: a tenant with a region of its own opens there instead, and a screen that has a point
 * (a branch, an address) opens on that.
 */
export const FALLBACK_MAP_CENTRE: LatLng = { latitude: 41.2995, longitude: 69.2401 };

/** A region as a map needs it: an id for the lookups, a box that constrains them, a place to open on. */
export interface MapRegion {
  readonly regionId: string;
  readonly code: string;
  readonly platform: boolean;
  readonly bounds: MapBounds;
  readonly centre: LatLng;
}

export type MapRegionState = 'idle' | 'loading' | 'ready' | 'denied' | 'failed';

/**
 * The tenant's active regions, read once and shared by every screen that draws a map (ADR 0145
 * decision 7: "regions constrain every call").
 *
 * A region is a bounding box (ADR 0037), and the box is what a map opens on, what an address
 * search is constrained to and what a drawn zone is checked against. A screen asks this service
 * for it instead of each reading the regions list for itself, and instead of hard-coding a city.
 *
 * **Not being able to read the regions is not an error.** Reading them takes `delivery.zone.read`,
 * which the floor staff taking an order by phone do not hold; for them the answer is `denied`, and
 * the screen opens the map on the branch or on {@link FALLBACK_MAP_CENTRE} and lets the server
 * apply the tenant's only region on its own (the lookups accept no region and say `NO_REGION` when
 * there is more than one to choose from). A failed read is not remembered, so the next screen
 * tries again.
 */
@Injectable({ providedIn: 'root' })
export class MapRegionService {
  private readonly api = inject(RegionsApi);
  private readonly brand = inject(CurrentBrand);

  private readonly stateSignal = signal<MapRegionState>('idle');
  private readonly regionsSignal = signal<readonly MapRegion[]>([]);
  private inFlight: Promise<void> | null = null;

  readonly state = this.stateSignal.asReadonly();
  readonly regions = this.regionsSignal.asReadonly();

  /**
   * The region a screen with no particular one in mind uses: the tenant's own first, else the
   * platform's. Ordered by code, so two reads agree.
   */
  readonly primary = computed<MapRegion | null>(() => {
    const all = this.regionsSignal();
    return all.find((region) => !region.platform) ?? all[0] ?? null;
  });

  /** The region by id, or the primary one. */
  regionFor(regionId: string | null | undefined): MapRegion | null {
    if (regionId) {
      return this.regionsSignal().find((region) => region.regionId === regionId) ?? this.primary();
    }
    return this.primary();
  }

  /** Reads the regions unless they are known already. Concurrent callers share one request. */
  ensureLoaded(): Promise<void> {
    if (this.stateSignal() === 'ready' || this.stateSignal() === 'denied') {
      return Promise.resolve();
    }
    this.inFlight ??= this.load().finally(() => (this.inFlight = null));
    return this.inFlight;
  }

  private async load(): Promise<void> {
    this.stateSignal.set('loading');
    try {
      await this.brand.ensureLoaded();
      const scope = this.brand.scope();
      if (scope === null) {
        this.stateSignal.set('failed');
        return;
      }
      const all = await this.api.list(scope.tenantId);
      this.regionsSignal.set(
        all
          .filter((region) => region.status === 'ACTIVE')
          .map(toMapRegion)
          .sort((a, b) => a.code.localeCompare(b.code)),
      );
      this.stateSignal.set('ready');
    } catch (failure) {
      const denied = failure instanceof ApiError && failure.status === 403;
      this.stateSignal.set(denied ? 'denied' : 'failed');
    }
  }
}

/** A region's south-west / north-east box, in the console's own naming. */
export function boundsOfRegion(region: RegionResponse): MapBounds {
  return {
    southWest: { latitude: region.bboxSwLat, longitude: region.bboxSwLon },
    northEast: { latitude: region.bboxNeLat, longitude: region.bboxNeLon },
  };
}

/** Where a map of this region opens: its own centre, or the middle of its box when it has none. */
export function centreOfRegion(region: RegionResponse): LatLng {
  return Number.isFinite(region.centreLat) && Number.isFinite(region.centreLon)
    ? { latitude: region.centreLat, longitude: region.centreLon }
    : centreOf(boundsOfRegion(region));
}

function toMapRegion(region: RegionResponse): MapRegion {
  return {
    regionId: region.regionId,
    code: region.code,
    platform: region.platform,
    bounds: boundsOfRegion(region),
    centre: centreOfRegion(region),
  };
}
