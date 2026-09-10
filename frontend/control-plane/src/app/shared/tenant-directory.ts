import { Injectable, inject, signal } from '@angular/core';

import { TenantSummaryView, TenantsApi } from '../features/tenants/tenants-api';

const REMEMBERED = 'horecaos.console.tenant';

/**
 * The tenants a platform operator chooses between, read once per session, and
 * the one they last chose.
 *
 * Cross-tenant screens -- the audit log, entitlements, usage, fiscalization,
 * the issue queue, migration -- all start from "which tenant". They each
 * asked for its id typed by hand, which nobody has to hand, and forgot it on
 * the next screen. The choice is kept for the browser session only: it is a
 * convenience, not a setting, and a new session starts from the directory.
 */
@Injectable({ providedIn: 'root' })
export class TenantDirectory {
  private readonly tenantsApi = inject(TenantsApi);
  private pending: Promise<void> | null = null;

  readonly tenants = signal<readonly TenantSummaryView[]>([]);
  readonly loaded = signal(false);
  readonly failed = signal(false);
  readonly selected = signal<string>(readRemembered());

  /** Reads the directory the first time any screen asks; later calls share that read. */
  load(): Promise<void> {
    this.pending ??= this.tenantsApi
      .listTenants(null, 200)
      .then((page) => {
        this.tenants.set(page.items);
        this.loaded.set(true);
      })
      .catch(() => {
        this.failed.set(true);
        this.pending = null;
      });
    return this.pending;
  }

  choose(tenantId: string): void {
    this.selected.set(tenantId);
    try {
      if (tenantId === '') {
        sessionStorage.removeItem(REMEMBERED);
      } else {
        sessionStorage.setItem(REMEMBERED, tenantId);
      }
    } catch {
      // Storage can be unavailable (a private window, a policy); the choice
      // then lasts until the page reloads, which is all it was for.
    }
  }

  nameOf(tenantId: string): string {
    return this.tenants().find((tenant) => tenant.id === tenantId)?.displayName ?? tenantId;
  }
}

function readRemembered(): string {
  try {
    return sessionStorage.getItem(REMEMBERED) ?? '';
  } catch {
    return '';
  }
}
