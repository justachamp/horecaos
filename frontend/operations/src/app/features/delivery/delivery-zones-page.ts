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
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import {
  DeliveryZonesApi,
  ZoneDetailResponse,
  ZoneSummaryResponse,
  ZoneVersionResponse,
} from './delivery-zones-api';
import { DeliveryTariffsApi, TariffSummaryResponse } from './delivery-tariffs-api';
import { localisedName } from './localised-name';
import { RegionResponse, RegionsApi } from './regions-api';

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
 * 4. **Three locale names, not one string written three times.**
 * 5. **Draft, activate and bind are three acts.** They used to run inside one
 *    `submitDraft`, so a mis-typed radius went live and stayed live. There is
 *    now a version list, a deactivate and an unbind.
 *
 * **Still reduced, and honestly.** No `MapCanvas`/`PolygonEditor` exists (IA
 * Part 4's pilot blockers; ADR 0015 owes the provider decision ADR 0037
 * inherited), so this authors circles around a branch. Bulk geozone upload
 * (`3.6c`) has no backend at all.
 */
@Component({
  selector: 'q-delivery-zones-page',
  imports: [TPipe],
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
  protected readonly i18n = inject(I18n);

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
  protected readonly newNameRu = signal('');
  protected readonly newNameUz = signal('');
  protected readonly newNameEn = signal('');

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
    await this.brand.ensureLoaded();
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
    const id = zone.deliveryTariffId;
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

  /** The zone's name in the operator's own locale — the point of authoring three. */
  protected zoneName(zone: ZoneSummaryResponse): string {
    return localisedName(this.i18n.locale(), zone);
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
    this.newNameRu.set('');
    this.newNameUz.set('');
    this.newNameEn.set('');
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
      this.newNameRu().trim().length > 0 &&
      this.newNameUz().trim().length > 0 &&
      this.newNameEn().trim().length > 0
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
      await this.api.create(scope, {
        role: this.newRole(),
        code: this.newCode().trim(),
        displayNameRu: this.newNameRu().trim(),
        displayNameUz: this.newNameUz().trim(),
        displayNameEn: this.newNameEn().trim(),
      });
      this.showCreateForm.set(false);
      await this.load();
    } catch (error) {
      this.createError.set(this.describe(error));
    } finally {
      this.createSubmitting.set(false);
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
    this.draftingZone.set(zone);
  }

  protected closeDraftForm(): void {
    this.draftingZone.set(null);
  }

  protected canDraft(): boolean {
    return (
      !this.draftSubmitting() &&
      this.draftRadiusMeters() > 0 &&
      this.draftOriginLocationId().length > 0
    );
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
      await this.api.draftCircleVersion(scope, zone.zoneId, {
        originLocationId: this.draftOriginLocationId(),
        radiusMeters: this.draftRadiusMeters(),
        regionId: this.draftRegionId() || null,
        priority: this.draftPriority(),
        currency: this.draftCurrency(),
        deliveryTariffId: priced ? this.draftTariffId() || null : null,
        freeDeliveryFromMinor: priced ? this.draftFreeFromMinor() : null,
        minBasketMinor: priced ? this.draftMinBasketMinor() : null,
      });
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

  protected async activate(zone: ZoneSummaryResponse, version: number): Promise<void> {
    await this.runOnRow(zone.zoneId, (scope) => this.api.activate(scope, zone.zoneId, version));
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

  private async runOnRow(
    zoneId: string,
    action: (scope: { tenantId: string; brandId: string }) => Promise<unknown>,
  ): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || this.busyZoneId()) {
      return;
    }
    this.busyZoneId.set(zoneId);
    this.rowError.set(null);
    try {
      await action(scope);
      await this.refreshRow(zoneId);
      this.zones.set(await this.api.list(scope));
      await this.loadTariffOptions();
    } catch (error) {
      this.rowError.set(this.describe(error));
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
