import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { LegalEntityView, TenantsApi } from './tenants-api';

/**
 * IA 2.4 Legal entities & tax identities -- the INN/legal-entity registry
 * behind branches, and which entity each branch fiscalizes under (ADR 0038).
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
  protected readonly assignBrandId = signal('');
  protected readonly assignLocationId = signal('');
  protected readonly assignEffectiveFrom = signal('');
  protected readonly assignSubmitting = signal(false);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.entities.set(await this.tenantsApi.getLegalEntities(this.tenantId));
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
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
    this.actionError.set(null);
  }

  protected closeAssign(): void {
    this.assigningEntityId.set(null);
  }

  protected canSubmitAssign(): boolean {
    return (
      !this.assignSubmitting() &&
      this.assignBrandId().trim().length > 0 &&
      this.assignLocationId().trim().length > 0 &&
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
    try {
      await this.tenantsApi.assignLegalEntity(this.tenantId, entityId, {
        brandId: this.assignBrandId().trim(),
        locationId: this.assignLocationId().trim(),
        effectiveFrom: this.assignEffectiveFrom().trim(),
      });
      this.assigningEntityId.set(null);
      this.assignBrandId.set('');
      this.assignLocationId.set('');
      this.assignEffectiveFrom.set('');
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.assignSubmitting.set(false);
    }
  }
}
