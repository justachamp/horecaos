import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { TenantDirectory } from '../../shared/tenant-directory';
import { TenantPicker } from '../../shared/tenant-picker';
import { BrandView, LocationView, TenantsApi } from '../tenants/tenants-api';
import {
  AccessApi,
  PendingApprovalResponse,
  PlatformGrantView,
  TenantGrantView,
  TenantRoleDescriptor,
} from './access-api';

/**
 * IA 7.1 Staff & roles -- HorecaOS employees, roles, tenant scoping, plus
 * (see `access-api.ts`) the checker half of the maker-checker journey this
 * wave's exit criterion needs: approving a pending request from the same
 * screen that grants roles.
 *
 * The tenant half is driven by one tenant chosen from the directory: its
 * grants (each revocable, with a reason), a role picked from the jobs that
 * tenant can be given, the brand or location a brand- or location-level job
 * applies to, and what is waiting for a second signature there. It used to
 * ask for the tenant's id, the role's code and nothing else, typed by hand,
 * and never showed the grants that already existed.
 */
@Component({
  selector: 'app-staff',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TenantPicker],
  templateUrl: './staff.html',
  styleUrl: './staff.css',
})
export class Staff {
  protected readonly i18n = inject(I18nService);
  private readonly api = inject(AccessApi);
  private readonly tenantsApi = inject(TenantsApi);
  private readonly directory = inject(TenantDirectory);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly platformGrants = signal<readonly PlatformGrantView[]>([]);

  protected readonly principalSubject = signal('');
  protected readonly roleCode = signal('');
  protected readonly grantReason = signal('');
  protected readonly granting = signal(false);
  protected readonly grantMessage = signal<string | null>(null);

  protected readonly tenantId = signal('');
  protected readonly tenantGrants = signal<readonly TenantGrantView[]>([]);
  protected readonly tenantRoles = signal<readonly TenantRoleDescriptor[]>([]);
  protected readonly tenantBrands = signal<readonly BrandView[]>([]);
  protected readonly tenantLocations = signal<readonly LocationView[]>([]);
  protected readonly tenantLoading = signal(false);
  protected readonly tenantError = signal<string | null>(null);
  protected readonly grantBrandId = signal('');
  protected readonly grantLocationId = signal('');
  protected readonly revokingId = signal<string | null>(null);
  protected readonly revokeReason = signal('');
  protected readonly revokeMessage = signal<string | null>(null);
  protected readonly tenantPrincipalSubject = signal('');
  protected readonly tenantRoleCode = signal('');
  protected readonly tenantGrantReason = signal('');
  protected readonly tenantGranting = signal(false);
  protected readonly tenantGrantMessage = signal<string | null>(null);

  protected readonly approvalsTenantId = signal('');
  protected readonly pending = signal<readonly PendingApprovalResponse[]>([]);
  protected readonly pendingLoading = signal(false);
  protected readonly pendingError = signal<string | null>(null);
  protected readonly decidingId = signal<string | null>(null);
  protected readonly decideReason = signal('');

  constructor() {
    void this.load();
    const remembered = this.directory.selected();
    if (remembered !== '') {
      void this.chooseTenant(remembered);
    }
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.platformGrants.set(await this.api.listPlatformGrants());
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected canSubmitGrant(): boolean {
    return (
      !this.granting() &&
      this.principalSubject().trim().length > 0 &&
      this.roleCode().trim().length > 0 &&
      this.grantReason().trim().length > 0
    );
  }

  protected async submitGrant(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canSubmitGrant()) {
      return;
    }
    this.granting.set(true);
    this.grantMessage.set(null);
    try {
      const outcome = await this.api.grantPlatform(
        this.principalSubject().trim(),
        this.roleCode().trim(),
        this.grantReason().trim(),
      );
      this.grantMessage.set(
        outcome.outcome === 'AWAITING_APPROVAL'
          ? this.i18n.t('staff.grant.outcome.awaitingApproval')
          : this.i18n.t('staff.grant.outcome.granted'),
      );
      this.principalSubject.set('');
      this.roleCode.set('');
      this.grantReason.set('');
      await this.load();
    } catch (error) {
      this.grantMessage.set(this.i18n.describe(error as ApiError));
    } finally {
      this.granting.set(false);
    }
  }

  protected async revoke(grantId: string): Promise<void> {
    this.grantMessage.set(null);
    try {
      await this.api.revokePlatformGrant(grantId, this.i18n.t('staff.revoke.reason'));
      await this.load();
    } catch (error) {
      this.grantMessage.set(this.i18n.describe(error as ApiError));
    }
  }

  /** Everything the tenant half shows, for the tenant just chosen. */
  protected async chooseTenant(tenantId: string): Promise<void> {
    this.tenantId.set(tenantId);
    this.approvalsTenantId.set(tenantId);
    this.tenantRoleCode.set('');
    this.grantBrandId.set('');
    this.grantLocationId.set('');
    this.tenantGrantMessage.set(null);
    this.revokeMessage.set(null);
    this.revokingId.set(null);
    if (tenantId === '') {
      this.tenantGrants.set([]);
      this.pending.set([]);
      return;
    }
    this.tenantLoading.set(true);
    this.tenantError.set(null);
    try {
      const [grants, roles, brands, pending] = await Promise.all([
        this.api.listTenantGrants(tenantId),
        this.api.listTenantRoles(tenantId),
        this.tenantsApi.getBrands(tenantId),
        this.api.pendingApprovals(tenantId),
      ]);
      this.tenantGrants.set(grants);
      this.tenantRoles.set(roles);
      this.tenantBrands.set(brands);
      this.pending.set(pending.items);
      const perBrand = await Promise.all(brands.map((brand) => this.tenantsApi.getLocations(tenantId, brand.id)));
      this.tenantLocations.set(perBrand.flat());
    } catch (error) {
      this.tenantError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.tenantLoading.set(false);
    }
  }

  /** The scope the chosen job is granted at: the whole tenant, one brand, or one location. */
  protected chosenRoleScope(): string | null {
    return this.tenantRoles().find((role) => role.code === this.tenantRoleCode())?.scopeType ?? null;
  }

  protected locationsOfChosenBrand(): readonly LocationView[] {
    return this.tenantLocations().filter((location) => location.brandId === this.grantBrandId());
  }

  protected scopeKey(scopeType: string): MessageKey {
    return `staff.scope.${scopeType}` as MessageKey;
  }

  /** A brand or location grant's place, by name; the id only when it no longer resolves. */
  protected scopeName(grant: TenantGrantView): string {
    if (grant.scopeType === 'BRAND') {
      return this.tenantBrands().find((brand) => brand.id === grant.scopeId)?.displayName ?? grant.scopeId;
    }
    if (grant.scopeType === 'LOCATION') {
      return this.tenantLocations().find((location) => location.id === grant.scopeId)?.displayName ?? grant.scopeId;
    }
    return '';
  }

  protected canSubmitTenantGrant(): boolean {
    const scope = this.chosenRoleScope();
    return (
      !this.tenantGranting() &&
      this.tenantId().trim().length > 0 &&
      this.tenantPrincipalSubject().trim().length > 0 &&
      this.tenantRoleCode().trim().length > 0 &&
      this.tenantGrantReason().trim().length > 0 &&
      (scope !== 'BRAND' || this.grantBrandId() !== '') &&
      (scope !== 'LOCATION' || (this.grantBrandId() !== '' && this.grantLocationId() !== ''))
    );
  }

  protected askRevoke(grantId: string): void {
    this.revokingId.set(grantId);
    this.revokeReason.set('');
    this.revokeMessage.set(null);
  }

  protected async confirmRevoke(): Promise<void> {
    const grantId = this.revokingId();
    const reason = this.revokeReason().trim();
    if (grantId === null || reason.length === 0) {
      return;
    }
    try {
      await this.api.revokeTenantGrant(this.tenantId(), grantId, reason);
      this.tenantGrants.update((grants) => grants.filter((grant) => grant.id !== grantId));
      this.revokingId.set(null);
      this.revokeMessage.set(this.i18n.t('staff.revokeTenant.done'));
    } catch (error) {
      this.revokeMessage.set(this.i18n.describe(error as ApiError));
    }
  }

  protected async submitTenantGrant(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canSubmitTenantGrant()) {
      return;
    }
    this.tenantGranting.set(true);
    this.tenantGrantMessage.set(null);
    try {
      const scope = this.chosenRoleScope();
      await this.api.grantTenant(
        this.tenantId().trim(),
        this.tenantPrincipalSubject().trim(),
        this.tenantRoleCode().trim(),
        this.tenantGrantReason().trim(),
        scope === 'BRAND' || scope === 'LOCATION' ? this.grantBrandId() : undefined,
        scope === 'LOCATION' ? this.grantLocationId() : undefined,
      );
      this.tenantGrantMessage.set(this.i18n.t('staff.tenantGrant.success'));
      this.tenantPrincipalSubject.set('');
      this.tenantRoleCode.set('');
      this.tenantGrantReason.set('');
      this.grantBrandId.set('');
      this.grantLocationId.set('');
      this.tenantGrants.set(await this.api.listTenantGrants(this.tenantId()));
    } catch (error) {
      this.tenantGrantMessage.set(this.i18n.describe(error as ApiError));
    } finally {
      this.tenantGranting.set(false);
    }
  }

  protected async decide(requestId: string, decision: 'APPROVE' | 'DECLINE'): Promise<void> {
    const tenantId = this.approvalsTenantId().trim();
    if (tenantId.length === 0 || this.decideReason().trim().length === 0) {
      return;
    }
    this.decidingId.set(requestId);
    this.pendingError.set(null);
    try {
      await this.api.decide(tenantId, requestId, decision, this.decideReason().trim());
      this.pending.update((rows) => rows.filter((row) => row.id !== requestId));
      this.decideReason.set('');
    } catch (error) {
      this.pendingError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.decidingId.set(null);
    }
  }
}
