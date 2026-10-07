import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { boundsOf } from '../../shared/ui/map/geometry';
import { MapArea, MapCanvas } from '../../shared/ui/map/map-canvas';
import { LatLng, MapBounds, MapHandle } from '../../shared/ui/map/map-provider';
import { describeApiError } from '../orders/order-errors';
import {
  BatchImportResponse,
  BatchImportZoneRow,
  DeliveryZonesApi,
  RowOutcomeResponse,
} from './delivery-zones-api';
import { FALLBACK_MAP_CENTRE, MapRegionService } from './map-region';
import { OrderVerdict, ParsedGeoJson, parseGeoJson, regionVerdict } from './zone-geometry';
import { ZoneOutlineReview } from './zone-outline-review';
import { Toasts } from '../../shared/ui/toast';

/** How many of a row's corners the "beside its source" table lists. */
const SOURCE_CORNERS_SHOWN = 8;

const VERDICT_KEYS: Readonly<Record<OrderVerdict, MessageKey>> = {
  INSIDE: 'delivery.zones.review.verdict.INSIDE',
  OUTSIDE: 'delivery.zones.review.verdict.OUTSIDE',
  LIKELY_SWAPPED: 'delivery.zones.review.verdict.LIKELY_SWAPPED',
  NO_REGION: 'delivery.zones.review.verdict.NO_REGION',
};

/** What the page knows about one uploaded row's geometry, before anything is sent. */
interface RowGeometry {
  readonly parsed: ParsedGeoJson | null;
  readonly verdict: OrderVerdict | null;
}

/**
 * IA 3.6c — Bulk geozone upload.
 *
 * **Built**: a file picker over a JSON export of legacy zone geometry, a
 * row-level preview before anything is sent, a dry run that runs every
 * validity/area/region-bbox check a real import would and reports the
 * outcome without persisting, and the same batch committed for real —
 * landing every accepted row as a new zone's `DRAFT` version, exactly like
 * drawing one by hand.
 *
 * **Looked at on a map, beside its source (ADR 0145, ADR 0037).** Each row's geometry is read
 * from the file as GeoJSON (`[longitude, latitude]`, which is what every producer writes) and
 * checked against its region's box *before* anything is sent; the verdict names the mistake legacy
 * exports actually contain, coordinates written the wrong way round, which is valid geometry that
 * lands somewhere else and which no containment test complains about. "Show" draws the row on the
 * map with the region's box and, beside it, the corners exactly as the file wrote them next to how
 * they were read. After a real import, a row that was saved can be reviewed and activated **from
 * here** through the same review the zones page uses (`q-zone-outline-review`), which reads the
 * stored geometry back from the server: the file is what was meant, the stored version is what will
 * govern a fee, and the reviewer sees the second. Activation is never one click.
 *
 * **With no map provider** the table and the verdict still work; the map says why it is not drawn
 * and the review's confirmation says that coordinates, not a map, were checked.
 *
 * **This is not `q-import-wizard`.** `P26` owns that shared component
 * (FileDropzone, dry-run diff, JobProgress, ResultSummary) and it is not
 * merged yet, so this page is the minimum local equivalent — a plain file
 * input and two tables — built to be replaced by the shared wizard without
 * changing the API calls underneath it.
 */
@Component({
  selector: 'q-geozone-batch-import-page',
  imports: [TPipe, MapCanvas, ZoneOutlineReview],
  templateUrl: './geozone-batch-import-page.html',
  styleUrl: './geozone-batch-import-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GeozoneBatchImportPage implements OnInit {
  private readonly api = inject(DeliveryZonesApi);
  private readonly brand = inject(CurrentBrand);
  private readonly toasts = inject(Toasts);
  private readonly regions = inject(MapRegionService);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);

  protected readonly fileName = signal<string | null>(null);
  protected readonly parseError = signal<string | null>(null);
  protected readonly rows = signal<readonly BatchImportZoneRow[]>([]);

  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly report = signal<BatchImportResponse | null>(null);

  /** The row being looked at on the map, by its `externalRef`. */
  protected readonly previewRef = signal<string | null>(null);
  /** The saved version being reviewed for activation, from a committed report row. */
  protected readonly reviewing = signal<{
    readonly zoneId: string;
    readonly version: number;
    readonly code: string;
    readonly regionId: string | null;
  } | null>(null);
  protected readonly reviewBusy = signal(false);
  protected readonly reviewError = signal<string | null>(null);
  /** Versions this page activated, so a row says so and does not offer it twice. */
  protected readonly activated = signal<ReadonlySet<string>>(new Set());

  protected readonly brandScope = computed(() => this.brand.scope());

  /** Each row's geometry read and judged once per upload, never per change-detection pass. */
  protected readonly geometry = computed<ReadonlyMap<string, RowGeometry>>(() => {
    const byRef = new Map<string, RowGeometry>();
    for (const row of this.rows()) {
      const parsed = parseGeoJson(row.geoJson);
      const region = this.regionBoundsOf(row.regionId);
      byRef.set(row.externalRef, {
        parsed,
        verdict: parsed === null ? null : regionVerdict(parsed.rings.flat(), region),
      });
    }
    return byRef;
  });

  protected readonly previewRow = computed<BatchImportZoneRow | null>(
    () => this.rows().find((row) => row.externalRef === this.previewRef()) ?? null,
  );
  protected readonly previewRings = computed<readonly (readonly LatLng[])[]>(() => {
    const row = this.previewRow();
    return row === null ? [] : (this.geometry().get(row.externalRef)?.parsed?.rings ?? []);
  });
  protected readonly previewAreas = computed<readonly MapArea[]>(() =>
    this.previewRings().map((ring, index) => ({ id: `row-${index}`, ring })),
  );
  protected readonly previewRegion = computed<MapBounds | null>(() => {
    const row = this.previewRow();
    return row === null ? null : this.regionBoundsOf(row.regionId);
  });
  protected readonly previewFit = computed<MapBounds | null>(() => {
    const corners = this.previewRings().flat();
    const region = this.previewRegion();
    const own = boundsOf(corners);
    if (own === null) {
      return region;
    }
    return region === null
      ? own
      : (boundsOf([...corners, region.southWest, region.northEast]) ?? own);
  });
  protected readonly previewCentre = computed<LatLng>(
    () => this.previewRings()[0]?.[0] ?? this.previewRegion()?.southWest ?? FALLBACK_MAP_CENTRE,
  );
  protected readonly previewSourceCorners = computed(() =>
    this.previewRings().flat().slice(0, SOURCE_CORNERS_SHOWN),
  );
  protected readonly previewSimplified = computed(() => {
    const row = this.previewRow();
    return row !== null && (this.geometry().get(row.externalRef)?.parsed?.simplified ?? false);
  });

  async ngOnInit(): Promise<void> {
    // The regions are read beside the brand and never block the page: a tenant that may not read
    // them still imports, and its rows say there was no region to check against.
    await Promise.all([this.brand.ensureLoaded(), this.regions.ensureLoaded()]);
    this.denied.set(this.brand.denied());
    this.loading.set(false);
  }

  private regionBoundsOf(regionId: string | null | undefined): MapBounds | null {
    return this.regions.regionFor(regionId)?.bounds ?? null;
  }

  protected verdictKey(row: BatchImportZoneRow): MessageKey | null {
    const verdict = this.geometry().get(row.externalRef)?.verdict ?? null;
    return verdict === null ? null : VERDICT_KEYS[verdict];
  }

  protected verdictOf(row: BatchImportZoneRow): OrderVerdict | null {
    return this.geometry().get(row.externalRef)?.verdict ?? null;
  }

  protected canDraw(row: BatchImportZoneRow): boolean {
    return this.geometry().get(row.externalRef)?.parsed != null;
  }

  protected showOnMap(row: BatchImportZoneRow): void {
    this.previewRef.set(row.externalRef);
  }

  protected closePreview(): void {
    this.previewRef.set(null);
  }

  /** Draws the region's box beside the row, so "outside" is something a person can see. */
  protected onPreviewReady(map: MapHandle): void {
    const region = this.previewRegion();
    if (region !== null) {
      map.addRectangle({ bounds: region, editable: false });
    }
  }

  // ------------------------------------------------- review and activate

  /** Whether a saved row can be reviewed for activation: it was committed, accepted, and not yet activated. */
  protected canReview(row: BatchImportZoneRow): boolean {
    const outcome = this.rowOutcomeFor(row);
    const report = this.report();
    return (
      report !== null &&
      !report.dryRun &&
      outcome?.accepted === true &&
      !!outcome.zoneId &&
      outcome.version != null &&
      !this.isActivated(row)
    );
  }

  protected isActivated(row: BatchImportZoneRow): boolean {
    const outcome = this.rowOutcomeFor(row);
    return outcome?.zoneId != null && this.activated().has(`${outcome.zoneId}:${outcome.version}`);
  }

  protected openReview(row: BatchImportZoneRow): void {
    const outcome = this.rowOutcomeFor(row);
    if (!outcome?.zoneId || outcome.version == null) {
      return;
    }
    this.reviewError.set(null);
    this.reviewing.set({
      zoneId: outcome.zoneId,
      version: outcome.version,
      code: row.code,
      regionId: row.regionId ?? null,
    });
  }

  protected closeReview(): void {
    if (!this.reviewBusy()) {
      this.reviewing.set(null);
    }
  }

  protected reviewRegionBounds(): MapBounds | null {
    return this.regionBoundsOf(this.reviewing()?.regionId);
  }

  /** The reviewer confirmed: activate the version that was looked at, and nothing else. */
  protected async confirmActivation(): Promise<void> {
    const scope = this.brand.scope();
    const review = this.reviewing();
    if (!scope || !review || this.reviewBusy()) {
      return;
    }
    this.reviewBusy.set(true);
    this.reviewError.set(null);
    try {
      await this.api.activate(scope, review.zoneId, review.version);
      this.activated.update((done) => new Set(done).add(`${review.zoneId}:${review.version}`));
      this.reviewing.set(null);
      this.toasts.show({
        message: this.i18n.t('delivery.zoneImport.activatedToast', {
          code: review.code,
          version: review.version,
        }),
        tone: 'success',
      });
    } catch (error) {
      this.reviewError.set(this.describe(error));
    } finally {
      this.reviewBusy.set(false);
    }
  }

  protected async onFileSelected(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
      return;
    }
    this.fileName.set(file.name);
    this.report.set(null);
    this.actionError.set(null);
    this.previewRef.set(null);
    this.activated.set(new Set());
    try {
      const text = await file.text();
      const parsed = JSON.parse(text) as unknown;
      this.rows.set(parseRows(parsed));
      this.parseError.set(null);
    } catch {
      this.rows.set([]);
      this.parseError.set(this.i18n.t('delivery.zoneImport.parseError'));
    } finally {
      input.value = '';
    }
  }

  protected clear(): void {
    this.fileName.set(null);
    this.rows.set([]);
    this.parseError.set(null);
    this.report.set(null);
    this.actionError.set(null);
    this.previewRef.set(null);
    this.reviewing.set(null);
    this.activated.set(new Set());
  }

  protected async dryRun(): Promise<void> {
    await this.submit(true);
  }

  protected async commit(): Promise<void> {
    await this.submit(false);
  }

  private async submit(dryRun: boolean): Promise<void> {
    const scope = this.brand.scope();
    const rows = this.rows();
    if (!scope || rows.length === 0) {
      return;
    }
    this.busy.set(true);
    this.actionError.set(null);
    try {
      const result = await this.api.importBatch(scope, { dryRun, rows });
      this.report.set(result);
      if (!dryRun) {
        this.toasts.show({
          message: this.i18n.t('delivery.zoneImport.committed', { count: result.accepted }),
          tone: result.rejected === 0 ? 'success' : 'info',
        });
      }
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.busy.set(false);
    }
  }

  protected rowOutcomeFor(row: BatchImportZoneRow): RowOutcomeResponse | null {
    const report = this.report();
    if (!report) {
      return null;
    }
    return report.rows.find((outcome) => outcome.externalRef === row.externalRef) ?? null;
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}

/**
 * Validates the uploaded JSON is an array of rows carrying at least the
 * fields the batch endpoint requires, defaulting `role` to `DELIVERY` (the
 * shape almost every legacy export is) when the source file omits it.
 */
function parseRows(parsed: unknown): readonly BatchImportZoneRow[] {
  if (!Array.isArray(parsed)) {
    throw new Error('Expected a JSON array of rows');
  }
  return parsed.map((entry, index) => {
    if (typeof entry !== 'object' || entry === null) {
      throw new Error(`Row ${index} is not an object`);
    }
    const row = entry as Record<string, unknown>;
    const externalRef = requireString(row, 'externalRef', index);
    const code = requireString(row, 'code', index);
    const geoJson = requireString(row, 'geoJson', index);
    const currency = requireString(row, 'currency', index);
    return {
      externalRef,
      role: row['role'] === 'CATCHMENT' ? 'CATCHMENT' : 'DELIVERY',
      code,
      displayNameRu: optionalString(row, 'displayNameRu') ?? code,
      displayNameUz: optionalString(row, 'displayNameUz') ?? code,
      displayNameEn: optionalString(row, 'displayNameEn') ?? code,
      regionId: optionalString(row, 'regionId'),
      priority: typeof row['priority'] === 'number' ? row['priority'] : 0,
      currency,
      deliveryTariffId: optionalString(row, 'deliveryTariffId'),
      freeDeliveryFromMinor: optionalNumber(row, 'freeDeliveryFromMinor'),
      minBasketMinor: optionalNumber(row, 'minBasketMinor'),
      geoJson,
    } satisfies BatchImportZoneRow;
  });
}

function requireString(row: Record<string, unknown>, field: string, index: number): string {
  const value = row[field];
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error(`Row ${index} is missing "${field}"`);
  }
  return value;
}

function optionalString(row: Record<string, unknown>, field: string): string | undefined {
  const value = row[field];
  return typeof value === 'string' && value.trim() !== '' ? value : undefined;
}

function optionalNumber(row: Record<string, unknown>, field: string): number | undefined {
  const value = row[field];
  return typeof value === 'number' ? value : undefined;
}
