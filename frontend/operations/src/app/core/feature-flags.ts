import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from './api/api-client';
import { CurrentTenant } from './auth/current-tenant';

/**
 * Which feature flags are on for the signed-in tenant (ADR 0082).
 *
 * Read once per session. A flag that cannot be read is off: an operator whose
 * grant does not reach the tenant-wide read, or a network failure, sees the
 * app without the staged feature rather than a half-rendered one.
 */
@Injectable({ providedIn: 'root' })
export class FeatureFlags {
  private readonly api = inject(ApiClient);
  private readonly tenant = inject(CurrentTenant);
  private readonly flags = signal<Readonly<Record<string, boolean>>>({});
  private loadPromise: Promise<void> | null = null;

  ensureLoaded(): Promise<void> {
    if (this.loadPromise === null) {
      this.loadPromise = this.load();
    }
    return this.loadPromise;
  }

  isOn(code: string): boolean {
    return this.flags()[code] === true;
  }

  private async load(): Promise<void> {
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (tenantId === null) {
      return;
    }
    try {
      const result = await firstValueFrom(
        this.api.get<Record<string, boolean>>(`/api/v1/operations/tenants/${tenantId}/feature-flags`),
      );
      this.flags.set(result.value ?? {});
    } catch {
      this.flags.set({});
    }
  }
}
