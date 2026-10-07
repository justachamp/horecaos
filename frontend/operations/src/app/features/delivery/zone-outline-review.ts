import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { BrandScope } from '../../core/api/catalog-paths';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { boundsOf } from '../../shared/ui/map/geometry';
import { MapArea, MapCanvas } from '../../shared/ui/map/map-canvas';
import { LatLng, MapBounds, MapHandle } from '../../shared/ui/map/map-provider';
import { DeliveryZonesApi, ZoneOutlineResponse } from './delivery-zones-api';
import { FALLBACK_MAP_CENTRE } from './map-region';
import { OrderVerdict, outerRings, regionVerdict } from './zone-geometry';

/** How many corners the table beside the map lists before it says there are more. */
const CORNERS_SHOWN = 12;

const VERDICT_KEYS: Readonly<Record<OrderVerdict, MessageKey>> = {
  INSIDE: 'delivery.zones.review.verdict.INSIDE',
  OUTSIDE: 'delivery.zones.review.verdict.OUTSIDE',
  LIKELY_SWAPPED: 'delivery.zones.review.verdict.LIKELY_SWAPPED',
  NO_REGION: 'delivery.zones.review.verdict.NO_REGION',
};

/**
 * One stored zone version, looked at on a map before it can go live (rows `3.6`, `3.6c`; ADR 0037,
 * ADR 0145).
 *
 * **Why activation passes through here.** A zone's geometry is checked by PostGIS for validity and
 * against its region's box, and neither check can tell a correct polygon from one with its
 * latitude and longitude swapped *inside* a box large enough to hold both: the geometry is valid,
 * it is simply somewhere else. ADR 0037 therefore requires imported (and, by the same argument,
 * hand-drawn) geometry to be looked at on a map beside its source before it governs what a
 * customer is charged. This is that look: the stored outline on the map, the region it must sit
 * in, a verdict that names the likely mistake, the corners as numbers, and a deliberate
 * confirmation that is the gate, not a formality — "Activate" is disabled until it is given.
 *
 * **Without a map it still reviews.** In an environment with no map provider (no key has been
 * obtained) the map is replaced by its honest explanation, and what is left is what a person can
 * check by reading: the corner coordinates and the verdict. The confirmation then says that, and
 * does not pretend a map was looked at. The server's own checks (validity, the region's box, the
 * area ceiling) run at activation either way, and remain the authority.
 *
 * The component reads one outline and activates nothing itself: the host owns the call, so the
 * same review serves the zones page and the bulk-upload report.
 */
@Component({
  selector: 'q-zone-outline-review',
  imports: [TPipe, MapCanvas],
  templateUrl: './zone-outline-review.html',
  styleUrl: './zone-outline-review.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ZoneOutlineReview {
  private readonly api = inject(DeliveryZonesApi);

  readonly scope = input.required<BrandScope>();
  readonly zoneId = input.required<string>();
  readonly version = input.required<number>();
  /** The zone's code, for the heading; it is tenant data. */
  readonly zoneCode = input.required<string>();
  /** The region the zone must sit in: its box is drawn and the verdict is taken against it. */
  readonly region = input<MapBounds | null>(null);
  /** The tariff the zone is bound to, already worded; `null` when it has none. */
  readonly tariff = input<string | null>(null);
  /** `activate` asks for the confirmation and offers the button; `view` only shows. */
  readonly mode = input<'view' | 'activate'>('view');
  readonly busy = input(false);

  readonly activate = output<void>();
  readonly closed = output<void>();

  protected readonly state = signal<'loading' | 'ready' | 'failed'>('loading');
  protected readonly outline = signal<ZoneOutlineResponse | null>(null);
  protected readonly reviewed = signal(false);
  protected readonly mapAvailable = signal(true);

  private generation = 0;
  private readonly destroyed = signal(false);

  protected readonly rings = computed<readonly (readonly LatLng[])[]>(() => {
    const outline = this.outline();
    return outline === null ? [] : outerRings(outline);
  });

  protected readonly allCorners = computed<readonly LatLng[]>(() => this.rings().flat());

  protected readonly areas = computed<readonly MapArea[]>(() =>
    this.rings().map((ring, index) => ({ id: `outline-${index}`, ring })),
  );

  protected readonly fit = computed<MapBounds | null>(() => {
    const own = boundsOf(this.allCorners());
    const region = this.region();
    if (own === null) {
      return region;
    }
    // The outline and the region it should sit in, together: an outline far from its region is
    // exactly what a reviewer has to see, so the map must not be framed on the outline alone.
    return region === null
      ? own
      : (boundsOf([...this.allCorners(), region.southWest, region.northEast]) ?? own);
  });

  protected readonly centre = computed<LatLng>(() => {
    const first = this.allCorners()[0];
    const region = this.region();
    return first ?? region?.southWest ?? FALLBACK_MAP_CENTRE;
  });

  protected readonly verdict = computed<OrderVerdict>(() =>
    regionVerdict(this.allCorners(), this.region()),
  );
  protected readonly verdictKey = computed<MessageKey>(() => VERDICT_KEYS[this.verdict()]);

  protected readonly shownCorners = computed(() => this.allCorners().slice(0, CORNERS_SHOWN));
  protected readonly hiddenCornerCount = computed(
    () => this.allCorners().length - this.shownCorners().length,
  );

  /** Holes and parts the canvas cannot show: the outer outline of each polygon is all it draws. */
  protected readonly simplified = computed(() => {
    const outline = this.outline();
    return (
      outline !== null &&
      (outline.polygons.length > 1 || outline.polygons.some((polygon) => polygon.holes.length > 0))
    );
  });

  protected readonly canActivate = computed(
    () => this.mode() === 'activate' && this.state() === 'ready' && this.reviewed() && !this.busy(),
  );

  constructor() {
    inject(DestroyRef).onDestroy(() => this.destroyed.set(true));
    effect(() => {
      const scope = this.scope();
      const zoneId = this.zoneId();
      const version = this.version();
      void this.load(scope, zoneId, version);
    });
  }

  private async load(scope: BrandScope, zoneId: string, version: number): Promise<void> {
    const generation = ++this.generation;
    this.state.set('loading');
    this.reviewed.set(false);
    try {
      const outline = await this.api.outline(scope, zoneId, version);
      if (generation !== this.generation || this.destroyed()) {
        return;
      }
      this.outline.set(outline);
      this.state.set('ready');
    } catch {
      if (generation === this.generation && !this.destroyed()) {
        this.outline.set(null);
        this.state.set('failed');
      }
    }
  }

  /** Draws the region the zone must sit in beside the zone, so "outside" is a thing a person can see. */
  protected onMapReady(map: MapHandle): void {
    this.mapAvailable.set(true);
    const region = this.region();
    if (region !== null) {
      map.addRectangle({ bounds: region, editable: false });
    }
  }

  protected onMapUnavailable(): void {
    this.mapAvailable.set(false);
  }

  protected toggleReviewed(checked: boolean): void {
    this.reviewed.set(checked);
  }

  protected confirm(): void {
    if (this.canActivate()) {
      this.activate.emit();
    }
  }
}
