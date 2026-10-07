import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { PlatformLocales } from '../../core/i18n/platform-locales';
import { TPipe } from '../../core/i18n/t.pipe';
import { MapArea, MapCanvas } from '../../shared/ui/map/map-canvas';
import { LatLng } from '../../shared/ui/map/map-provider';
import {
  DeliveryZonesApi,
  ZoneOutlineResponse,
  ZoneSummaryResponse,
} from '../delivery/delivery-zones-api';
import { localisedName } from '../delivery/localised-name';
import { outerRings } from '../delivery/zone-geometry';
import { ProvenanceBanner } from './provenance-banner';
import { formatCount } from './report-formatting';
import { ProvenanceResponse, ReportingApi, ZoneDensityRowResponse } from './reporting-api';

type LoadState = 'idle' | 'loading' | 'ready' | 'error';

/** One line of the density table: a zone (or "outside every zone") and what was delivered into it. */
interface DensityRow {
  /** `null` for the deliveries no drawn zone covered. */
  readonly zoneId: string | null;
  readonly name: string;
  readonly deliveries: number;
  /** `0.0` to `1.0`: this row against the busiest zone, which is what shades the map. */
  readonly intensity: number;
  /** Share of every delivery counted, 0 to 100. */
  readonly sharePercent: number;
  readonly fees: string;
}

/**
 * Where deliveries land against the delivery zones that were drawn for them (row `7.10`, ADR 0145
 * decision 8): the order-density view.
 *
 * **A zone dimension and never a doorstep.** ADR 0037 kept coordinates out of the fee-resolution
 * event ("the heat-map consumers need the zone, not the doorstep"), so this reads deliveries per
 * zone off the closed reporting fact that already carries the zone a fee was resolved against, and
 * shades each zone's outline by how many it took. Nothing on the map can be traced to a person.
 *
 * **The number that earns the view is the one outside every zone.** A delivery priced by the
 * branch's own tariff because no drawn zone covered its address is the signature of a badly cut
 * zone or a missing catchment, and a density view that dropped it would show a tidy map of the
 * orders that were *not* the problem. It is its own row, last, never folded into a zone.
 *
 * **What is counted is said.** Deliveries whose fee a tariff resolved, over closed business days
 * (today is not in it: the day is not closed), in this branch. A delivery priced outside the tariff
 * model is not in the figure, and the line under the table says so rather than calling it every
 * order. Money stays in minor units per currency and is formatted the way the brand writes money.
 *
 * **Without the outlines it is still a table.** A reader of reports who may not read delivery zones
 * (a separate capability) gets the counts with no names and no map, and the page says why; without
 * a map provider the shaded areas become the table alone, which is the honest state of an
 * environment with no key.
 */
@Component({
  selector: 'q-zone-density',
  imports: [TPipe, MapCanvas, ProvenanceBanner],
  templateUrl: './zone-density.html',
  styleUrl: './zone-density.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ZoneDensity {
  private readonly reporting = inject(ReportingApi);
  private readonly zonesApi = inject(DeliveryZonesApi);
  private readonly i18n = inject(I18n);
  private readonly registry = inject(PlatformLocales);

  /** The branch whose deliveries are counted; also the brand whose zones are drawn. */
  readonly scope = input.required<LocationScope>();
  /** `YYYY-MM-DD`, both inclusive, as every other report here takes them. */
  readonly from = input.required<string>();
  readonly to = input.required<string>();
  /** Where the map opens. */
  readonly center = input.required<LatLng>();

  protected readonly state = signal<LoadState>('idle');
  protected readonly provenance = signal<ProvenanceResponse | null>(null);
  private readonly densityRows = signal<readonly ZoneDensityRowResponse[]>([]);
  private readonly outlines = signal<readonly ZoneOutlineResponse[]>([]);
  private readonly zones = signal<readonly ZoneSummaryResponse[]>([]);
  /** Whether the brand's zones could be read at all: without them there are counts and no names. */
  protected readonly zonesReadable = signal(true);

  private generation = 0;

  protected readonly rows = computed<readonly DensityRow[]>(() => {
    const byZone = new Map<string, Map<string, { deliveries: number; fee: number }>>();
    let outside: Map<string, { deliveries: number; fee: number }> | null = null;
    const add = (
      target: Map<string, { deliveries: number; fee: number }>,
      row: ZoneDensityRowResponse,
    ): void => {
      const entry = target.get(row.currency) ?? { deliveries: 0, fee: 0 };
      entry.deliveries += row.deliveryCount;
      entry.fee += row.totalFeeMinor;
      target.set(row.currency, entry);
    };
    for (const row of this.densityRows()) {
      if (row.zoneId === null) {
        outside ??= new Map();
        add(outside, row);
      } else {
        const target = byZone.get(row.zoneId) ?? new Map();
        add(target, row);
        byZone.set(row.zoneId, target);
      }
    }
    // Every live zone has a row, even with nothing delivered into it: a zone nobody orders from
    // is exactly what this view is for finding.
    for (const outline of this.outlines()) {
      if (!byZone.has(outline.zoneId)) {
        byZone.set(outline.zoneId, new Map());
      }
    }
    const totalOf = (target: Map<string, { deliveries: number; fee: number }>): number =>
      [...target.values()].reduce((sum, entry) => sum + entry.deliveries, 0);
    const grand =
      [...byZone.values()].reduce((sum, target) => sum + totalOf(target), 0) +
      (outside ? totalOf(outside) : 0);
    const busiest = Math.max(0, ...[...byZone.values()].map(totalOf));
    const feesOf = (target: Map<string, { deliveries: number; fee: number }>): string =>
      [...target.entries()]
        .map(([currency, entry]) =>
          formatMoney({ amountMinor: entry.fee, currency }, this.i18n.locale(), { withUnit: true }),
        )
        .join(' · ') || '—';

    const rows: DensityRow[] = [...byZone.entries()].map(([zoneId, target]) => {
      const deliveries = totalOf(target);
      return {
        zoneId,
        name: this.nameOf(zoneId),
        deliveries,
        intensity: busiest === 0 ? 0 : deliveries / busiest,
        sharePercent: grand === 0 ? 0 : (deliveries / grand) * 100,
        fees: feesOf(target),
      };
    });
    rows.sort((a, b) => b.deliveries - a.deliveries || a.name.localeCompare(b.name));
    if (outside !== null) {
      const deliveries = totalOf(outside);
      rows.push({
        zoneId: null,
        name: this.i18n.t('reports.geography.density.outside'),
        deliveries,
        intensity: 0,
        sharePercent: grand === 0 ? 0 : (deliveries / grand) * 100,
        fees: feesOf(outside),
      });
    }
    return rows;
  });

  protected readonly areas = computed<readonly MapArea[]>(() => {
    const byZone = new Map(this.rows().map((row) => [row.zoneId, row]));
    return this.outlines().flatMap((outline) => {
      const row = byZone.get(outline.zoneId);
      return outerRings(outline).map((ring, index) => ({
        id: `${outline.zoneId}:${index}`,
        ring,
        intensity: row?.intensity ?? 0,
        label: this.i18n.t('reports.geography.density.areaLabel', {
          zone: row?.name ?? outline.code,
          count: row?.deliveries ?? 0,
        }),
      }));
    });
  });

  protected readonly total = computed(() =>
    this.rows().reduce((sum, row) => sum + row.deliveries, 0),
  );
  protected readonly outsideRow = computed(() => this.rows().find((row) => row.zoneId === null));
  protected readonly formatCount = formatCount;

  constructor() {
    effect(() => {
      const scope = this.scope();
      const from = this.from();
      const to = this.to();
      void this.load(scope, from, to);
    });
  }

  private nameOf(zoneId: string): string {
    const zone = this.zones().find((candidate) => candidate.zoneId === zoneId);
    if (zone) {
      return localisedName(this.i18n.locale(), zone, this.registry.fallbackOrder());
    }
    const outline = this.outlines().find((candidate) => candidate.zoneId === zoneId);
    return outline?.code ?? this.i18n.t('reports.geography.density.zoneUnknown');
  }

  protected retry(): void {
    void this.load(this.scope(), this.from(), this.to());
  }

  private async load(scope: LocationScope, from: string, to: string): Promise<void> {
    const generation = ++this.generation;
    this.state.set('loading');
    try {
      const [density, zones, outlines] = await Promise.all([
        this.reporting.zoneDensity(scope.tenantId, { from, to, locationId: [scope.locationId] }),
        this.zonesApi.list(scope).then(
          (list) => ({ list, readable: true }),
          () => ({ list: [] as readonly ZoneSummaryResponse[], readable: false }),
        ),
        this.zonesApi.activeOutlines(scope).catch((): readonly ZoneOutlineResponse[] => []),
      ]);
      if (generation !== this.generation) {
        return;
      }
      this.provenance.set(density.provenance);
      this.densityRows.set(density.zones);
      this.zones.set(zones.list);
      this.zonesReadable.set(zones.readable);
      this.outlines.set(outlines);
      this.state.set('ready');
    } catch (error) {
      if (error instanceof ApiError) {
        if (generation === this.generation) {
          this.state.set('error');
        }
      } else {
        throw error;
      }
    }
  }
}
