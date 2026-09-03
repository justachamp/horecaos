import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import {
  CouriersApi,
  CourierTypeResponse,
  RateCardSummaryResponse,
  RateComponentRequest,
} from '../couriers/couriers-api';
import { describeApiError } from '../orders/order-errors';

/**
 * IA 3.4 — Courier types & rates.
 *
 * **Built**: vehicle-class authoring (`courier.type.manage`, this wave's new
 * `POST /courier-types`) alongside the roster's existing read; rate-card
 * listing, draft authoring and activation (`courier.ratecard.manage/.read`,
 * this wave's new endpoints over `CourierRateCardService`, which existed
 * with no controller before it — see the wave's final report).
 *
 * **Reduced relative to the full band editor, deliberately.** ADR 0042's
 * rate card supports four component types with a distance-band ladder
 * (`PER_KM_BAND` with `bandFromMeters`/`bandToMeters`); authoring a full
 * ladder is a `DataGrid`-shaped tool the design system does not have (IA
 * Part 4's own gap list). This form drafts the two flat components —
 * `PER_ORDER` and `PER_SHIFT_FIXED` — which is a real, activatable card, not
 * a mock: a tenant paying strictly per delivery or per shift is fully
 * served, and a distance-tiered tariff is authored later once the band
 * editor exists, on the same card entity.
 */
@Component({
  selector: 'q-courier-types-rates-page',
  imports: [TPipe],
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

  // -------------------------------------------------------- type authoring
  protected readonly showTypeForm = signal(false);
  protected readonly typeSubmitting = signal(false);
  protected readonly typeError = signal<string | null>(null);
  protected readonly newTypeCode = signal('');
  protected readonly newTypeDisplayName = signal('');
  protected readonly newTypeVehicleClass = signal('SCOOTER');
  protected readonly newTypeMinDistance = signal(0);
  protected readonly newTypeMaxConcurrent = signal(2);
  protected readonly newTypeOfferTtl = signal(60);

  // -------------------------------------------------- rate card authoring
  protected readonly showCardForm = signal(false);
  protected readonly cardSubmitting = signal(false);
  protected readonly cardError = signal<string | null>(null);
  protected readonly newCardCode = signal('');
  protected readonly newCardCurrency = signal('UZS');
  protected readonly newCardPerOrderMinor = signal(3000);
  protected readonly newCardPerShiftMinor = signal(0);

  protected readonly activatingCardId = signal<string | null>(null);
  protected readonly activateError = signal<string | null>(null);

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
      const [types, cards] = await Promise.all([
        this.api.types(scope.tenantId),
        this.api.rateCards(scope.tenantId, scope.brandId),
      ]);
      this.types.set(types);
      this.rateCards.set(cards);
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

  // ------------------------------------------------------------- types

  protected openTypeForm(): void {
    this.newTypeCode.set('');
    this.newTypeDisplayName.set('');
    this.newTypeVehicleClass.set('SCOOTER');
    this.newTypeMinDistance.set(0);
    this.newTypeMaxConcurrent.set(2);
    this.newTypeOfferTtl.set(60);
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
        maxConcurrentAssignments: this.newTypeMaxConcurrent(),
        offerTtlSeconds: this.newTypeOfferTtl(),
      });
      this.showTypeForm.set(false);
      await this.load();
    } catch (error) {
      this.typeError.set(this.describe(error));
    } finally {
      this.typeSubmitting.set(false);
    }
  }

  // --------------------------------------------------------- rate cards

  protected openCardForm(): void {
    this.newCardCode.set('');
    this.newCardCurrency.set('UZS');
    this.newCardPerOrderMinor.set(3000);
    this.newCardPerShiftMinor.set(0);
    this.cardError.set(null);
    this.showCardForm.set(true);
  }

  protected closeCardForm(): void {
    this.showCardForm.set(false);
  }

  protected canCreateCard(): boolean {
    return (
      !this.cardSubmitting() &&
      this.newCardCode().trim().length > 0 &&
      this.newCardCurrency().trim().length === 3 &&
      (this.newCardPerOrderMinor() > 0 || this.newCardPerShiftMinor() > 0)
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
      const components: RateComponentRequest[] = [];
      if (this.newCardPerOrderMinor() > 0) {
        components.push({
          componentType: 'PER_ORDER',
          priority: 0,
          amountMinor: this.newCardPerOrderMinor(),
        });
      }
      if (this.newCardPerShiftMinor() > 0) {
        components.push({
          componentType: 'PER_SHIFT_FIXED',
          priority: 0,
          amountMinor: this.newCardPerShiftMinor(),
          minimumPaidSeconds: 3600,
        });
      }
      await this.api.authorRateCard(scope.tenantId, {
        brandId: scope.brandId,
        code: this.newCardCode().trim().toUpperCase(),
        cardVersion: 1,
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

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
