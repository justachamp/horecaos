import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { CurrentLocation } from '../../core/auth/current-location';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { PlatformLocales } from '../../core/i18n/platform-locales';
import {
  LabelsByLocale,
  labelDrafts,
  changedLabels,
  labelsToSend,
  localeDisplayName,
  platformColumns,
} from '../../core/i18n/locale-labels';
import { LocaleSet } from '../../core/i18n/locale-set';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { RingProblem } from '../../shared/ui/map/geometry';
import { LatLng, MapBounds } from '../../shared/ui/map/map-provider';
import { PolygonEditor } from '../../shared/ui/map/polygon-editor';
import { describeApiError } from '../orders/order-errors';
import {
  DeliveryZonesApi,
  ZoneDetailResponse,
  ZoneSummaryResponse,
  ZoneVersionResponse,
} from './delivery-zones-api';
import { DeliveryTariffsApi, TariffSummaryResponse } from './delivery-tariffs-api';
import { localisedName } from './localised-name';
import { FALLBACK_MAP_CENTRE, boundsOfRegion, centreOfRegion } from './map-region';
import { RegionResponse, RegionsApi } from './regions-api';
import { ZoneOutlineReview } from './zone-outline-review';
import { editableRing, toGeoJsonPolygon } from './zone-geometry';

/**
 * Enum-to-catalogue lookups.
 *
 * `MessageKey` is the union of every key in `messages.en.ts`, which is what
 * makes a missing translation a compile error. Concatenating a key in a
 * template produces a plain `string` and gives that up, so each enum gets a
 * table with an explicit fallback.
 */
const ROLE_KEYS: Readonly<Record<string, MessageKey>> = {
  DELIVERY: 'delivery.zones.role.DELIVERY',
  CATCHMENT: 'delivery.zones.role.CATCHMENT',
};

const VERSION_STATUS_KEYS: Readonly<Record<string, MessageKey>> = {
  DRAFT: 'delivery.zones.versionStatus.DRAFT',
  ACTIVE: 'delivery.zones.versionStatus.ACTIVE',
  RETIRED: 'delivery.zones.versionStatus.RETIRED',
  DISCARDED: 'delivery.zones.versionStatus.DISCARDED',
};

/** A tariff as this page needs to talk about it: a name to show and whether it prices to nothing. */
interface TariffOption {
  readonly tariffId: string;
  readonly label: string;
  readonly free: boolean;
}

/**
 * Delivery zones — operations §3.6, and §3.6d with it.
 *
 * **What changed in this wave (ADR 0104).**
 *
 * 1. **The draft carries its tariff.** `submitDraft` used to build a body with
 *    no `deliveryTariffId` although the client type and the endpoint both had
 *    the field, so every zone a console user drew carried a null tariff and
 *    ADR 0037's zone-beats-branch precedence had never once been exercised
 *    from the product. The form now offers the brand's rate tables.
 * 2. **A free geozone is that binding and nothing else.** `ZoneRole`'s own
 *    Javadoc refuses a third geometry layer — "a 'free geozone' is not a third
 *    role: it is a DELIVERY zone whose tariff resolves to zero" — so §3.6d is
 *    discharged by the tariff select plus the «бесплатно» marker on the list,
 *    derived from the bound tariff's live version.
 * 3. **`CATCHMENT` is offered.** The create form hard-coded `DELIVERY`, which
 *    made the branch-containment guard unreachable. A `CATCHMENT` zone carries
 *    no tariff and no thresholds — `ck_zone_version_catchment_is_not_priced`
 *    refuses them — so the form hides those fields for it rather than letting
 *    the database answer.
 * 4. **A name per locale, not one string written three times.** Row 10.12:
 *    the create form and the rename form offer the *brand's own* supported
 *    languages (`LocaleSet`, default first) instead of a fixed ru/uz/en
 *    triple; the brand's default language is the one name a zone must have.
 *    **A language the brand does not offer is never touched by a rename**: the
 *    body carries only the offered, filled-in names ({@link labelsToSend}) —
 *    and, on a rename, only those the operator changed ({@link changedLabels};
 *    the rename is unversioned, so an untouched name would overwrite another
 *    operator's newer one) — the server writes exactly the languages named
 *    (`PUT .../service-zones/{zoneId}/names`), and the zone keeps the rest.
 * 5. **Draft, activate and bind are three acts.** They used to run inside one
 *    `submitDraft`, so a mis-typed radius went live and stayed live. There is
 *    now a version list, a deactivate and an unbind.
 *
 * **Drawn on a map (ADR 0145, rows `3.6` and `3.6c`).** A zone is no longer only a radius around a
 * branch. The draft form offers a second shape, a polygon drawn corner by corner (`q-polygon-editor`,
 * with the region's box on the map and a corner table that works without one), and a stored version
 * can be opened in that editor to draft its successor (versions are immutable: an edit is a new
 * draft, and the one it started from is left alone). Neither shape governs anything when saved.
 * **Activation passes through a review** (`q-zone-outline-review`): the stored outline on the map
 * beside the region it must sit in, a verdict that names the likeliest mistake (coordinates written
 * the wrong way round), the corners as numbers, the bound tariff, and a confirmation that is the
 * gate. ADR 0037 asks for exactly that look before geometry governs a fee, because a swapped pair is
 * valid geometry that lands somewhere else and no containment test complains. Bulk geozone upload
 * (`3.6c`) has its own page, `geozone-batch-import-page.ts`, which opens the same review for the
 * versions it drafted.
 *
 * **With no map provider** (no key has been obtained in this environment) the editor and the review
 * say so in words and keep working from their coordinate tables; the review's confirmation then says
 * that coordinates, not a map, were checked.
 */
@Component({
  selector: 'q-delivery-zones-page',
  imports: [TPipe, RouterLink, PolygonEditor, ZoneOutlineReview],
  templateUrl: './delivery-zones-page.html',
  styleUrl: './delivery-zones-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DeliveryZonesPage implements OnInit {
  private readonly api = inject(DeliveryZonesApi);
  private readonly tariffsApi = inject(DeliveryTariffsApi);
  private readonly regionsApi = inject(RegionsApi);
  private readonly brand = inject(CurrentBrand);
  private readonly location = inject(CurrentLocation);
  private readonly localeSet = inject(LocaleSet);
  protected readonly i18n = inject(I18n);
  private readonly registry = inject(PlatformLocales);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly zones = signal<readonly ZoneSummaryResponse[]>([]);
  protected readonly tariffs = signal<readonly TariffOption[]>([]);
  protected readonly regions = signal<readonly RegionResponse[]>([]);

  protected readonly expandedZoneId = signal<string | null>(null);
  protected readonly detailByZoneId = signal<ReadonlyMap<string, ZoneDetailResponse>>(new Map());
  protected readonly versionsByZoneId = signal<ReadonlyMap<string, readonly ZoneVersionResponse[]>>(
    new Map(),
  );
  protected readonly rowError = signal<string | null>(null);
  protected readonly busyZoneId = signal<string | null>(null);

  protected readonly showCreateForm = signal(false);
  protected readonly createSubmitting = signal(false);
  protected readonly createError = signal<string | null>(null);
  protected readonly newRole = signal<'DELIVERY' | 'CATCHMENT'>('DELIVERY');
  protected readonly newCode = signal('');
  protected readonly newNames = signal<LabelsByLocale>({});

  /** Row 10.12: the languages a zone's name is offered in — the brand's own set, default first. */
  protected readonly locales = computed<readonly string[]>(() => this.localeSet.locales());
  protected readonly defaultLocale = computed<string>(() => this.localeSet.defaultLocale());

  protected readonly renamingZone = signal<ZoneSummaryResponse | null>(null);
  protected readonly renameNames = signal<LabelsByLocale>({});
  protected readonly renameSubmitting = signal(false);
  protected readonly renameError = signal<string | null>(null);

  protected readonly draftingZone = signal<ZoneSummaryResponse | null>(null);
  protected readonly draftSubmitting = signal(false);
  protected readonly draftError = signal<string | null>(null);
  protected readonly draftProblems = signal<readonly string[]>([]);
  protected readonly draftOriginLocationId = signal<string>('');
  protected readonly draftRadiusMeters = signal(3000);
  protected readonly draftPriority = signal(0);
  protected readonly draftCurrency = signal('UZS');
  protected readonly draftRegionId = signal<string>('');
  protected readonly draftTariffId = signal<string>('');
  protected readonly draftFreeFromMinor = signal<number | null>(null);
  protected readonly draftMinBasketMinor = signal<number | null>(null);

  // ---------------------------------------------------- ADR 0145: drawn zones
  /** `CIRCLE` is the original radius around a branch; `POLYGON` is drawn on the map. */
  protected readonly draftShape = signal<'CIRCLE' | 'POLYGON'>('CIRCLE');
  protected readonly draftRing = signal<readonly LatLng[]>([]);
  protected readonly draftRingProblems = signal<readonly RingProblem[]>([]);
  /** The version whose outline the polygon editor started from, if it did. */
  protected readonly draftFromVersion = signal<number | null>(null);

  /** The version being looked at on the map, and whether looking is all that is being done. */
  protected readonly reviewing = signal<{
    readonly zone: ZoneSummaryResponse;
    readonly version: number;
    readonly mode: 'view' | 'activate';
    readonly regionId: string | null;
    /**
     * The tariff bound to **this version**, which is what the review must disclose. The zone
     * summary's `deliveryTariffId` is the *active* version's, and a draft that changes the tariff
     * (or a zone with no active version at all) would otherwise be reviewed under the wrong fee.
     */
    readonly tariffId: string | null;
  } | null>(null);

  protected readonly brandScope = computed(() => this.brand.scope());

  /** The box the polygon editor opens on and checks against: the chosen region's, else the first. */
  protected readonly draftRegionBounds = computed<MapBounds | null>(() => {
    const region = this.regionById(this.draftRegionId());
    return region === null ? null : boundsOfRegion(region);
  });
  protected readonly draftCentre = computed<LatLng>(() => {
    const region = this.regionById(this.draftRegionId());
    return region === null ? FALLBACK_MAP_CENTRE : centreOfRegion(region);
  });

  protected readonly reviewRegionBounds = computed<MapBounds | null>(() => {
    const review = this.reviewing();
    const region = review === null ? null : this.regionById(review.regionId ?? '');
    return region === null ? null : boundsOfRegion(region);
  });
  protected readonly reviewTariff = computed<string | null>(() => {
    const review = this.reviewing();
    if (review === null) {
      return null;
    }
    const label = this.tariffLabelOf(review.tariffId);
    return label === '—' ? null : label;
  });

  protected readonly bindingZoneId = signal<string | null>(null);
  protected readonly bindLocationId = signal<string>('');

  /** `CATCHMENT` decides candidacy and never price, so the priced half of the form is hidden for it. */
  protected readonly draftIsPriced = computed(() => this.draftingZone()?.role !== 'CATCHMENT');

  protected readonly branchOptions = computed(() => this.location.options());

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    // Row 10.12: resolved alongside the brand scope, so the name form never
    // sticks on LocaleSet's platform fallback for a configured brand.
    await Promise.all([this.brand.ensureLoaded(), this.localeSet.ensureLoaded()]);
    const scope = this.brand.scope();
    if (!scope) {
      this.denied.set(this.brand.denied());
      this.loading.set(false);
      return;
    }
    try {
      this.zones.set(await this.api.list(scope));
      await this.loadTariffOptions();
      await this.loadRegions(scope.tenantId);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(this.describe(error));
      }
    } finally {
      this.loading.set(false);
    }
  }

  /**
   * Names for the tariff column, and the zero-fee fact behind «бесплатно».
   *
   * The list read carries no bands, so "does this tariff resolve to zero" can
   * only be answered from the detail. That detail is fetched for the tariffs
   * actually bound to a zone, not for all of them — a brand with hundreds of
   * rate tables and three zones makes three calls. A brand that binds hundreds
   * of *distinct* tariffs to zones would want the fact on the list read
   * instead; ADR 0104 names that trade-off.
   */
  private async loadTariffOptions(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    const summaries = await this.tariffsApi.list(scope);
    const boundIds = new Set(
      this.zones()
        .map((zone) => zone.deliveryTariffId)
        .filter((id): id is string => !!id),
    );
    const freeIds = new Set<string>();
    for (const id of boundIds) {
      try {
        const detail = await this.tariffsApi.detail(scope, id);
        if (pricesToZero(detail.activeVersion)) {
          freeIds.add(id);
        }
      } catch {
        // A tariff whose detail cannot be read is simply not marked free.
        // Claiming free delivery because a read failed is the one wrong answer.
      }
    }
    this.tariffs.set(
      summaries.map((summary: TariffSummaryResponse) => ({
        tariffId: summary.tariffId,
        label: `${summary.code} — ${summary.name}`,
        free: freeIds.has(summary.tariffId),
      })),
    );
  }

  private async loadRegions(tenantId: string): Promise<void> {
    try {
      this.regions.set((await this.regionsApi.list(tenantId)).filter((r) => r.status === 'ACTIVE'));
    } catch {
      // Regions are optional on a zone. A tenant whose grant does not cover
      // them still gets a working zone form, with the region select empty.
      this.regions.set([]);
    }
  }

  // ---------------------------------------------------------------- reading

  protected tariffLabel(zone: ZoneSummaryResponse): string {
    return this.tariffLabelOf(zone.deliveryTariffId);
  }

  /** The tariff's name, its id when the brand's list does not carry it, and a dash when there is none. */
  private tariffLabelOf(id: string | null | undefined): string {
    if (!id) {
      return '—';
    }
    return this.tariffs().find((option) => option.tariffId === id)?.label ?? id;
  }

  /** §3.6d: a zone bound to a tariff whose live version prices to nothing. */
  protected isFree(zone: ZoneSummaryResponse): boolean {
    const id = zone.deliveryTariffId;
    return !!id && this.tariffs().some((option) => option.tariffId === id && option.free);
  }

  /**
   * A zone threshold written the way the brand writes money (Settings 10.12). The summary names
   * the zone's currency; a platform that predates the field only ever priced in UZS.
   */
  protected threshold(zone: ZoneSummaryResponse, minor: number): string {
    return formatMoney(
      { amountMinor: minor, currency: zone.currency ?? 'UZS' },
      this.i18n.locale(),
      {
        withUnit: true,
      },
    );
  }

  /** The zone's name in the operator's own locale — the point of authoring three. */
  protected zoneName(zone: ZoneSummaryResponse): string {
    return localisedName(this.i18n.locale(), zone, this.registry.fallbackOrder());
  }

  protected roleKey(role: string): MessageKey {
    return ROLE_KEYS[role] ?? 'delivery.zones.role.DELIVERY';
  }

  protected versionStatusKey(status: string): MessageKey {
    return VERSION_STATUS_KEYS[status] ?? 'delivery.zones.versionStatus.DRAFT';
  }

  protected isExpanded(zone: ZoneSummaryResponse): boolean {
    return this.expandedZoneId() === zone.zoneId;
  }

  protected async toggleExpand(zone: ZoneSummaryResponse): Promise<void> {
    if (this.isExpanded(zone)) {
      this.expandedZoneId.set(null);
      return;
    }
    this.expandedZoneId.set(zone.zoneId);
    await this.refreshRow(zone.zoneId);
  }

  private async refreshRow(zoneId: string): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    try {
      const [detail, versions] = await Promise.all([
        this.api.detail(scope, zoneId),
        this.api.versions(scope, zoneId),
      ]);
      this.detailByZoneId.update((current) => new Map(current).set(zoneId, detail));
      this.versionsByZoneId.update((current) => new Map(current).set(zoneId, versions));
    } catch (error) {
      this.rowError.set(this.describe(error));
    }
  }

  protected detailFor(zone: ZoneSummaryResponse): ZoneDetailResponse | null {
    return this.detailByZoneId().get(zone.zoneId) ?? null;
  }

  protected versionsFor(zone: ZoneSummaryResponse): readonly ZoneVersionResponse[] {
    return this.versionsByZoneId().get(zone.zoneId) ?? [];
  }

  protected branchName(locationId: string): string {
    return (
      this.branchOptions().find((option) => option.id === locationId)?.displayName ?? locationId
    );
  }

  // --------------------------------------------------------------- create

  protected openCreateForm(): void {
    this.newRole.set('DELIVERY');
    this.newCode.set('');
    this.newNames.set({});
    this.createError.set(null);
    this.showCreateForm.set(true);
  }

  protected closeCreateForm(): void {
    this.showCreateForm.set(false);
  }

  protected canCreate(): boolean {
    return (
      !this.createSubmitting() &&
      this.newCode().trim().length > 0 &&
      // The brand's default language is the one name a zone must have.
      (this.newNames()[this.defaultLocale()] ?? '').trim().length > 0
    );
  }

  protected async submitCreate(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || !this.canCreate()) {
      return;
    }
    this.createSubmitting.set(true);
    this.createError.set(null);
    try {
      // The platform triple stays required by the contract: a platform language
      // the brand does not offer takes the default language's name.
      const columns = platformColumns(this.locales(), this.newNames(), this.defaultLocale());
      await this.api.create(scope, {
        role: this.newRole(),
        code: this.newCode().trim(),
        displayNameRu: columns.ru,
        displayNameUz: columns['uz-Latn'],
        displayNameEn: columns.en,
        displayNames: labelsToSend(this.locales(), this.newNames()),
      });
      this.showCreateForm.set(false);
      await this.load();
    } catch (error) {
      this.createError.set(this.describe(error));
    } finally {
      this.createSubmitting.set(false);
    }
  }

  // --------------------------------------------------------------- names

  protected localeName(locale: string): string {
    return localeDisplayName(this.i18n, locale);
  }

  protected isDefault(locale: string): boolean {
    return locale === this.defaultLocale();
  }

  protected setNewName(locale: string, value: string): void {
    this.newNames.update((names) => ({ ...names, [locale]: value }));
  }

  protected setRenameName(locale: string, value: string): void {
    this.renameNames.update((names) => ({ ...names, [locale]: value }));
  }

  /** Languages the zone is named in that the brand does not offer — kept, not shown. */
  protected hiddenLocales(zone: ZoneSummaryResponse | null): readonly string[] {
    const offered = new Set(this.locales());
    return Object.keys(zone?.displayNames ?? {}).filter((locale) => !offered.has(locale));
  }

  protected openRenameForm(zone: ZoneSummaryResponse): void {
    this.renameNames.set(labelDrafts(this.locales(), zone.displayNames ?? triple(zone)));
    this.renameError.set(null);
    this.renamingZone.set(zone);
  }

  protected closeRenameForm(): void {
    this.renamingZone.set(null);
  }

  /** The names the dialog changed: an offered language, filled in, whose text differs from what it opened with. */
  private renameChanges(): Record<string, string> {
    const zone = this.renamingZone();
    return zone
      ? changedLabels(this.locales(), this.renameNames(), zone.displayNames ?? triple(zone))
      : {};
  }

  protected canRename(): boolean {
    return !this.renameSubmitting() && Object.keys(this.renameChanges()).length > 0;
  }

  /**
   * Writes the names the operator changed in the offered languages and no
   * other — a language the brand does not offer is not in the body and keeps
   * its name, a blank field is left out rather than sent as an empty string
   * (row 10.12's never-delete guarantee, client half), and a language left as
   * the dialog loaded it is left out too: the rename is unversioned, so
   * resending an untouched name would overwrite what another operator saved
   * since the list was read.
   */
  protected async submitRename(): Promise<void> {
    const scope = this.brand.scope();
    const zone = this.renamingZone();
    if (!scope || !zone || !this.canRename()) {
      return;
    }
    this.renameSubmitting.set(true);
    this.renameError.set(null);
    try {
      await this.api.rename(scope, zone.zoneId, this.renameChanges());
      this.renamingZone.set(null);
      this.zones.set(await this.api.list(scope));
    } catch (error) {
      this.renameError.set(this.describe(error));
    } finally {
      this.renameSubmitting.set(false);
    }
  }

  // ---------------------------------------------------------------- draft

  protected openDraftForm(zone: ZoneSummaryResponse): void {
    this.draftOriginLocationId.set(this.location.scope()?.locationId ?? '');
    this.draftRadiusMeters.set(3000);
    this.draftPriority.set(zone.priority ?? 0);
    this.draftCurrency.set(zone.currency ?? 'UZS');
    this.draftRegionId.set('');
    this.draftTariffId.set(zone.deliveryTariffId ?? '');
    this.draftFreeFromMinor.set(zone.freeDeliveryFromMinor ?? null);
    this.draftMinBasketMinor.set(zone.minBasketMinor ?? null);
    this.draftError.set(null);
    this.draftProblems.set([]);
    this.draftShape.set('CIRCLE');
    this.draftRing.set([]);
    this.draftRingProblems.set([]);
    this.draftFromVersion.set(null);
    this.draftingZone.set(zone);
  }

  /**
   * Opens the draft form on a polygon, starting from a stored version's outline (row `3.6`).
   * Versions are immutable (ADR 0037), so "edit" is always "draft the next one from this one";
   * what the editor would silently flatten (holes, several parts) is refused up front rather than
   * saved as something the operator did not draw.
   */
  protected async editOutline(
    zone: ZoneSummaryResponse,
    version: ZoneVersionResponse,
  ): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.rowError.set(null);
    let ring: readonly LatLng[] | null;
    try {
      ring = editableRing(await this.api.outline(scope, zone.zoneId, version.version));
    } catch {
      this.rowError.set(this.i18n.t('delivery.zones.map.outlineFailed'));
      return;
    }
    if (ring === null) {
      this.rowError.set(this.i18n.t('delivery.zones.map.notEditable'));
      return;
    }
    this.openDraftForm(zone);
    this.draftShape.set('POLYGON');
    this.draftRing.set(ring);
    this.draftFromVersion.set(version.version);
    this.draftPriority.set(version.priority);
    this.draftCurrency.set(version.currency);
    this.draftRegionId.set(version.regionId ?? '');
    this.draftTariffId.set(version.deliveryTariffId ?? '');
    this.draftFreeFromMinor.set(version.freeDeliveryFromMinor ?? null);
    this.draftMinBasketMinor.set(version.minBasketMinor ?? null);
  }

  protected setDraftShape(shape: 'CIRCLE' | 'POLYGON'): void {
    this.draftShape.set(shape);
    if (shape === 'CIRCLE') {
      this.draftFromVersion.set(null);
    }
  }

  protected onRingChange(ring: readonly LatLng[]): void {
    this.draftRing.set(ring);
  }

  protected onRingProblems(problems: readonly RingProblem[]): void {
    this.draftRingProblems.set(problems);
  }

  /** The region by id, or the first one when none is chosen; `null` when the tenant has no region to read. */
  private regionById(regionId: string): RegionResponse | null {
    const all = this.regions();
    return (
      (regionId ? all.find((region) => region.regionId === regionId) : undefined) ?? all[0] ?? null
    );
  }

  protected closeDraftForm(): void {
    this.draftingZone.set(null);
  }

  protected canDraft(): boolean {
    if (this.draftSubmitting()) {
      return false;
    }
    if (this.draftShape() === 'POLYGON') {
      // The editor reports what is wrong with the outline (too few corners, a crossing, a corner
      // outside the region); an outline with any of it is not saved, so the round trip that
      // would come back with the same list is skipped. The server still decides.
      return this.draftRing().length >= 3 && this.draftRingProblems().length === 0;
    }
    return this.draftRadiusMeters() > 0 && this.draftOriginLocationId().length > 0;
  }

  /**
   * Drafts a version and stops there.
   *
   * Activation is a separate click, because it is a separate capability
   * (`DELIVERY_ZONE_ACTIVATE`) and a separate decision: drawing a circle is
   * routine, deciding it governs what the platform will sell is not.
   */
  protected async submitDraft(): Promise<void> {
    const scope = this.brand.scope();
    const zone = this.draftingZone();
    if (!scope || !zone || !this.canDraft()) {
      return;
    }
    const priced = zone.role !== 'CATCHMENT';
    this.draftSubmitting.set(true);
    this.draftError.set(null);
    this.draftProblems.set([]);
    try {
      const terms = {
        regionId: this.draftRegionId() || null,
        priority: this.draftPriority(),
        currency: this.draftCurrency(),
        deliveryTariffId: priced ? this.draftTariffId() || null : null,
        freeDeliveryFromMinor: priced ? this.draftFreeFromMinor() : null,
        minBasketMinor: priced ? this.draftMinBasketMinor() : null,
      };
      if (this.draftShape() === 'POLYGON') {
        await this.api.draftPolygonVersion(scope, zone.zoneId, {
          ...terms,
          geoJson: toGeoJsonPolygon(this.draftRing()),
        });
      } else {
        await this.api.draftCircleVersion(scope, zone.zoneId, {
          ...terms,
          originLocationId: this.draftOriginLocationId(),
          radiusMeters: this.draftRadiusMeters(),
        });
      }
      this.draftingZone.set(null);
      this.expandedZoneId.set(zone.zoneId);
      await this.refreshRow(zone.zoneId);
      await this.load();
    } catch (error) {
      this.draftError.set(this.describe(error));
      this.draftProblems.set(problemsOf(error));
    } finally {
      this.draftSubmitting.set(false);
    }
  }

  // ------------------------------------------------------------ lifecycle

  /**
   * Opens a version's review: on the map beside its region (`view`), or as the gate in front of
   * activation (`activate`). Activation is never one click from the version list any more — see
   * the class doc for why.
   */
  protected openReview(
    zone: ZoneSummaryResponse,
    version: ZoneVersionResponse,
    mode: 'view' | 'activate',
  ): void {
    this.rowError.set(null);
    this.reviewing.set({
      zone,
      version: version.version,
      mode,
      regionId: version.regionId ?? null,
      tariffId: version.deliveryTariffId ?? null,
    });
  }

  protected closeReview(): void {
    if (this.busyZoneId() === null) {
      this.reviewing.set(null);
    }
  }

  /** The reviewer confirmed: activate the version that was looked at, and not another. */
  protected async confirmActivation(): Promise<void> {
    const review = this.reviewing();
    if (review === null || review.mode !== 'activate') {
      return;
    }
    const done = await this.runOnRow(review.zone.zoneId, (scope) =>
      this.api.activate(scope, review.zone.zoneId, review.version),
    );
    if (done) {
      this.reviewing.set(null);
    }
    // A refusal (the region's box, the area ceiling, a self-intersection) stays on screen beside
    // the review, with the version still open to be looked at again.
  }

  protected async deactivate(zone: ZoneSummaryResponse, version: number): Promise<void> {
    await this.runOnRow(zone.zoneId, (scope) => this.api.deactivate(scope, zone.zoneId, version));
  }

  protected openBindForm(zone: ZoneSummaryResponse): void {
    this.bindLocationId.set(this.branchOptions()[0]?.id ?? '');
    this.rowError.set(null);
    this.bindingZoneId.set(zone.zoneId);
  }

  protected closeBindForm(): void {
    this.bindingZoneId.set(null);
  }

  protected async submitBind(): Promise<void> {
    const zoneId = this.bindingZoneId();
    const locationId = this.bindLocationId();
    if (!zoneId || !locationId) {
      return;
    }
    this.bindingZoneId.set(null);
    await this.runOnRow(zoneId, (scope) => this.api.bindLocation(scope, zoneId, locationId));
  }

  protected async unbind(zone: ZoneSummaryResponse, locationId: string): Promise<void> {
    await this.runOnRow(zone.zoneId, (scope) =>
      this.api.unbindLocation(scope, zone.zoneId, locationId),
    );
  }

  /** @returns whether the action went through; a refusal is shown in {@link rowError} and answers `false` */
  private async runOnRow(
    zoneId: string,
    action: (scope: { tenantId: string; brandId: string }) => Promise<unknown>,
  ): Promise<boolean> {
    const scope = this.brand.scope();
    if (!scope || this.busyZoneId()) {
      return false;
    }
    this.busyZoneId.set(zoneId);
    this.rowError.set(null);
    try {
      await action(scope);
      await this.refreshRow(zoneId);
      this.zones.set(await this.api.list(scope));
      await this.loadTariffOptions();
      return true;
    } catch (error) {
      this.rowError.set(this.describe(error));
      return false;
    } finally {
      this.busyZoneId.set(null);
    }
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}

/**
 * Whether a tariff version charges nothing for any address it covers.
 *
 * Deliberately strict. "Free" has to survive every path through the
 * calculator, so a surcharging time rule, a per-kilometre component, a
 * non-zero band or a non-zero minimum all disqualify it — a zone that is free
 * at noon and priced at seven is not a free geozone, and labelling it one
 * would be worse than labelling nothing.
 *
 * A tariff with no live version is not free either: V0025 is explicit that an
 * unset rate table gives `NO_TARIFF` and "free delivery and a missing rate
 * table must never look alike".
 */
function pricesToZero(
  version:
    | {
        minFeeMinor: number;
        bands: readonly { baseMinor: number; perKmMinor: number }[];
        timeRules: readonly { multiplierBasisPoints: number; surchargeMinor: number }[];
      }
    | null
    | undefined,
): boolean {
  if (!version || version.bands.length === 0) {
    return false;
  }
  return (
    version.minFeeMinor === 0 &&
    version.bands.every((band) => band.baseMinor === 0 && band.perKmMinor === 0) &&
    version.timeRules.every((rule) => rule.surchargeMinor === 0)
  );
}

/** The `problems` array ADR 0037's refusals carry, so the operator sees every reason at once. */
function problemsOf(error: unknown): readonly string[] {
  if (!(error instanceof ApiError)) {
    return [];
  }
  const problems = error.problem?.['problems'];
  return Array.isArray(problems) ? problems.map((entry) => String(entry)) : [];
}

/** A zone's platform-triple name columns keyed by locale — for a response that predates `displayNames`. */
function triple(zone: ZoneSummaryResponse): LabelsByLocale {
  return { ru: zone.displayNameRu, 'uz-Latn': zone.displayNameUz, en: zone.displayNameEn };
}
