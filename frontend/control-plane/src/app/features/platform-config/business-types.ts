import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { BusinessTypeView, ResidencyApi, TenantProfileView } from '../compliance/residency-api';

/**
 * IA 8.2 Business types -- the kinds of business the platform serves, the
 * shape each usually takes, and which tenants are which.
 *
 * A type is recorded and shown; it enables and disables nothing by itself.
 * What a tenant may do stays with its capabilities and entitlements, and
 * onboarding applies the default template to every type.
 */
@Component({
  selector: 'app-business-types',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './business-types.html',
  styleUrls: ['../compliance/residency-hosting.css', './business-types.css'],
})
export class BusinessTypes {
  protected readonly i18n = inject(I18nService);
  protected readonly session = inject(SessionContextService);
  private readonly api = inject(ResidencyApi);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly types = signal<readonly BusinessTypeView[]>([]);
  protected readonly tenants = signal<readonly TenantProfileView[]>([]);
  protected readonly filter = signal('');

  protected readonly editing = signal<string | null>(null);
  protected readonly nextType = signal('');
  protected readonly reason = signal('');
  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionMessage = signal<string | null>(null);

  protected readonly shown = computed(() =>
    this.filter() === '' ? this.tenants() : this.tenants().filter((tenant) => tenant.businessType === this.filter()),
  );

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const [types, residency] = await Promise.all([this.api.businessTypes(), this.api.residency()]);
      this.types.set(types);
      this.tenants.set(residency.tenants);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected typeKey(code: string): MessageKey {
    return `businessTypes.type.${code}` as MessageKey;
  }

  protected handoverKey(code: string): MessageKey {
    return `businessTypes.handover.${code}` as MessageKey;
  }

  protected open(tenant: TenantProfileView): void {
    this.editing.set(this.editing() === tenant.tenantId ? null : tenant.tenantId);
    this.nextType.set(tenant.businessType);
    this.reason.set('');
    this.actionError.set(null);
  }

  protected async save(tenant: TenantProfileView): Promise<void> {
    const reason = this.reason().trim();
    if (this.busy() || reason.length === 0 || this.nextType() === tenant.businessType) {
      return;
    }
    this.busy.set(true);
    this.actionError.set(null);
    this.actionMessage.set(null);
    try {
      await this.api.setBusinessType(tenant.tenantId, this.nextType(), reason);
      this.editing.set(null);
      this.actionMessage.set(
        this.i18n.t('businessTypes.saved', { tenant: tenant.displayName, type: this.i18n.t(this.typeKey(this.nextType())) }),
      );
      await this.load();
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.busy.set(false);
    }
  }
}
