import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { AccessApi, PendingApprovalResponse, PlatformGrantView } from './access-api';

/**
 * IA 7.1 Staff & roles -- HorecaOS employees, roles, tenant scoping, plus
 * (see `access-api.ts`) the checker half of the maker-checker journey this
 * wave's exit criterion needs: approving a pending request from the same
 * screen that grants roles.
 */
@Component({
  selector: 'app-staff',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './staff.html',
  styleUrl: './staff.css',
})
export class Staff {
  protected readonly i18n = inject(I18nService);
  private readonly api = inject(AccessApi);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly platformGrants = signal<readonly PlatformGrantView[]>([]);

  protected readonly principalSubject = signal('');
  protected readonly roleCode = signal('');
  protected readonly grantReason = signal('');
  protected readonly granting = signal(false);
  protected readonly grantMessage = signal<string | null>(null);

  protected readonly tenantId = signal('');
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

  protected canSubmitTenantGrant(): boolean {
    return (
      !this.tenantGranting() &&
      this.tenantId().trim().length > 0 &&
      this.tenantPrincipalSubject().trim().length > 0 &&
      this.tenantRoleCode().trim().length > 0 &&
      this.tenantGrantReason().trim().length > 0
    );
  }

  protected async submitTenantGrant(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canSubmitTenantGrant()) {
      return;
    }
    this.tenantGranting.set(true);
    this.tenantGrantMessage.set(null);
    try {
      await this.api.grantTenant(
        this.tenantId().trim(),
        this.tenantPrincipalSubject().trim(),
        this.tenantRoleCode().trim(),
        this.tenantGrantReason().trim(),
      );
      this.tenantGrantMessage.set(this.i18n.t('staff.tenantGrant.success'));
      this.tenantPrincipalSubject.set('');
      this.tenantRoleCode.set('');
      this.tenantGrantReason.set('');
    } catch (error) {
      this.tenantGrantMessage.set(this.i18n.describe(error as ApiError));
    } finally {
      this.tenantGranting.set(false);
    }
  }

  protected async loadPending(event: Event): Promise<void> {
    event.preventDefault();
    const tenantId = this.approvalsTenantId().trim();
    if (tenantId.length === 0) {
      return;
    }
    this.pendingLoading.set(true);
    this.pendingError.set(null);
    try {
      const page = await this.api.pendingApprovals(tenantId);
      this.pending.set(page.items);
    } catch (error) {
      this.pendingError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.pendingLoading.set(false);
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
