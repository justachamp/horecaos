import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
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
  templateUrl: './audit-log.html',
  styleUrl: './audit-log.css',
})
export class AuditLog {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  private readonly api = inject(AccessApi);

  protected readonly tenantId = signal('');
  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly events = signal<readonly AuditEventView[]>([]);
  protected readonly searched = signal(false);

  protected async load(event: Event): Promise<void> {
    event.preventDefault();
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
