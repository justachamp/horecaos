import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';

import { Auth } from '../../core/auth/auth';
import { CurrentBrand } from '../../core/auth/current-brand';
import { CurrentLocation } from '../../core/auth/current-location';
import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { DeliveryZonesApi, ZoneDetailResponse, ZoneSummaryResponse } from './delivery-zones-api';

/**
 * Delivery zones — operations §3.6.
 *
 * **Built.** List with the live version's numbers (priority, currency, free-
 * delivery threshold, minimum basket, bound tariff); register a zone; draft a
 * circle version around a branch and activate it; bind the zone to a branch.
 * Read and detail (`GET .../service-zones`, `GET .../service-zones/{id}`) are
 * this wave's own addition — see the wave's final report — the write path
 * (`POST` create/draft/activate/bind) already existed.
 *
 * **Reduced relative to the spec, honestly.** No `MapCanvas`/`PolygonEditor`
 * exists in this design system (IA Part 4's own "Pilot blockers" table), so
 * this authors circles only — the backend's `CircleRequest` shape, not the
 * free-hand polygon `DraftVersionRequest` also accepts. Bulk geozone upload
 * is not built: the backend has no batch import endpoint at all (`the seed
 * tool` the task brief names loops the same single-zone REST calls this page
 * makes, one at a time — there is no dedicated bulk format to build a
 * frontend for).
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
  private readonly brand = inject(CurrentBrand);
  private readonly location = inject(CurrentLocation);
  private readonly auth = inject(Auth);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly zones = signal<readonly ZoneSummaryResponse[]>([]);

  protected readonly expandedZoneId = signal<string | null>(null);
  protected readonly detailByZoneId = signal<ReadonlyMap<string, ZoneDetailResponse>>(new Map());

  protected readonly showCreateForm = signal(false);
  protected readonly createSubmitting = signal(false);
  protected readonly createError = signal<string | null>(null);
  protected readonly newCode = signal('');
  protected readonly newName = signal('');

  protected readonly draftingZoneId = signal<string | null>(null);
  protected readonly draftSubmitting = signal(false);
  protected readonly draftError = signal<string | null>(null);
  protected readonly draftRadiusMeters = signal(3000);
  protected readonly draftPriority = signal(0);
  protected readonly draftCurrency = signal('UZS');
  protected readonly draftFreeFromMinor = signal<number | null>(null);
  protected readonly draftMinBasketMinor = signal<number | null>(null);

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

  // ---------------------------------------------------------------- expand

  protected isExpanded(zone: ZoneSummaryResponse): boolean {
    return this.expandedZoneId() === zone.zoneId;
  }

  protected async toggleExpand(zone: ZoneSummaryResponse): Promise<void> {
    if (this.isExpanded(zone)) {
      this.expandedZoneId.set(null);
      return;
    }
    this.expandedZoneId.set(zone.zoneId);
    const scope = this.brand.scope();
    if (!scope || this.detailByZoneId().has(zone.zoneId)) {
      return;
    }
    try {
      const detail = await this.api.detail(scope, zone.zoneId);
      this.detailByZoneId.update((current) => new Map(current).set(zone.zoneId, detail));
    } catch {
      // The row still renders with its list-row numbers; the detail panel
      // simply stays empty — see the template.
    }
  }

  protected detailFor(zone: ZoneSummaryResponse): ZoneDetailResponse | null {
    return this.detailByZoneId().get(zone.zoneId) ?? null;
  }

  // --------------------------------------------------------------- create

  protected openCreateForm(): void {
    this.newCode.set('');
    this.newName.set('');
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
        role: 'DELIVERY',
        code: this.newCode().trim(),
        displayNameRu: this.newName().trim(),
        displayNameUz: this.newName().trim(),
        displayNameEn: this.newName().trim(),
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
    this.draftRadiusMeters.set(3000);
    this.draftPriority.set(0);
    this.draftCurrency.set('UZS');
    this.draftFreeFromMinor.set(null);
    this.draftMinBasketMinor.set(null);
    this.draftError.set(null);
    this.draftingZoneId.set(zone.zoneId);
  }

  protected closeDraftForm(): void {
    this.draftingZoneId.set(null);
  }

  protected canDraft(): boolean {
    return (
      !this.draftSubmitting() && this.draftRadiusMeters() > 0 && this.location.scope() !== null
    );
  }

  protected async submitDraft(): Promise<void> {
    const scope = this.brand.scope();
    const locationScope = this.location.scope();
    const zoneId = this.draftingZoneId();
    const actorId = this.auth.subject();
    if (!scope || !zoneId || !locationScope || !actorId || !this.canDraft()) {
      return;
    }
    this.draftSubmitting.set(true);
    this.draftError.set(null);
    try {
      const drafted = await this.api.draftCircleVersion(scope, zoneId, {
        originLocationId: locationScope.locationId,
        radiusMeters: this.draftRadiusMeters(),
        priority: this.draftPriority(),
        currency: this.draftCurrency(),
        freeDeliveryFromMinor: this.draftFreeFromMinor(),
        minBasketMinor: this.draftMinBasketMinor(),
        actorId,
      });
      await this.api.activate(scope, zoneId, drafted.version, actorId);
      await this.api.bindLocation(scope, zoneId, locationScope.locationId);
      this.draftingZoneId.set(null);
      this.detailByZoneId.update((current) => {
        const next = new Map(current);
        next.delete(zoneId);
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
