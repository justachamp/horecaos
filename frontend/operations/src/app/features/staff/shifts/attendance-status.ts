import { Injectable, inject, signal } from '@angular/core';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { StaffShiftsApi } from './staff-shifts-api';

/**
 * Whether this company records staff hours (ADR 0156: `workforce.attendance.enabled`).
 *
 * The shift screens, the person card's «Смены» tab and the clock card on «Моя работа» exist only when
 * a tenant has switched attendance on, having read the notice. A tenant that has not is told nothing
 * is missing: those places are absent, not empty. Read once per session; one that cannot be read is off
 * -- the safe direction, since the platform answers every workforce route as absent while it is off.
 */
@Injectable({ providedIn: 'root' })
export class AttendanceStatus {
  private readonly api = inject(StaffShiftsApi);
  private readonly tenant = inject(CurrentTenant);

  private readonly state = signal<boolean | null>(null);
  private loading: Promise<void> | null = null;

  /** True only once it has been read and is on; false before and when it is off or unreadable. */
  readonly enabled = this.state.asReadonly();

  ensureLoaded(): Promise<void> {
    if (this.loading === null) {
      this.loading = this.load();
    }
    return this.loading;
  }

  /** After the tenant switched it on in this session. */
  markEnabled(): void {
    this.state.set(true);
  }

  private async load(): Promise<void> {
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      this.state.set(false);
      return;
    }
    try {
      this.state.set(await this.api.enabled(tenantId));
    } catch {
      this.state.set(false);
    }
  }
}
