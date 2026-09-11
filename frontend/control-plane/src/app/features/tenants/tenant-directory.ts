import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { SessionContextService } from '../../core/auth/session-context.service';
import {
  CreateTenantRequest,
  CustomerIdentityMode,
  TenantHealthView,
  TenantPlanView,
  TenantSummaryView,
  TenantsApi,
} from './tenants-api';

/**
 * IA 2.1 Tenant directory -- every tenant, and the bootstrap creation flow
 * (ADR 0055 phase 5's own proving run: `POST /control-plane/tenants` is the
 * first call of the maker-checker journey this wave's exit criterion names).
 *
 * Each row shows where the tenant trades and what kind of business it is,
 * the plan it is on, and its open problems: dead letters, blocked receipts
 * and POS orders awaiting a decision, counted the way its issue queue lists
 * them. Health is that count, not a score; zero means nothing is waiting.
 */
@Component({
  selector: 'app-tenant-directory',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './tenant-directory.html',
  styleUrl: './tenant-directory.css',
})
export class TenantDirectory {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  private readonly tenantsApi = inject(TenantsApi);
  private readonly router = inject(Router);
  private readonly session = inject(SessionContextService);

  /** Plan and open problems by tenant; each read is optional and fails quietly into a dash. */
  protected readonly plans = signal<ReadonlyMap<string, TenantPlanView>>(new Map());
  protected readonly health = signal<ReadonlyMap<string, TenantHealthView>>(new Map());

  /**
   * Where each tenant's owner stands (ADR 0100), by id.
   *
   * Read from the address-free projection, not from the invitations screen's
   * overview: that one resolves every outstanding owner's address out of the
   * identity provider and records a reveal for them, which is the right price
   * for a screen that shows an address and the wrong one for a column that
   * shows a word. Nothing an operator may not read reaches this component --
   * and, because the projection has none to give, nothing reaches the browser
   * either.
   *
   * Optional in the way plan and health are: a caller without
   * `TENANT_ONBOARDING_MANAGE`, or a read that fails, leaves the map empty and
   * the column a dash, never an error across the page. A tenant the projection
   * does not mention is a tenant this screen knows nothing about, and says so.
   */
  protected readonly ownerStates = signal<ReadonlyMap<string, string>>(new Map());

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly tenants = signal<readonly TenantSummaryView[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loadingMore = signal(false);

  protected readonly creating = signal(false);
  protected readonly createSubmitting = signal(false);
  protected readonly createError = signal<string | null>(null);

  protected readonly slug = signal('');
  protected readonly legalName = signal('');
  protected readonly displayName = signal('');
  protected readonly defaultCurrency = signal('UZS');
  protected readonly defaultTimezone = signal('Asia/Tashkent');
  protected readonly customerIdentityMode = signal<CustomerIdentityMode>('TENANT_SHARED');

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const page = await this.tenantsApi.listTenants();
      this.tenants.set(page.items);
      this.nextCursor.set(page.nextCursor);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
    void this.loadColumns();
  }

  /**
   * The plan and health columns come from two platform-wide reads. Neither is
   * needed to list the tenants, so a failure (or a missing capability) leaves
   * a dash in its column rather than an error across the page.
   */
  private async loadColumns(): Promise<void> {
    if (this.session.has('COMMERCIAL_PLAN_READ')) {
      try {
        this.plans.set(
          new Map((await this.tenantsApi.tenantPlans()).map((plan) => [plan.tenantId, plan])),
        );
      } catch {
        this.plans.set(new Map());
      }
    }
    try {
      this.health.set(
        new Map((await this.tenantsApi.tenantHealth()).map((row) => [row.tenantId, row])),
      );
    } catch {
      this.health.set(new Map());
    }
    if (this.session.has('TENANT_ONBOARDING_MANAGE')) {
      try {
        this.ownerStates.set(
          new Map((await this.tenantsApi.ownerStates()).map((row) => [row.tenantId, row.state])),
        );
      } catch {
        this.ownerStates.set(new Map());
      }
    }
  }

  /**
   * What the owner column says about this tenant: `waiting` for an owner
   * somebody still has to chase, `ready` once they set up an account,
   * `notNeeded` when they already had one, `none` when no owner has been
   * linked or invited at all, and null when the answer is not known -- which
   * is not the same as any of them, and is the dash.
   */
  protected ownerColumn(tenantId: string): 'waiting' | 'ready' | 'notNeeded' | 'none' | null {
    const state = this.ownerStates().get(tenantId);
    if (state === undefined) {
      return null;
    }
    if (state === 'NO_OWNER') {
      return 'none';
    }
    if (state === 'ACCEPTED') {
      return 'ready';
    }
    return state === 'NOT_NEEDED' ? 'notNeeded' : 'waiting';
  }

  protected planOf(tenantId: string): string {
    const plan = this.plans().get(tenantId);
    return plan === undefined ? '—' : `${plan.planCode} v${plan.planVersionNumber}`;
  }

  protected problemsOf(tenantId: string): number {
    const row = this.health().get(tenantId);
    return row === undefined ? 0 : row.deadLetters + row.blockedReceipts + row.posOrdersAwaiting;
  }

  protected businessTypeKey(type: string): MessageKey {
    return `businessTypes.type.${type}` as MessageKey;
  }

  protected async loadMore(): Promise<void> {
    const cursor = this.nextCursor();
    if (cursor === null || this.loadingMore()) {
      return;
    }
    this.loadingMore.set(true);
    try {
      const page = await this.tenantsApi.listTenants(cursor);
      this.tenants.update((existing) => [...existing, ...page.items]);
      this.nextCursor.set(page.nextCursor);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loadingMore.set(false);
    }
  }

  protected openCreate(): void {
    this.creating.set(true);
    this.createError.set(null);
  }

  protected closeCreate(): void {
    this.creating.set(false);
  }

  protected canSubmitCreate(): boolean {
    return (
      !this.createSubmitting() &&
      this.slug().trim().length > 0 &&
      this.legalName().trim().length > 0 &&
      this.displayName().trim().length > 0 &&
      this.defaultCurrency().trim().length === 3 &&
      this.defaultTimezone().trim().length > 0
    );
  }

  protected async submitCreate(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canSubmitCreate()) {
      return;
    }
    this.createSubmitting.set(true);
    this.createError.set(null);
    const request: CreateTenantRequest = {
      slug: this.slug().trim(),
      legalName: this.legalName().trim(),
      displayName: this.displayName().trim(),
      defaultCurrency: this.defaultCurrency().trim().toUpperCase(),
      defaultTimezone: this.defaultTimezone().trim(),
      customerIdentityMode: this.customerIdentityMode(),
    };
    try {
      const tenant = await this.tenantsApi.createTenant(request);
      // Created tenants go straight to their own detail page: onboarding a
      // tenant is the very next thing a platform admin does after creating
      // one, and returning to a list they would just leave again serves
      // nobody.
      await this.router.navigate(['/tenants', tenant.id]);
    } catch (error) {
      this.createError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.createSubmitting.set(false);
    }
  }

  protected statusKey(status: TenantSummaryView['status']): MessageKey {
    return `tenants.status.${status}` as MessageKey;
  }
}
