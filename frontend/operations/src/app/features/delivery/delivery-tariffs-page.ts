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
  ActiveVersionResponse,
  BandRequest,
  DeliveryTariffsApi,
  DiscountRequest,
  TariffDetailResponse,
  TariffSummaryResponse,
  TimeRuleRequest,
} from './delivery-tariffs-api';

/** One row of the band editor, held as strings-free numbers the form can bind to. */
interface BandDraft {
  bandSet: string;
  fromMeters: number;
  toMeters: number;
  baseMinor: number;
  perKmMinor: number;
}

interface TimeRuleDraft {
  priority: number;
  dayMask: number;
  fromTime: string;
  toTime: string;
  bandSet: string;
  multiplierBasisPoints: number;
  surchargeMinor: number;
}

interface DiscountDraft {
  priority: number;
  kind: 'AMOUNT' | 'DISTANCE_ALLOWANCE';
  amountMinor: number;
  allowanceMeters: number;
  dayMask: number;
  fromTime: string;
  toTime: string;
}

/** Bit 0 is Monday, so weekdays is 31 and the whole week 127. */
const WHOLE_WEEK = 127;

/**
 * Enum-to-catalogue lookups, spelled out rather than concatenated.
 *
 * `MessageKey` is a union of every key in `messages.en.ts`, which is what makes
 * a missing translation a compile error rather than a blank cell. A template
 * expression like `'…' + tariff.feeSource` is a plain `string` and defeats
 * exactly that guarantee, so each enum gets a table and an explicit fallback.
 */
const DAY_KEYS: readonly MessageKey[] = [
  'delivery.tariffs.days.mon',
  'delivery.tariffs.days.tue',
  'delivery.tariffs.days.wed',
  'delivery.tariffs.days.thu',
  'delivery.tariffs.days.fri',
  'delivery.tariffs.days.sat',
  'delivery.tariffs.days.sun',
];

const FEE_SOURCE_KEYS: Readonly<Record<string, MessageKey>> = {
  TARIFF: 'delivery.tariffs.feeSource.TARIFF',
  PROVIDER_QUOTE: 'delivery.tariffs.feeSource.PROVIDER_QUOTE',
};

const DISTANCE_MODE_KEYS: Readonly<Record<string, MessageKey>> = {
  RADIUS: 'delivery.tariffs.distanceMode.RADIUS',
  ROAD: 'delivery.tariffs.distanceMode.ROAD',
};

const ACCRUAL_KEYS: Readonly<Record<string, MessageKey>> = {
  STARTED_KILOMETRE: 'delivery.tariffs.accrual.STARTED_KILOMETRE',
  PRORATED_METRE: 'delivery.tariffs.accrual.PRORATED_METRE',
};

const ROUNDING_KEYS: Readonly<Record<string, MessageKey>> = {
  HALF_UP: 'delivery.tariffs.rounding.HALF_UP',
  HALF_EVEN: 'delivery.tariffs.rounding.HALF_EVEN',
};

const DISCOUNT_KIND_KEYS: Readonly<Record<string, MessageKey>> = {
  AMOUNT: 'delivery.tariffs.discountKind.AMOUNT',
  DISTANCE_ALLOWANCE: 'delivery.tariffs.discountKind.DISTANCE_ALLOWANCE',
};

/**
 * Delivery tariffs — operations §3.7.
 *
 * **What changed in this wave (ADR 0101).**
 *
 * 1. **The form authors the whole rate table.** It used to draft exactly one
 *    flat band across the whole reach, with no time rules, no discounts, no
 *    rounding and no min/max — while `DraftTariffVersionRequest` had accepted
 *    all of it since V0032. An operator pricing a real city had to ask a
 *    developer for everything past the first band.
 * 2. **The detail panel renders what it used to drop.** Distance mode, reach,
 *    rounding, the road factor, and every time rule and discount as a row
 *    rather than as a count.
 * 3. **A tariff can be bound to a branch.** `bindLocation` existed on both
 *    controllers and had no caller anywhere in this console, so every location
 *    rode the brand default and ADR 0037's middle precedence rung was
 *    unreachable from the product.
 * 4. **`RADIUS_FALLBACK` is rendered, not hidden.** A `ROAD` tariff with no
 *    routing installation is refused at activation; one whose provider does
 *    not answer prices from the straight line inflated by the detour factor
 *    and stamps `RADIUS_FALLBACK` on the resolution. Three Javadoc comments in
 *    the platform say this exists "so nobody is misled", and nothing rendered
 *    it. Both facts are on the screen now.
 *
 * Drafting and activating are two clicks, for the reason
 * `DELIVERY_TARIFF_ACTIVATE` is a separate capability from `MANAGE`: an
 * operator entering a rate table has a broken one half way through by
 * definition, and only the activation gate checks the bands tile
 * `[0, maxDistance)` with no gap.
 */
@Component({
  selector: 'q-delivery-tariffs-page',
  imports: [TPipe],
  templateUrl: './delivery-tariffs-page.html',
  styleUrl: './delivery-tariffs-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DeliveryTariffsPage implements OnInit {
  private readonly api = inject(DeliveryTariffsApi);
  private readonly brand = inject(CurrentBrand);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly tariffs = signal<readonly TariffSummaryResponse[]>([]);

  protected readonly expandedTariffId = signal<string | null>(null);
  protected readonly detailByTariffId = signal<ReadonlyMap<string, TariffDetailResponse>>(
    new Map(),
  );
  protected readonly rowError = signal<string | null>(null);
  protected readonly busyTariffId = signal<string | null>(null);

  protected readonly showCreateForm = signal(false);
  protected readonly createSubmitting = signal(false);
  protected readonly createError = signal<string | null>(null);
  protected readonly newCode = signal('');
  protected readonly newName = signal('');
  protected readonly newBrandDefault = signal(false);

  protected readonly draftingTariff = signal<TariffSummaryResponse | null>(null);
  protected readonly draftSubmitting = signal(false);
  protected readonly draftError = signal<string | null>(null);
  protected readonly draftProblems = signal<readonly string[]>([]);
  protected readonly draftCurrency = signal('UZS');
  protected readonly draftFeeSource = signal<'TARIFF' | 'PROVIDER_QUOTE'>('TARIFF');
  protected readonly draftDistanceMode = signal<'RADIUS' | 'ROAD'>('RADIUS');
  protected readonly draftRoutingInstallationId = signal('');
  protected readonly draftRoadFactorBasisPoints = signal(13_000);
  protected readonly draftMaxDistanceMeters = signal(15_000);
  protected readonly draftMinFeeMinor = signal(0);
  protected readonly draftMaxFeeMinor = signal<number | null>(null);
  protected readonly draftRoundingStepMinor = signal<number | null>(null);
  /**
   * How a band's per-kilometre component accrues.
   *
   * Authored, not left to the server's default, because the default is
   * `STARTED_KILOMETRE` and a branch migrated from the legacy dashboard prices
   * `PRORATED_METRE`. Re-drafting such a tariff without this field would
   * silently change what every customer of that branch is charged, with
   * nothing on the screen having said so.
   */
  protected readonly draftAccrual = signal<'STARTED_KILOMETRE' | 'PRORATED_METRE'>(
    'STARTED_KILOMETRE',
  );
  protected readonly draftRoundingRule = signal<'' | 'HALF_UP' | 'HALF_EVEN'>('');
  protected readonly draftBands = signal<readonly BandDraft[]>([]);
  protected readonly draftTimeRules = signal<readonly TimeRuleDraft[]>([]);
  protected readonly draftDiscounts = signal<readonly DiscountDraft[]>([]);

  /**
   * The version this session just drafted, per tariff.
   *
   * Held rather than inferred, because "the next version number" is not
   * `activeVersion + 1`: drafts accumulate, and a tariff with a live v3 and two
   * abandoned drafts is at v6. The draft response names the number; that is
   * the one the activate button must send. It is deliberately session-local —
   * a reload clears it, and the honest consequence is that a draft left
   * overnight is activated by drafting again rather than by this button.
   */
  protected readonly draftedVersionByTariffId = signal<ReadonlyMap<string, number>>(new Map());

  protected readonly bindingTariffId = signal<string | null>(null);
  protected readonly bindLocationId = signal('');

  protected readonly branchOptions = computed(() => this.location.options());

  /**
   * A ROAD tariff with no routing installation will be refused at activation
   * (`DeliveryTariff.activationProblems`). Said before the submit rather than
   * after, because the alternative is an operator who believes ROAD works and
   * discovers otherwise on the activation click.
   */
  protected readonly roadNeedsRouting = computed(
    () => this.draftDistanceMode() === 'ROAD' && this.draftRoutingInstallationId().trim() === '',
  );

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
      this.tariffs.set(await this.api.list(scope));
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

  // ---------------------------------------------------------------- reading

  protected isExpanded(tariff: TariffSummaryResponse): boolean {
    return this.expandedTariffId() === tariff.tariffId;
  }

  protected async toggleExpand(tariff: TariffSummaryResponse): Promise<void> {
    if (this.isExpanded(tariff)) {
      this.expandedTariffId.set(null);
      return;
    }
    this.expandedTariffId.set(tariff.tariffId);
    const scope = this.brand.scope();
    if (!scope || this.detailByTariffId().has(tariff.tariffId)) {
      return;
    }
    try {
      const detail = await this.api.detail(scope, tariff.tariffId);
      this.detailByTariffId.update((current) => new Map(current).set(tariff.tariffId, detail));
    } catch (error) {
      this.rowError.set(this.describe(error));
    }
  }

  protected detailFor(tariff: TariffSummaryResponse): TariffDetailResponse | null {
    return this.detailByTariffId().get(tariff.tariffId) ?? null;
  }

  /**
   * Whether this live version will price from an inflated straight line rather
   * than from a road distance.
   *
   * `RoadDistancePort` answers empty today — ADR 0037 records it — so every
   * `ROAD` tariff currently resolves `RADIUS_FALLBACK`. Rendering it is the
   * difference between an operator who knows the fee is approximate and one
   * who thinks the routing provider is working.
   */
  protected fallsBackToRadius(active: ActiveVersionResponse): boolean {
    return active.distanceMode === 'ROAD';
  }

  protected describeDayMask(mask: number): string {
    const selected = DAY_KEYS.filter((_, index) => (mask & (1 << index)) !== 0);
    if (selected.length === DAY_KEYS.length) {
      return this.i18n.t('delivery.tariffs.days.all');
    }
    return selected.map((key) => this.i18n.t(key)).join(', ');
  }

  protected feeSourceKey(feeSource: string | null | undefined): MessageKey {
    return FEE_SOURCE_KEYS[feeSource ?? ''] ?? 'delivery.tariffs.feeSource.TARIFF';
  }

  protected distanceModeKey(distanceMode: string | null | undefined): MessageKey {
    return DISTANCE_MODE_KEYS[distanceMode ?? ''] ?? 'delivery.tariffs.distanceMode.RADIUS';
  }

  protected accrualKey(accrual: string | null | undefined): MessageKey {
    return ACCRUAL_KEYS[accrual ?? ''] ?? 'delivery.tariffs.accrual.STARTED_KILOMETRE';
  }

  protected roundingKey(rule: string | null | undefined): MessageKey {
    return ROUNDING_KEYS[rule ?? ''] ?? 'delivery.tariffs.rounding.HALF_UP';
  }

  protected discountKindKey(kind: string | null | undefined): MessageKey {
    return DISCOUNT_KIND_KEYS[kind ?? ''] ?? 'delivery.tariffs.discountKind.AMOUNT';
  }

  // --------------------------------------------------------------- create

  protected openCreateForm(): void {
    this.newCode.set('');
    this.newName.set('');
    this.newBrandDefault.set(false);
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
      this.newName().trim().length > 0
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
        code: this.newCode().trim(),
        name: this.newName().trim(),
        brandDefault: this.newBrandDefault(),
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

  protected openDraftForm(tariff: TariffSummaryResponse): void {
    const active = this.detailByTariffId().get(tariff.tariffId)?.activeVersion ?? null;
    this.draftCurrency.set(active?.currency ?? tariff.currency ?? 'UZS');
    this.draftFeeSource.set((active?.feeSource as 'TARIFF' | 'PROVIDER_QUOTE') ?? 'TARIFF');
    this.draftDistanceMode.set((active?.distanceMode as 'RADIUS' | 'ROAD') ?? 'RADIUS');
    this.draftRoutingInstallationId.set(active?.routingProviderInstallationId ?? '');
    this.draftRoadFactorBasisPoints.set(active?.roadFactorBasisPoints ?? 13_000);
    this.draftMaxDistanceMeters.set(active?.maxDistanceMeters ?? 15_000);
    this.draftMinFeeMinor.set(active?.minFeeMinor ?? 0);
    this.draftMaxFeeMinor.set(active?.maxFeeMinor ?? null);
    this.draftRoundingStepMinor.set(active?.feeRoundingStepMinor ?? null);
    this.draftAccrual.set(
      active?.distanceAccrual === 'PRORATED_METRE' ? 'PRORATED_METRE' : 'STARTED_KILOMETRE',
    );
    this.draftRoundingRule.set((active?.feeRoundingRule as 'HALF_UP' | 'HALF_EVEN') ?? '');
    // Seeded from the live version so editing a rate table is an edit rather
    // than a re-typing. A version is immutable; this produces a new one.
    this.draftBands.set(
      active && active.bands.length > 0
        ? active.bands.map((band) => ({
            bandSet: band.bandSet === 'BASE' ? '' : (band.bandSet ?? ''),
            fromMeters: band.fromMeters,
            toMeters: band.toMeters,
            baseMinor: band.baseMinor,
            perKmMinor: band.perKmMinor,
          }))
        : [
            {
              bandSet: '',
              fromMeters: 0,
              toMeters: active?.maxDistanceMeters ?? 15_000,
              baseMinor: 10_000,
              perKmMinor: 0,
            },
          ],
    );
    this.draftTimeRules.set(
      (active?.timeRules ?? []).map((rule) => ({
        priority: rule.priority,
        dayMask: rule.dayMask,
        fromTime: rule.fromTime.slice(0, 5),
        toTime: rule.toTime.slice(0, 5),
        bandSet: rule.bandSet ?? '',
        multiplierBasisPoints: rule.multiplierBasisPoints,
        surchargeMinor: rule.surchargeMinor,
      })),
    );
    this.draftDiscounts.set(
      (active?.discounts ?? []).map((discount) => ({
        priority: discount.priority,
        kind: discount.kind === 'DISTANCE_ALLOWANCE' ? 'DISTANCE_ALLOWANCE' : 'AMOUNT',
        amountMinor: discount.amountMinor ?? 0,
        allowanceMeters: discount.allowanceMeters ?? 0,
        dayMask: discount.dayMask,
        fromTime: discount.fromTime.slice(0, 5),
        toTime: discount.toTime.slice(0, 5),
      })),
    );
    this.draftError.set(null);
    this.draftProblems.set([]);
    this.draftingTariff.set(tariff);
  }

  protected closeDraftForm(): void {
    this.draftingTariff.set(null);
  }

  protected addBand(): void {
    this.draftBands.update((bands) => {
      const last = bands[bands.length - 1];
      const from = last ? last.toMeters : 0;
      return [
        ...bands,
        {
          bandSet: last?.bandSet ?? '',
          fromMeters: from,
          toMeters: Math.max(from + 1_000, this.draftMaxDistanceMeters()),
          // Bands accumulate (V0032), so a tier added after the first usually
          // charges per kilometre and nothing to enter.
          baseMinor: 0,
          perKmMinor: 2_000,
        },
      ];
    });
  }

  protected removeBand(index: number): void {
    this.draftBands.update((bands) => bands.filter((_, i) => i !== index));
  }

  protected updateBand(index: number, field: keyof BandDraft, value: string): void {
    this.draftBands.update((bands) =>
      bands.map((band, i) =>
        i === index ? { ...band, [field]: field === 'bandSet' ? value : Number(value) } : band,
      ),
    );
  }

  protected addTimeRule(): void {
    this.draftTimeRules.update((rules) => [
      ...rules,
      {
        priority: rules.length * 10,
        dayMask: WHOLE_WEEK,
        fromTime: '18:00',
        toTime: '22:00',
        bandSet: '',
        multiplierBasisPoints: 10_000,
        surchargeMinor: 0,
      },
    ]);
  }

  protected removeTimeRule(index: number): void {
    this.draftTimeRules.update((rules) => rules.filter((_, i) => i !== index));
  }

  protected updateTimeRule(index: number, field: keyof TimeRuleDraft, value: string): void {
    this.draftTimeRules.update((rules) =>
      rules.map((rule, i) =>
        i === index
          ? {
              ...rule,
              [field]:
                field === 'fromTime' || field === 'toTime' || field === 'bandSet'
                  ? value
                  : Number(value),
            }
          : rule,
      ),
    );
  }

  protected addDiscount(): void {
    this.draftDiscounts.update((discounts) => [
      ...discounts,
      {
        priority: discounts.length * 10,
        kind: 'AMOUNT',
        amountMinor: 5_000,
        allowanceMeters: 0,
        dayMask: WHOLE_WEEK,
        fromTime: '10:00',
        toTime: '14:00',
      },
    ]);
  }

  protected removeDiscount(index: number): void {
    this.draftDiscounts.update((discounts) => discounts.filter((_, i) => i !== index));
  }

  protected updateDiscount(index: number, field: keyof DiscountDraft, value: string): void {
    this.draftDiscounts.update((discounts) =>
      discounts.map((discount, i) =>
        i === index
          ? {
              ...discount,
              [field]:
                field === 'kind' || field === 'fromTime' || field === 'toTime'
                  ? value
                  : Number(value),
            }
          : discount,
      ),
    );
  }

  protected canDraft(): boolean {
    return (
      !this.draftSubmitting() && this.draftMaxDistanceMeters() > 0 && this.draftBands().length > 0
    );
  }

  protected async submitDraft(): Promise<void> {
    const scope = this.brand.scope();
    const tariff = this.draftingTariff();
    if (!scope || !tariff || !this.canDraft()) {
      return;
    }
    const bands: BandRequest[] = this.draftBands().map((band) => ({
      bandSet: band.bandSet.trim() || null,
      fromMeters: band.fromMeters,
      toMeters: band.toMeters,
      baseMinor: band.baseMinor,
      perKmMinor: band.perKmMinor,
    }));
    const timeRules: TimeRuleRequest[] = this.draftTimeRules().map((rule) => ({
      priority: rule.priority,
      dayMask: rule.dayMask,
      fromTime: withSeconds(rule.fromTime),
      toTime: withSeconds(rule.toTime),
      bandSet: rule.bandSet.trim() || null,
      multiplierBasisPoints: rule.multiplierBasisPoints,
      surchargeMinor: rule.surchargeMinor,
    }));
    const discounts: DiscountRequest[] = this.draftDiscounts().map((discount) => ({
      priority: discount.priority,
      kind: discount.kind,
      // Exactly one of the two is set, per `TariffDiscount`'s own contract.
      amountMinor: discount.kind === 'AMOUNT' ? discount.amountMinor : null,
      allowanceMeters: discount.kind === 'DISTANCE_ALLOWANCE' ? discount.allowanceMeters : null,
      dayMask: discount.dayMask,
      fromTime: withSeconds(discount.fromTime),
      toTime: withSeconds(discount.toTime),
    }));

    this.draftSubmitting.set(true);
    this.draftError.set(null);
    this.draftProblems.set([]);
    try {
      const drafted = await this.api.draftVersion(scope, tariff.tariffId, {
        currency: this.draftCurrency(),
        feeSource: this.draftFeeSource(),
        distanceMode: this.draftDistanceMode(),
        roadFactorBasisPoints: this.draftRoadFactorBasisPoints(),
        routingProviderInstallationId: this.draftRoutingInstallationId().trim() || null,
        maxDistanceMeters: this.draftMaxDistanceMeters(),
        minFeeMinor: this.draftMinFeeMinor(),
        maxFeeMinor: this.draftMaxFeeMinor(),
        distanceAccrual: this.draftAccrual(),
        feeRoundingStepMinor: this.draftRoundingStepMinor(),
        feeRoundingRule: this.draftRoundingRule() || null,
        bands,
        timeRules,
        discounts,
      });
      this.draftingTariff.set(null);
      this.draftedVersionByTariffId.update((current) =>
        new Map(current).set(tariff.tariffId, drafted.version),
      );
      this.detailByTariffId.update((current) => {
        const next = new Map(current);
        next.delete(tariff.tariffId);
        return next;
      });
      this.expandedTariffId.set(tariff.tariffId);
      await this.load();
      await this.toggleExpandInto(tariff.tariffId);
    } catch (error) {
      this.draftError.set(this.describe(error));
      this.draftProblems.set(problemsOf(error));
    } finally {
      this.draftSubmitting.set(false);
    }
  }

  private async toggleExpandInto(tariffId: string): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    try {
      const detail = await this.api.detail(scope, tariffId);
      this.detailByTariffId.update((current) => new Map(current).set(tariffId, detail));
    } catch (error) {
      this.rowError.set(this.describe(error));
    }
  }

  // ------------------------------------------------------------ lifecycle

  /** The version this session drafted, or null when there is nothing to activate. */
  protected pendingVersion(tariff: TariffSummaryResponse): number | null {
    const drafted = this.draftedVersionByTariffId().get(tariff.tariffId);
    return drafted !== undefined && drafted !== tariff.activeVersion ? drafted : null;
  }

  protected async activate(tariff: TariffSummaryResponse, version: number): Promise<void> {
    await this.runOnRow(tariff.tariffId, (scope) =>
      this.api.activate(scope, tariff.tariffId, version),
    );
  }

  protected openBindForm(tariff: TariffSummaryResponse): void {
    this.bindLocationId.set(this.branchOptions()[0]?.id ?? '');
    this.rowError.set(null);
    this.bindingTariffId.set(tariff.tariffId);
  }

  protected closeBindForm(): void {
    this.bindingTariffId.set(null);
  }

  protected async submitBind(): Promise<void> {
    const tariffId = this.bindingTariffId();
    const locationId = this.bindLocationId();
    if (!tariffId || !locationId) {
      return;
    }
    this.bindingTariffId.set(null);
    await this.runOnRow(tariffId, (scope) => this.api.bindLocation(scope, tariffId, locationId));
  }

  private async runOnRow(
    tariffId: string,
    action: (scope: { tenantId: string; brandId: string }) => Promise<unknown>,
  ): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || this.busyTariffId()) {
      return;
    }
    this.busyTariffId.set(tariffId);
    this.rowError.set(null);
    this.draftProblems.set([]);
    try {
      await action(scope);
      this.detailByTariffId.update((current) => {
        const next = new Map(current);
        next.delete(tariffId);
        return next;
      });
      this.tariffs.set(await this.api.list(scope));
      if (this.expandedTariffId() === tariffId) {
        const detail = await this.api.detail(scope, tariffId);
        this.detailByTariffId.update((current) => new Map(current).set(tariffId, detail));
      }
    } catch (error) {
      this.rowError.set(this.describe(error));
      this.draftProblems.set(problemsOf(error));
    } finally {
      this.busyTariffId.set(null);
    }
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}

/** `LocalTime` on the wire is `HH:mm:ss`; the form's `<input type="time">` is `HH:mm`. */
function withSeconds(value: string): string {
  return value.length === 5 ? `${value}:00` : value;
}

/**
 * The `problems` array a tariff refusal carries — a band gap names the exact
 * metres nobody could order from, and collapsing that into one line would
 * throw away the only part an operator can act on.
 */
function problemsOf(error: unknown): readonly string[] {
  if (!(error instanceof ApiError)) {
    return [];
  }
  const problems = error.problem?.['problems'];
  return Array.isArray(problems) ? problems.map((entry) => String(entry)) : [];
}
