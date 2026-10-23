import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { ExternalRuleResult, RuleSimulator, SimulatedRule } from '../../shared/ui/rule-simulator';
import {
  AdjustmentReasonResponse,
  CouriersApi,
  CourierTypeResponse,
  CreateAdjustmentReasonRequest,
  RateCardDetailResponse,
  RateCardSummaryResponse,
  RateComponentRequest,
  RateComponentView,
  RuleSimulation,
} from '../couriers/couriers-api';
import { describeApiError } from '../orders/order-errors';

/** One row of the rate-card component editor, before it becomes a request. */
interface ComponentDraft {
  componentType: string;
  priority: number;
  amountMinor: number;
  bandFromMeters: number | null;
  bandToMeters: number | null;
  minimumPaidSeconds: number | null;
}

const OUTCOME_BASES = [
  'DELIVERED_VOLUME',
  'ON_TIME_RATE',
  'LATE_DELIVERY',
  'GEO_UNVERIFIED_RATE',
  'CASH_VARIANCE',
  'ORDER_UNDELIVERED',
  'ORDER_DAMAGED',
] as const;

/**
 * Every basis {@link AdjustmentRuleEvaluator} on the platform actually reads.
 *
 * `CASH_VARIANCE` joined in gap map row 3.4c: the evaluator has counted a period's
 * `CASH_VARIANCE` ledger entries at settlement close since batch 10, and a test now proves a
 * wired rule on it fires, so the form no longer leaves it to a direct API call.
 */
const EVALUATED_BASES = new Set([
  'DELIVERED_VOLUME',
  'ON_TIME_RATE',
  'LATE_DELIVERY',
  'GEO_UNVERIFIED_RATE',
  'CASH_VARIANCE',
]);

/**
 * Bases the evaluator reads over a settlement period only (couriers.md §11): a shift's own
 * handover is one row, and "one variance in one shift" is a weaker signal than the period total,
 * so a rule on one of these in a SHIFT window would never fire. The form pins the window and the
 * trigger rather than letting an operator author a rule that is silently dead.
 */
const PERIOD_ONLY_BASES = new Set(['CASH_VARIANCE']);

/**
 * IA 3.4 — Courier types & rates, and the bonus/penalty registry the same IA
 * row owns ("Owns: … bonus/penalty rule definitions").
 *
 * **This wave (T16, ADR 0108) closes three gaps the operations gap map named
 * on this page.**
 *
 * 1. **Types were create-only.** `code`/`displayName`/every dispatch number
 *    can now be corrected under an optimistic lock (`PUT`), and a type
 *    archives instead of staying wrong forever (`POST .../archival`). The
 *    create form now sends `maxDistanceMeters` — accepted and enforced by
 *    `CourierDispatchGate` since it existed, just never sent from here — and
 *    the two attributes with no column anywhere before this wave, starting
 *    minute and work mode (captured and rendered; not yet enforced, see the
 *    field hints on the form).
 * 2. **The rate-card form emitted two of the backend's four component
 *    types.** `PER_KM_BAND` now has a real ladder editor (add/remove a band
 *    row, each with its own from/to/amount) and `PER_ORDER_MINIMUM` is a
 *    fourth row type, alongside the two flat components the previous wave
 *    shipped. `GET rate-cards/{cardId}` — authored with no caller before
 *    this wave — now has one: "Show components" opens a card's ladder and
 *    its effective dates before anyone activates it. `cardVersion` and a
 *    narrower scope (a location, or a courier type) are now real form
 *    fields rather than hard-coded — a code whose v1 is ACTIVE can be
 *    re-priced.
 * 3. **There was no rule catalogue at all.** The registry section below
 *    defines a bonus/penalty reason, manual-only or wired to a rule
 *    (`AdjustmentRuleEvaluator`, ADR 0108) that fires automatically at shift
 *    close. `ORDER_UNDELIVERED`/`ORDER_DAMAGED` have no evaluator reader —
 *    the wiring fields are disabled for those two bases — and stay
 *    manual-only. Recording a one-off bonus or penalty against a named
 *    courier is on {@link CouriersPage}'s own detail pane, not here: this
 *    page defines policy, that one acts on one person.
 *
 * **The rule simulator (couriers.md §11, ADR 0178).** «Who would this rule have
 * hit last week, and for how much» before a rule is saved: the reason form runs
 * the candidate through `POST .../adjustment-rule-simulations`, which replays
 * the evaluator's own arithmetic over recent closed shifts and settlement
 * periods and writes nothing. The verdict is drawn by the shared
 * `q-rule-simulator` in its engine mode (the platform answered, the component
 * does not guess), and the couriers it would have hit are listed beside it by
 * reference, never by name. The replay is tenant-wide, never narrowed to the
 * operator's branch: a reason is a tenant-level registry row that fires at every
 * branch's shift close, and a preview limited to one branch would read «nobody»
 * for a rule that hits a dozen couriers elsewhere.
 *
 * **Deliberately not built.** A visual band-gap/overlap ruler
 * (`docs/operations-spec/couriers.md` §10 describes it) —
 * activation's own server-side validation still catches every gap and
 * overlap, and its message renders in `cardError`/`activateError` exactly
 * as before. The reason form below is the minimum typed editor ADR 0108's
 * five rule columns need.
 */
@Component({
  selector: 'q-courier-types-rates-page',
  imports: [TPipe, RuleSimulator],
  templateUrl: './courier-types-rates-page.html',
  styleUrl: './courier-types-rates-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CourierTypesRatesPage implements OnInit {
  private readonly api = inject(CouriersApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);

  protected readonly types = signal<readonly CourierTypeResponse[]>([]);
  protected readonly rateCards = signal<readonly RateCardSummaryResponse[]>([]);
  protected readonly adjustmentReasons = signal<readonly AdjustmentReasonResponse[]>([]);
  protected readonly includeArchivedTypes = signal(false);

  // -------------------------------------------------------- type authoring
  protected readonly showTypeForm = signal(false);
  protected readonly typeSubmitting = signal(false);
  protected readonly typeError = signal<string | null>(null);
  protected readonly newTypeCode = signal('');
  protected readonly newTypeDisplayName = signal('');
  protected readonly newTypeVehicleClass = signal('SCOOTER');
  protected readonly newTypeMinDistance = signal(0);
  protected readonly newTypeMaxDistance = signal<number | null>(null);
  protected readonly newTypeMaxConcurrent = signal(2);
  protected readonly newTypeOfferTtl = signal(60);
  protected readonly newTypeStartingMinute = signal(0);
  protected readonly newTypeWorkMode = signal('SHIFT');

  // ----------------------------------------------------------- type editing
  protected readonly editingType = signal<CourierTypeResponse | null>(null);
  protected readonly editTypeSubmitting = signal(false);
  protected readonly editTypeError = signal<string | null>(null);
  protected readonly editTypeCode = signal('');
  protected readonly editTypeDisplayName = signal('');
  protected readonly editTypeVehicleClass = signal('SCOOTER');
  protected readonly editTypeMinDistance = signal(0);
  protected readonly editTypeMaxDistance = signal<number | null>(null);
  protected readonly editTypeMaxConcurrent = signal(2);
  protected readonly editTypeOfferTtl = signal(60);
  protected readonly editTypeStartingMinute = signal(0);
  protected readonly editTypeWorkMode = signal('SHIFT');
  protected readonly editTypeReason = signal('');

  protected readonly archivingTypeId = signal<string | null>(null);
  protected readonly archiveTypeReason = signal('');
  protected readonly archiveTypeSubmitting = signal(false);
  protected readonly archiveTypeError = signal<string | null>(null);

  // -------------------------------------------------- rate card authoring
  protected readonly showCardForm = signal(false);
  protected readonly cardSubmitting = signal(false);
  protected readonly cardError = signal<string | null>(null);
  protected readonly newCardCode = signal('');
  protected readonly newCardVersion = signal(1);
  protected readonly newCardCurrency = signal('UZS');
  protected readonly newCardLocationId = signal('');
  protected readonly newCardCourierTypeId = signal('');
  protected readonly newCardComponents = signal<ComponentDraft[]>([]);

  protected readonly activatingCardId = signal<string | null>(null);
  protected readonly activateError = signal<string | null>(null);

  // ---------------------------------------------------------- card detail
  protected readonly viewingCard = signal<RateCardDetailResponse | null>(null);
  protected readonly viewingCardCode = signal('');
  protected readonly viewCardLoading = signal(false);
  protected readonly viewCardError = signal<string | null>(null);

  // ------------------------------------------------------- adjustment reasons
  protected readonly showReasonForm = signal(false);
  protected readonly reasonSubmitting = signal(false);
  protected readonly reasonError = signal<string | null>(null);
  protected readonly newReasonCode = signal('');
  protected readonly newReasonKind = signal<'BONUS' | 'PENALTY'>('BONUS');
  protected readonly newReasonOutcomeBasis = signal<string>('DELIVERED_VOLUME');
  protected readonly newReasonDisplayName = signal('');
  protected readonly newReasonWired = signal(false);
  protected readonly newReasonAmount = signal(0);
  protected readonly newReasonCurrency = signal('UZS');
  protected readonly newReasonComparator = signal<'GTE' | 'LTE'>('GTE');
  protected readonly simLookbackDays = signal(7);
  protected readonly simRunning = signal(false);
  protected readonly simError = signal<string | null>(null);
  protected readonly simulation = signal<RuleSimulation | null>(null);
  protected readonly newReasonThreshold = signal(0);
  protected readonly newReasonWindow = signal<'SHIFT' | 'SETTLEMENT_PERIOD'>('SHIFT');
  protected readonly newReasonTrigger = signal<'SHIFT_CLOSE' | 'SETTLEMENT_PERIOD_CLOSE'>(
    'SHIFT_CLOSE',
  );

  protected readonly archivingReasonId = signal<string | null>(null);
  protected readonly archiveReasonReason = signal('');
  protected readonly archiveReasonSubmitting = signal(false);
  protected readonly archiveReasonError = signal<string | null>(null);

  protected readonly outcomeBases = OUTCOME_BASES;

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.loading.set(false);
      return;
    }
    try {
      const [types, cards, reasons] = await Promise.all([
        this.api.types(scope.tenantId, this.includeArchivedTypes()),
        this.api.rateCards(scope.tenantId, scope.brandId),
        this.api.adjustmentReasons(scope.tenantId),
      ]);
      this.types.set(types);
      this.rateCards.set(cards);
      this.adjustmentReasons.set(reasons);
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

  protected async toggleIncludeArchivedTypes(): Promise<void> {
    this.includeArchivedTypes.update((value) => !value);
    await this.load();
  }

  // ------------------------------------------------------------- types

  protected openTypeForm(): void {
    this.newTypeCode.set('');
    this.newTypeDisplayName.set('');
    this.newTypeVehicleClass.set('SCOOTER');
    this.newTypeMinDistance.set(0);
    this.newTypeMaxDistance.set(null);
    this.newTypeMaxConcurrent.set(2);
    this.newTypeOfferTtl.set(60);
    this.newTypeStartingMinute.set(0);
    this.newTypeWorkMode.set('SHIFT');
    this.typeError.set(null);
    this.showTypeForm.set(true);
  }

  protected closeTypeForm(): void {
    this.showTypeForm.set(false);
  }

  protected canCreateType(): boolean {
    return (
      !this.typeSubmitting() &&
      this.newTypeCode().trim().length > 0 &&
      this.newTypeDisplayName().trim().length > 0 &&
      this.newTypeMaxConcurrent() > 0 &&
      this.newTypeOfferTtl() > 0
    );
  }

  protected async submitType(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canCreateType()) {
      return;
    }
    this.typeSubmitting.set(true);
    this.typeError.set(null);
    try {
      await this.api.createType(scope.tenantId, {
        code: this.newTypeCode().trim().toUpperCase(),
        displayName: this.newTypeDisplayName().trim(),
        vehicleClass: this.newTypeVehicleClass(),
        minDistanceMeters: this.newTypeMinDistance(),
        maxDistanceMeters: this.newTypeMaxDistance(),
        maxConcurrentAssignments: this.newTypeMaxConcurrent(),
        offerTtlSeconds: this.newTypeOfferTtl(),
        startingMinuteOffset: this.newTypeStartingMinute(),
        workMode: this.newTypeWorkMode(),
      });
      this.showTypeForm.set(false);
      await this.load();
    } catch (error) {
      this.typeError.set(this.describe(error));
    } finally {
      this.typeSubmitting.set(false);
    }
  }

  protected openEditType(type: CourierTypeResponse): void {
    this.editingType.set(type);
    this.editTypeCode.set(type.code);
    this.editTypeDisplayName.set(type.displayName);
    this.editTypeVehicleClass.set(type.vehicleClass);
    this.editTypeMinDistance.set(type.minDistanceMeters);
    this.editTypeMaxDistance.set(type.maxDistanceMeters ?? null);
    this.editTypeMaxConcurrent.set(type.maxConcurrentAssignments);
    this.editTypeOfferTtl.set(type.offerTtlSeconds);
    this.editTypeStartingMinute.set(type.startingMinuteOffset);
    this.editTypeWorkMode.set(type.workMode);
    this.editTypeReason.set('');
    this.editTypeError.set(null);
  }

  protected closeEditType(): void {
    this.editingType.set(null);
  }

  protected canSaveEditType(): boolean {
    return (
      !this.editTypeSubmitting() &&
      this.editTypeCode().trim().length > 0 &&
      this.editTypeDisplayName().trim().length > 0 &&
      this.editTypeMaxConcurrent() > 0 &&
      this.editTypeOfferTtl() > 0 &&
      this.editTypeReason().trim().length > 0
    );
  }

  protected async submitEditType(): Promise<void> {
    const scope = this.location.scope();
    const type = this.editingType();
    if (!scope || !type || !this.canSaveEditType()) {
      return;
    }
    this.editTypeSubmitting.set(true);
    this.editTypeError.set(null);
    try {
      await this.api.updateType(scope.tenantId, type.courierTypeId, {
        code: this.editTypeCode().trim().toUpperCase(),
        displayName: this.editTypeDisplayName().trim(),
        vehicleClass: this.editTypeVehicleClass(),
        minDistanceMeters: this.editTypeMinDistance(),
        maxDistanceMeters: this.editTypeMaxDistance(),
        maxConcurrentAssignments: this.editTypeMaxConcurrent(),
        offerTtlSeconds: this.editTypeOfferTtl(),
        startingMinuteOffset: this.editTypeStartingMinute(),
        workMode: this.editTypeWorkMode(),
        expectedVersion: type.version,
        reason: this.editTypeReason().trim(),
      });
      this.editingType.set(null);
      await this.load();
    } catch (error) {
      this.editTypeError.set(this.describe(error));
    } finally {
      this.editTypeSubmitting.set(false);
    }
  }

  protected startArchiveType(type: CourierTypeResponse): void {
    this.archivingTypeId.set(type.courierTypeId);
    this.archiveTypeReason.set('');
    this.archiveTypeError.set(null);
  }

  protected cancelArchiveType(): void {
    this.archivingTypeId.set(null);
  }

  protected canConfirmArchiveType(): boolean {
    return !this.archiveTypeSubmitting() && this.archiveTypeReason().trim().length > 0;
  }

  protected async confirmArchiveType(): Promise<void> {
    const scope = this.location.scope();
    const typeId = this.archivingTypeId();
    if (!scope || !typeId || !this.canConfirmArchiveType()) {
      return;
    }
    this.archiveTypeSubmitting.set(true);
    this.archiveTypeError.set(null);
    try {
      await this.api.archiveType(scope.tenantId, typeId, this.archiveTypeReason().trim());
      this.archivingTypeId.set(null);
      await this.load();
    } catch (error) {
      this.archiveTypeError.set(this.describe(error));
    } finally {
      this.archiveTypeSubmitting.set(false);
    }
  }

  // --------------------------------------------------------- rate cards

  protected openCardForm(): void {
    this.newCardCode.set('');
    this.newCardVersion.set(1);
    this.newCardCurrency.set('UZS');
    this.newCardLocationId.set('');
    this.newCardCourierTypeId.set('');
    this.newCardComponents.set([
      {
        componentType: 'PER_ORDER',
        priority: 0,
        amountMinor: 3000,
        bandFromMeters: null,
        bandToMeters: null,
        minimumPaidSeconds: null,
      },
    ]);
    this.cardError.set(null);
    this.showCardForm.set(true);
  }

  protected closeCardForm(): void {
    this.showCardForm.set(false);
  }

  /** A brand-new card reuses an authored code at the next version; editing an existing one is not possible (ADR 0042). */
  protected openReVersionCardForm(card: RateCardSummaryResponse): void {
    this.newCardCode.set(card.code);
    this.newCardVersion.set(card.cardVersion + 1);
    this.newCardCurrency.set(card.currency);
    this.newCardLocationId.set(card.locationId ?? '');
    this.newCardCourierTypeId.set(card.courierTypeId ?? '');
    this.newCardComponents.set([
      {
        componentType: 'PER_ORDER',
        priority: 0,
        amountMinor: 3000,
        bandFromMeters: null,
        bandToMeters: null,
        minimumPaidSeconds: null,
      },
    ]);
    this.cardError.set(null);
    this.showCardForm.set(true);
  }

  protected addComponent(componentType: string): void {
    this.newCardComponents.update((components) => [
      ...components,
      componentType === 'PER_KM_BAND'
        ? {
            componentType,
            priority: components.length,
            amountMinor: 0,
            bandFromMeters: this.nextBandStart(components),
            bandToMeters: null,
            minimumPaidSeconds: null,
          }
        : {
            componentType,
            priority: components.length,
            amountMinor: 0,
            bandFromMeters: null,
            bandToMeters: null,
            minimumPaidSeconds: componentType === 'PER_SHIFT_FIXED' ? 3600 : null,
          },
    ]);
  }

  /** The next band starts where the widest existing band ends, so appending one keeps the ladder contiguous by default. */
  private nextBandStart(components: readonly ComponentDraft[]): number {
    const bands = components.filter((c) => c.componentType === 'PER_KM_BAND');
    if (bands.length === 0) {
      return 0;
    }
    const bounded = bands.filter((b) => b.bandToMeters !== null);
    return bounded.length === bands.length
      ? Math.max(...bounded.map((b) => b.bandToMeters ?? 0))
      : 0;
  }

  protected removeComponent(index: number): void {
    this.newCardComponents.update((components) => components.filter((_, i) => i !== index));
  }

  protected updateComponentAmount(index: number, value: number): void {
    this.newCardComponents.update((components) =>
      components.map((c, i) => (i === index ? { ...c, amountMinor: value } : c)),
    );
  }

  protected updateComponentBandFrom(index: number, value: number): void {
    this.newCardComponents.update((components) =>
      components.map((c, i) => (i === index ? { ...c, bandFromMeters: value } : c)),
    );
  }

  protected updateComponentBandTo(index: number, value: number | null): void {
    this.newCardComponents.update((components) =>
      components.map((c, i) => (i === index ? { ...c, bandToMeters: value } : c)),
    );
  }

  protected updateComponentMinimumSeconds(index: number, value: number): void {
    this.newCardComponents.update((components) =>
      components.map((c, i) => (i === index ? { ...c, minimumPaidSeconds: value } : c)),
    );
  }

  protected bandComponents(): readonly ComponentDraft[] {
    return this.newCardComponents().filter((c) => c.componentType === 'PER_KM_BAND');
  }

  protected canCreateCard(): boolean {
    return (
      !this.cardSubmitting() &&
      this.newCardCode().trim().length > 0 &&
      this.newCardVersion() > 0 &&
      this.newCardCurrency().trim().length === 3 &&
      this.newCardComponents().length > 0 &&
      this.newCardComponents().every((c) => c.amountMinor >= 0)
    );
  }

  protected async submitCard(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canCreateCard()) {
      return;
    }
    this.cardSubmitting.set(true);
    this.cardError.set(null);
    try {
      const components: RateComponentRequest[] = this.newCardComponents().map((draft) => ({
        componentType: draft.componentType,
        priority: draft.priority,
        amountMinor: draft.amountMinor,
        bandFromMeters: draft.bandFromMeters,
        bandToMeters: draft.bandToMeters,
        minimumPaidSeconds: draft.minimumPaidSeconds,
      }));
      await this.api.authorRateCard(scope.tenantId, {
        brandId: scope.brandId,
        locationId: this.newCardLocationId() || null,
        courierTypeId: this.newCardCourierTypeId() || null,
        code: this.newCardCode().trim().toUpperCase(),
        cardVersion: this.newCardVersion(),
        currency: this.newCardCurrency().trim().toUpperCase(),
        components,
      });
      this.showCardForm.set(false);
      await this.load();
    } catch (error) {
      this.cardError.set(this.describe(error));
    } finally {
      this.cardSubmitting.set(false);
    }
  }

  protected canActivate(card: RateCardSummaryResponse): boolean {
    return card.status === 'DRAFT' && this.activatingCardId() === null;
  }

  protected async activate(card: RateCardSummaryResponse): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canActivate(card)) {
      return;
    }
    this.activatingCardId.set(card.cardId);
    this.activateError.set(null);
    try {
      await this.api.activateRateCard(scope.tenantId, card.cardId, 'Activated from IA 3.4');
      await this.load();
    } catch (error) {
      this.activateError.set(this.describe(error));
    } finally {
      this.activatingCardId.set(null);
    }
  }

  protected cardScopeLabel(card: RateCardSummaryResponse): string {
    if (card.locationId) {
      return this.i18n.t('delivery.rates.scope.location');
    }
    if (card.courierTypeId) {
      return this.i18n.t('delivery.rates.scope.courierType');
    }
    return this.i18n.t('delivery.rates.scope.brand');
  }

  // ---------------------------------------------------------- card detail

  protected async openCardDetail(card: RateCardSummaryResponse): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.viewingCard.set(null);
    this.viewingCardCode.set(card.code);
    this.viewCardError.set(null);
    this.viewCardLoading.set(true);
    try {
      this.viewingCard.set(await this.api.rateCard(scope.tenantId, card.cardId));
    } catch (error) {
      this.viewCardError.set(this.describe(error));
    } finally {
      this.viewCardLoading.set(false);
    }
  }

  protected closeCardDetail(): void {
    this.viewingCard.set(null);
  }

  protected componentTypeLabel(componentType: string): string {
    switch (componentType) {
      case 'PER_ORDER':
        return this.i18n.t('delivery.rates.componentType.PER_ORDER');
      case 'PER_SHIFT_FIXED':
        return this.i18n.t('delivery.rates.componentType.PER_SHIFT_FIXED');
      case 'PER_KM_BAND':
        return this.i18n.t('delivery.rates.componentType.PER_KM_BAND');
      case 'PER_ORDER_MINIMUM':
        return this.i18n.t('delivery.rates.componentType.PER_ORDER_MINIMUM');
      default:
        return componentType;
    }
  }

  protected workModeLabel(workMode: string): string {
    switch (workMode) {
      case 'SHIFT':
        return this.i18n.t('delivery.rates.types.workMode.SHIFT');
      case 'ON_DEMAND':
        return this.i18n.t('delivery.rates.types.workMode.ON_DEMAND');
      default:
        return workMode;
    }
  }

  protected reasonKindLabel(kind: string): string {
    return kind === 'PENALTY'
      ? this.i18n.t('delivery.rates.reasons.kind.PENALTY')
      : this.i18n.t('delivery.rates.reasons.kind.BONUS');
  }

  protected sortedComponents(
    components: readonly RateComponentView[],
  ): readonly RateComponentView[] {
    return [...components].sort((a, b) => {
      if (a.componentType === 'PER_KM_BAND' && b.componentType === 'PER_KM_BAND') {
        return (a.bandFromMeters ?? 0) - (b.bandFromMeters ?? 0);
      }
      return a.priority - b.priority;
    });
  }

  // ------------------------------------------------------ adjustment reasons

  protected openReasonForm(): void {
    this.newReasonCode.set('');
    this.newReasonKind.set('BONUS');
    this.newReasonOutcomeBasis.set('DELIVERED_VOLUME');
    this.newReasonDisplayName.set('');
    this.newReasonWired.set(false);
    this.newReasonAmount.set(0);
    this.newReasonCurrency.set('UZS');
    this.newReasonComparator.set('GTE');
    this.newReasonThreshold.set(0);
    this.newReasonWindow.set('SHIFT');
    this.newReasonTrigger.set('SHIFT_CLOSE');
    this.simulation.set(null);
    this.simError.set(null);
    this.reasonError.set(null);
    this.showReasonForm.set(true);
  }

  protected closeReasonForm(): void {
    this.showReasonForm.set(false);
  }

  /** ORDER_UNDELIVERED and ORDER_DAMAGED have no evaluator reader (ADR 0108) — wiring them to a rule would define a rule that never fires. */
  protected basisIsEvaluated(basis: string): boolean {
    return EVALUATED_BASES.has(basis);
  }

  /** Whether the evaluator reads this basis over a settlement period only, never a shift. */
  protected basisIsPeriodOnly(basis: string): boolean {
    return PERIOD_ONLY_BASES.has(basis);
  }

  /**
   * The outcome picker's change handler. Choosing a period-only basis pins the window and the
   * trigger to the settlement period, so the rule the operator ends up with is one that fires.
   */
  protected chooseOutcomeBasis(basis: string): void {
    this.newReasonOutcomeBasis.set(basis);
    if (this.basisIsPeriodOnly(basis)) {
      this.newReasonWindow.set('SETTLEMENT_PERIOD');
      this.newReasonTrigger.set('SETTLEMENT_PERIOD_CLOSE');
    }
  }

  protected canCreateReason(): boolean {
    if (
      this.reasonSubmitting() ||
      this.newReasonCode().trim().length === 0 ||
      this.newReasonDisplayName().trim().length === 0
    ) {
      return false;
    }
    if (this.newReasonWired() && !this.basisIsEvaluated(this.newReasonOutcomeBasis())) {
      return false;
    }
    if (
      this.newReasonWired() &&
      this.basisIsPeriodOnly(this.newReasonOutcomeBasis()) &&
      (this.newReasonWindow() !== 'SETTLEMENT_PERIOD' ||
        this.newReasonTrigger() !== 'SETTLEMENT_PERIOD_CLOSE')
    ) {
      return false;
    }
    if (this.newReasonWired()) {
      return this.newReasonCurrency().trim().length === 3 && this.newReasonThreshold() >= 0;
    }
    return true;
  }

  protected async submitReasonForm(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canCreateReason()) {
      return;
    }
    this.reasonSubmitting.set(true);
    this.reasonError.set(null);
    try {
      const signedAmount =
        this.newReasonKind() === 'PENALTY'
          ? -Math.abs(this.newReasonAmount())
          : Math.abs(this.newReasonAmount());
      const request: CreateAdjustmentReasonRequest = {
        code: this.newReasonCode().trim().toUpperCase(),
        kind: this.newReasonKind(),
        outcomeBasis: this.newReasonOutcomeBasis(),
        displayName: this.newReasonDisplayName().trim(),
        ...(this.newReasonWired()
          ? {
              ruleAmountMinor: signedAmount,
              ruleCurrency: this.newReasonCurrency().trim().toUpperCase(),
              ruleComparator: this.newReasonComparator(),
              ruleThreshold: this.newReasonThreshold(),
              ruleWindow: this.newReasonWindow(),
              ruleTrigger: this.newReasonTrigger(),
            }
          : {}),
      };
      await this.api.createAdjustmentReason(scope.tenantId, request);
      this.showReasonForm.set(false);
      await this.load();
    } catch (error) {
      this.reasonError.set(this.describe(error));
    } finally {
      this.reasonSubmitting.set(false);
    }
  }

  // ------------------------------------------------------------ the simulator

  /** The one rule the simulator draws: the candidate in the form, named as the operator named it. */
  protected readonly simRules = computed<readonly SimulatedRule[]>(() => [
    {
      id: 'candidate',
      label: this.newReasonDisplayName().trim() || this.newReasonCode().trim().toUpperCase(),
      enabled: true,
      groups: [],
      outcome: '',
    },
  ]);

  /** The platform's verdict in the shape the shared simulator draws in its engine mode; null before one exists. */
  protected readonly simVerdict = computed<readonly ExternalRuleResult[] | null>(() => {
    const result = this.simulation();
    if (result === null) {
      return null;
    }
    const label = this.simRules()[0].label;
    if (result.couriersHit === 0) {
      return [
        {
          id: 'candidate',
          label,
          state: 'unmatched',
          detail: this.i18n.t('delivery.rates.reasons.sim.nobody', {
            days: result.lookbackDays,
            windows: result.windowsReplayed,
          }),
        },
      ];
    }
    return [
      {
        id: 'candidate',
        label,
        state: 'matched',
        detail: this.i18n.t('delivery.rates.reasons.sim.hit', {
          couriers: result.couriersHit,
          total: this.simMoney(result.totalAmountMinor, result.currency),
          windows: result.windowsReplayed,
          days: result.lookbackDays,
        }),
      },
    ];
  });

  /** A preview needs a wired, evaluated rule: there is nothing to replay for a manual-only reason. */
  protected canSimulate(): boolean {
    return (
      !this.simRunning() &&
      this.newReasonWired() &&
      this.basisIsEvaluated(this.newReasonOutcomeBasis()) &&
      this.newReasonCurrency().trim().length === 3 &&
      Number.isFinite(this.newReasonThreshold()) &&
      this.newReasonThreshold() >= 0
    );
  }

  protected async runSimulation(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canSimulate()) {
      return;
    }
    this.simRunning.set(true);
    this.simError.set(null);
    try {
      const signedAmount =
        this.newReasonKind() === 'PENALTY'
          ? -Math.abs(this.newReasonAmount())
          : Math.abs(this.newReasonAmount());
      this.simulation.set(
        await this.api.simulateRule(scope.tenantId, {
          outcomeBasis: this.newReasonOutcomeBasis(),
          ruleComparator: this.newReasonComparator(),
          ruleThreshold: this.newReasonThreshold(),
          ruleWindow: this.newReasonWindow(),
          ruleAmountMinor: signedAmount,
          ruleCurrency: this.newReasonCurrency().trim().toUpperCase(),
          lookbackDays: this.simLookbackDays(),
          // No locationId, deliberately: the rule being previewed is a tenant-level registry row and
          // fires at every branch's shift close, so the preview replays every branch's shifts. Sending
          // the operator's branch narrows SHIFT windows to it and understates who the saved rule hits.
        }),
      );
    } catch (error) {
      this.simulation.set(null);
      this.simError.set(this.describe(error));
    } finally {
      this.simRunning.set(false);
    }
  }

  protected simMoney(minor: number, currency: string): string {
    return formatMoney({ amountMinor: minor, currency }, this.i18n.locale(), { withUnit: true });
  }

  protected startArchiveReason(reason: AdjustmentReasonResponse): void {
    this.archivingReasonId.set(reason.reasonId);
    this.archiveReasonReason.set('');
    this.archiveReasonError.set(null);
  }

  protected cancelArchiveReason(): void {
    this.archivingReasonId.set(null);
  }

  protected canConfirmArchiveReason(): boolean {
    return !this.archiveReasonSubmitting() && this.archiveReasonReason().trim().length > 0;
  }

  protected async confirmArchiveReason(): Promise<void> {
    const scope = this.location.scope();
    const reasonId = this.archivingReasonId();
    if (!scope || !reasonId || !this.canConfirmArchiveReason()) {
      return;
    }
    this.archiveReasonSubmitting.set(true);
    this.archiveReasonError.set(null);
    try {
      await this.api.archiveAdjustmentReason(
        scope.tenantId,
        reasonId,
        this.archiveReasonReason().trim(),
      );
      this.archivingReasonId.set(null);
      await this.load();
    } catch (error) {
      this.archiveReasonError.set(this.describe(error));
    } finally {
      this.archiveReasonSubmitting.set(false);
    }
  }

  protected reasonRuleSummary(reason: AdjustmentReasonResponse): string {
    if (!reason.hasRule) {
      return this.i18n.t('delivery.rates.reasons.manualOnly');
    }
    const comparator = reason.ruleComparator === 'LTE' ? '≤' : '≥';
    const amount =
      reason.ruleAmountMinor !== null && reason.ruleAmountMinor !== undefined && reason.ruleCurrency
        ? formatMoney(
            { amountMinor: reason.ruleAmountMinor, currency: reason.ruleCurrency },
            this.i18n.locale(),
            { withUnit: true },
          )
        : '—';
    return `${reason.outcomeBasis} ${comparator} ${reason.ruleThreshold} → ${amount}`;
  }

  /** A rate-card component's amount as a bare number the way the brand writes money (Settings 10.12). */
  protected componentAmount(minor: number, currency: string): string {
    return formatMoney({ amountMinor: minor, currency }, this.i18n.locale());
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
