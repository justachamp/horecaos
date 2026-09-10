import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

import { ApiError } from '../../core/api/problem';
import { APP_CONFIG } from '../../core/config/app-config';
import { I18nService } from '../../core/i18n/i18n.service';
import {
  BrandView,
  LegalEntityView,
  LocationFiscalAssignmentView,
  LocationView,
  TenantsApi,
} from './tenants-api';

/** A location as the assign form offers it: under its brand, never as a bare id. */
interface PickableLocation {
  readonly brand: BrandView;
  readonly location: LocationView;
}

/**
 * IA 2.4 Legal entities & tax identities -- the INN/legal-entity registry
 * behind branches, and which entity each branch fiscalizes under.
 *
 * The assign form offers the tenant's own locations, grouped by brand. It used
 * to ask for a brand id and a location id typed by hand, which nobody setting
 * up a tenant has to hand; the section below the registry shows where each
 * location stands, because "which entity does this branch sell as" is the
 * question an operator arrives with -- usually from a failed onboarding step.
 */
@Component({
  selector: 'app-tenant-legal-entities',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './tenant-legal-entities.html',
  styleUrl: './tenant-legal-entities.css',
})
export class TenantLegalEntities {
  protected readonly i18n = inject(I18nService);
  private readonly tenantsApi = inject(TenantsApi);
  private readonly route = inject(ActivatedRoute);
  private readonly config = inject(APP_CONFIG);

  protected readonly tenantId = this.route.snapshot.paramMap.get('tenantId')!;

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly entities = signal<readonly LegalEntityView[]>([]);
  protected readonly actionError = signal<string | null>(null);

  protected readonly registering = signal(false);
  protected readonly submitting = signal(false);
  protected readonly code = signal('');
  protected readonly legalName = signal('');
  protected readonly tin = signal('');
  protected readonly vatRegistered = signal(false);

  protected readonly assigningEntityId = signal<string | null>(null);
  /** `brandId/locationId`, as the location picker's value. */
  protected readonly assignLocationKey = signal('');
  protected readonly assignDone = signal<string | null>(null);

  protected readonly locations = signal<readonly PickableLocation[]>([]);
  /** Every location's assignments, most recent first, keyed by location id. */
  protected readonly assignments = signal<ReadonlyMap<string, readonly LocationFiscalAssignmentView[]>>(new Map());
  protected readonly historyOpen = signal<string | null>(null);
  protected readonly detailsOpen = signal<string | null>(null);
  protected readonly assignEffectiveFrom = signal('');
  protected readonly assignSubmitting = signal(false);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const [entities, brands] = await Promise.all([
        this.tenantsApi.getLegalEntities(this.tenantId),
        this.tenantsApi.getBrands(this.tenantId),
      ]);
      this.entities.set(entities);
      const perBrand = await Promise.all(
        brands.map(async (brand) =>
          (await this.tenantsApi.getLocations(this.tenantId, brand.id)).map((location) => ({ brand, location })),
        ),
      );
      this.locations.set(perBrand.flat());
      await Promise.all(this.locations().map((pick) => this.loadAssignments(pick)));
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  private async loadAssignments(pick: PickableLocation): Promise<void> {
    const history = await this.tenantsApi.getLocationAssignments(this.tenantId, pick.brand.id, pick.location.id);
    this.assignments.update((current) => new Map(current).set(pick.location.id, history));
  }

  /** Today in the console's timezone, as the yyyy-mm-dd a date input takes. */
  private today(): string {
    return new Intl.DateTimeFormat('en-CA', { timeZone: this.config.displayTimeZone }).format(new Date());
  }

  /** The assignment in force today, if any: started, and not yet ended. */
  protected currentAssignment(locationId: string): LocationFiscalAssignmentView | null {
    const today = this.today();
    return (
      (this.assignments().get(locationId) ?? []).find(
        (a) => a.effectiveFrom <= today && (a.effectiveUntil === null || a.effectiveUntil > today),
      ) ?? null
    );
  }

  protected history(locationId: string): readonly LocationFiscalAssignmentView[] {
    return this.assignments().get(locationId) ?? [];
  }

  protected entity(entityId: string): LegalEntityView | undefined {
    return this.entities().find((e) => e.id === entityId);
  }

  protected toggleHistory(locationId: string): void {
    this.historyOpen.update((open) => (open === locationId ? null : locationId));
  }

  protected toggleDetails(entityId: string): void {
    this.detailsOpen.update((open) => (open === entityId ? null : entityId));
  }

  protected openRegister(): void {
    this.registering.set(true);
    this.actionError.set(null);
  }

  protected closeRegister(): void {
    this.registering.set(false);
  }

  protected canSubmit(): boolean {
    return (
      !this.submitting() &&
      this.code().trim().length > 0 &&
      this.legalName().trim().length > 0 &&
      /^[0-9]{9}$/.test(this.tin().trim())
    );
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canSubmit()) {
      return;
    }
    this.submitting.set(true);
    this.actionError.set(null);
    try {
      const entity = await this.tenantsApi.registerLegalEntity(this.tenantId, {
        code: this.code().trim(),
        legalName: this.legalName().trim(),
        tin: this.tin().trim(),
        vatRegistered: this.vatRegistered(),
      });
      this.entities.update((entities) => [...entities, entity]);
      this.registering.set(false);
      this.code.set('');
      this.legalName.set('');
      this.tin.set('');
      this.vatRegistered.set(false);
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.submitting.set(false);
    }
  }

  protected async activate(entity: LegalEntityView): Promise<void> {
    this.actionError.set(null);
    try {
      const activated = await this.tenantsApi.activateLegalEntity(this.tenantId, entity.id, entity.version);
      this.entities.update((entities) => entities.map((e) => (e.id === entity.id ? activated : e)));
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    }
  }

  protected openAssign(entityId: string): void {
    this.assigningEntityId.set(entityId);
    this.assignLocationKey.set('');
    this.assignEffectiveFrom.set(this.today());
    this.assignDone.set(null);
    this.actionError.set(null);
  }

  protected closeAssign(): void {
    this.assigningEntityId.set(null);
  }

  protected canSubmitAssign(): boolean {
    return (
      !this.assignSubmitting() &&
      this.assignLocationKey().length > 0 &&
      this.assignEffectiveFrom().trim().length > 0
    );
  }

  protected async submitAssign(event: Event): Promise<void> {
    event.preventDefault();
    const entityId = this.assigningEntityId();
    if (entityId === null || !this.canSubmitAssign()) {
      return;
    }
    this.assignSubmitting.set(true);
    this.actionError.set(null);
    const pick = this.locations().find(
      (candidate) => `${candidate.brand.id}/${candidate.location.id}` === this.assignLocationKey(),
    );
    if (pick === undefined) {
      return;
    }
    try {
      await this.tenantsApi.assignLegalEntity(this.tenantId, entityId, {
        brandId: pick.brand.id,
        locationId: pick.location.id,
        effectiveFrom: this.assignEffectiveFrom().trim(),
      });
      this.assigningEntityId.set(null);
      this.assignLocationKey.set('');
      this.assignDone.set(this.i18n.t('legalEntities.assign.done'));
      await this.loadAssignments(pick);
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.assignSubmitting.set(false);
    }
  }
}
