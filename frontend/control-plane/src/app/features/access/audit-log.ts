import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { TenantDirectory } from '../../shared/tenant-directory';
import { TenantPicker } from '../../shared/tenant-picker';
import { AccessApi, AuditEventView } from './access-api';

/**
 * IA 7.5 Audit log -- platform actions, non-human actors first-class
 * (ADR 0027).
 *
 * `AuditController` reads one tenant at a time (`GET
 * .../tenants/{tenantId}/audit-events`); there is no platform-wide, every-tenant
 * query, so -- like 6.1 and 5.3 -- this is the tenant-picker pattern rather
 * than a single always-on board. Impersonation-session records, named by the
 * IA row, do not exist because impersonation itself is not built (IA 2.8).
 */
@Component({
  selector: 'app-audit-log',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TenantPicker],
  templateUrl: './audit-log.html',
  styleUrl: './audit-log.css',
})
export class AuditLog {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  private readonly api = inject(AccessApi);

  private readonly directory = inject(TenantDirectory);
  private readonly tenantRoute = inject(ActivatedRoute);
  /** From a `?tenantId=` link first, else the tenant chosen last on any screen. */
  protected readonly tenantId = signal(
    this.tenantRoute.snapshot.queryParamMap.get('tenantId') ?? this.directory.selected(),
  );
  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly events = signal<readonly AuditEventView[]>([]);
  protected readonly searched = signal(false);

  constructor() {
    if (this.tenantId().length > 0) {
      void this.load();
    }
  }

  /** A tenant chosen in the picker: shown at once, nothing to press. */
  protected chooseTenant(tenantId: string): void {
    this.tenantId.set(tenantId);
    if (tenantId.length > 0) {
      void this.load();
    }
  }

  protected async load(event?: Event): Promise<void> {
    event?.preventDefault();
    const tenantId = this.tenantId().trim();
    if (tenantId.length === 0) {
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    this.searched.set(true);
    try {
      const page = await this.api.auditEvents(tenantId);
      this.events.set(page.items);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }
}
