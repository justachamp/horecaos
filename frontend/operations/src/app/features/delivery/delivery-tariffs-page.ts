import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { Auth } from '../../core/auth/auth';
import { CurrentBrand } from '../../core/auth/current-brand';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import {
  DeliveryTariffsApi,
  TariffDetailResponse,
  TariffSummaryResponse,
} from './delivery-tariffs-api';

/**
 * Delivery tariffs — operations §3.7.
 *
 * **Built.** List with the live version's headline numbers (currency, fee
 * source, distance mode, reach); register a tariff; draft a single flat-band
 * version (base + per-km across the whole reach) and activate it. Read and
 * detail (`GET .../delivery-tariffs`, `GET .../delivery-tariffs/{id}`) are
 * this wave's own addition — the write path already existed.
 *
 * **Reduced relative to the spec.** Multi-band tables, peak-hour time rules
 * and standing discounts are real backend features (`draftFlatVersion`'s own
 * doc explains why this form drafts one band); the detail panel still shows
 * every band/time-rule/discount an already-activated tariff carries, so a
 * tariff authored by the seed tool or a future richer editor renders in
 * full — this page just does not author more than one band itself.
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
  private readonly auth = inject(Auth);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly tariffs = signal<readonly TariffSummaryResponse[]>([]);

  protected readonly expandedTariffId = signal<string | null>(null);
  protected readonly detailByTariffId = signal<ReadonlyMap<string, TariffDetailResponse>>(
    new Map(),
  );

  protected readonly showCreateForm = signal(false);
  protected readonly createSubmitting = signal(false);
  protected readonly createError = signal<string | null>(null);
  protected readonly newCode = signal('');
  protected readonly newName = signal('');
  protected readonly newBrandDefault = signal(false);

  protected readonly draftingTariffId = signal<string | null>(null);
  protected readonly draftSubmitting = signal(false);
  protected readonly draftError = signal<string | null>(null);
  protected readonly draftCurrency = signal('UZS');
  protected readonly draftMaxDistanceMeters = signal(15_000);
  protected readonly draftMinFeeMinor = signal(0);
  protected readonly draftBaseMinor = signal(10_000);
  protected readonly draftPerKmMinor = signal(2_000);

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
    } catch {
      // The row still renders with its list-row numbers.
    }
  }

  protected detailFor(tariff: TariffSummaryResponse): TariffDetailResponse | null {
    return this.detailByTariffId().get(tariff.tariffId) ?? null;
  }

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

  protected openDraftForm(tariff: TariffSummaryResponse): void {
    this.draftCurrency.set('UZS');
    this.draftMaxDistanceMeters.set(15_000);
    this.draftMinFeeMinor.set(0);
    this.draftBaseMinor.set(10_000);
    this.draftPerKmMinor.set(2_000);
    this.draftError.set(null);
    this.draftingTariffId.set(tariff.tariffId);
  }

  protected closeDraftForm(): void {
    this.draftingTariffId.set(null);
  }

  protected canDraft(): boolean {
    return (
      !this.draftSubmitting() && this.draftMaxDistanceMeters() > 0 && this.auth.subject() !== null
    );
  }

  protected async submitDraft(): Promise<void> {
    const scope = this.brand.scope();
    const tariffId = this.draftingTariffId();
    const actorId = this.auth.subject();
    if (!scope || !tariffId || !actorId || !this.canDraft()) {
      return;
    }
    this.draftSubmitting.set(true);
    this.draftError.set(null);
    try {
      const drafted = await this.api.draftFlatVersion(scope, tariffId, {
        currency: this.draftCurrency(),
        feeSource: 'TARIFF',
        distanceMode: 'RADIUS',
        maxDistanceMeters: this.draftMaxDistanceMeters(),
        minFeeMinor: this.draftMinFeeMinor(),
        baseMinor: this.draftBaseMinor(),
        perKmMinor: this.draftPerKmMinor(),
        actorId,
      });
      await this.api.activate(scope, tariffId, drafted.version, actorId);
      this.draftingTariffId.set(null);
      this.detailByTariffId.update((current) => {
        const next = new Map(current);
        next.delete(tariffId);
        return next;
      });
      await this.load();
    } catch (error) {
      this.draftError.set(this.describe(error));
    } finally {
      this.draftSubmitting.set(false);
    }
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
