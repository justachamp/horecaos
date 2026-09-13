import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { CurrentTenant } from '../../core/auth/current-tenant';
import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { AccessCheckApi, AccessCheckResponse } from './access-check-api';
import { BrandSummary, LocationSummary, ScopeDirectory, StaffApi } from './staff-api';
import { scopeLevelLabel } from './staff-role-labels';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';
type CheckState = 'idle' | 'checking' | 'answered' | 'error';
type AskableScope = 'TENANT' | 'BRAND' | 'LOCATION';

/**
 * Staff 9.5 Проверка доступа (`docs/operations-spec/staff-and-access.md` §6,
 * ADR 0109) — «почему Азиза не может отменить заказ?» in one place.
 *
 * Deliberately not a nav-rail entry: the spec calls this "three clicks away
 * … used a handful of times a year", reached from the link at the bottom of
 * Approvals (`approvals-page.html`'s own doc).
 *
 * **Кто and Что are raw codes, not sentences.** The spec's own layout asks
 * for a searchable person picker and "the action list, as plain sentences" —
 * neither exists yet: Staff 9.2 (the person record) has no name anywhere in
 * this codebase (every actor is a Keycloak subject id, gap map row 9.2), and
 * there is no capability→sentence label registry the way
 * `approval-action-labels.ts` gives approvals. `GrantController.accessCheck`
 * itself is unaffected either way — it takes a subject string and a
 * `Capability` enum value — so this screen is honest about showing what the
 * platform actually has today rather than inventing labels that would read
 * as more finished than the rest of Staff.
 */
@Component({
  selector: 'q-access-check-page',
  imports: [TPipe],
  templateUrl: './access-check-page.html',
  styleUrl: './access-check-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AccessCheckPage {
  private readonly tenant = inject(CurrentTenant);
  private readonly staffApi = inject(StaffApi);
  private readonly accessCheckApi = inject(AccessCheckApi);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<LoadState>('loading');
  protected readonly loadErrorText = signal<string | null>(null);

  protected readonly subjects = signal<readonly string[]>([]);
  protected readonly capabilities = signal<readonly string[]>([]);
  protected readonly directory = signal<ScopeDirectory>({ brands: [], locations: [] });

  protected readonly selectedSubject = signal('');
  protected readonly selectedCapability = signal('');
  protected readonly selectedScopeType = signal<AskableScope>('TENANT');
  protected readonly selectedBrandId = signal('');
  protected readonly selectedLocationId = signal('');

  protected readonly checkState = signal<CheckState>('idle');
  protected readonly checkErrorText = signal<string | null>(null);
  protected readonly answer = signal<AccessCheckResponse | null>(null);

  protected readonly locationsInSelectedBrand = computed<readonly LocationSummary[]>(() => {
    const brandId = this.selectedBrandId();
    return this.directory().locations.filter((location) => location.brandId === brandId);
  });

  protected readonly canSubmit = computed(() => {
    if (!this.selectedSubject() || !this.selectedCapability()) {
      return false;
    }
    if (this.selectedScopeType() === 'BRAND') {
      return this.selectedBrandId() !== '';
    }
    if (this.selectedScopeType() === 'LOCATION') {
      return this.selectedBrandId() !== '' && this.selectedLocationId() !== '';
    }
    return true;
  });

  constructor() {
    void this.load();
  }

  protected retry(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    this.state.set('loading');
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      this.state.set(this.tenant.denied() ? 'denied' : 'error');
      return;
    }
    try {
      const [grants, roles, directory] = await Promise.all([
        this.staffApi.listGrants(tenantId),
        this.staffApi.roles(tenantId),
        this.staffApi.scopeDirectory(tenantId),
      ]);
      this.subjects.set(
        [...new Set(grants.map((grant) => grant.principalSubject))].sort((a, b) =>
          a.localeCompare(b),
        ),
      );
      this.capabilities.set(
        [...new Set(roles.flatMap((role) => role.capabilities))].sort((a, b) => a.localeCompare(b)),
      );
      this.directory.set(directory);
      this.state.set('ready');
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.state.set('denied');
      } else {
        this.loadErrorText.set(this.describe(error));
        this.state.set('error');
      }
    }
  }

  protected setSubject(value: string): void {
    this.selectedSubject.set(value);
  }

  protected setCapability(value: string): void {
    this.selectedCapability.set(value);
  }

  protected setScopeType(value: string): void {
    this.selectedScopeType.set(value as AskableScope);
    this.selectedBrandId.set('');
    this.selectedLocationId.set('');
    this.answer.set(null);
    this.checkState.set('idle');
  }

  protected setBrand(value: string): void {
    this.selectedBrandId.set(value);
    this.selectedLocationId.set('');
  }

  protected setLocation(value: string): void {
    this.selectedLocationId.set(value);
  }

  protected brandName(brandId: string): string {
    return (
      this.directory().brands.find((brand: BrandSummary) => brand.id === brandId)?.displayName ??
      brandId
    );
  }

  protected locationName(locationId: string): string {
    return (
      this.directory().locations.find((location) => location.id === locationId)?.displayName ??
      locationId
    );
  }

  protected scopeLevelLabel(scopeType: AskableScope | 'PLATFORM'): string {
    return scopeLevelLabel(scopeType, (key) => this.i18n.t(key));
  }

  protected async submit(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (!tenantId || !this.canSubmit()) {
      return;
    }
    this.checkState.set('checking');
    this.checkErrorText.set(null);
    try {
      const result = await this.accessCheckApi.check(tenantId, {
        subject: this.selectedSubject(),
        capability: this.selectedCapability(),
        scopeType: this.selectedScopeType(),
        brandId: this.selectedBrandId() || undefined,
        locationId: this.selectedLocationId() || undefined,
      });
      this.answer.set(result);
      this.checkState.set('answered');
    } catch (error) {
      this.checkErrorText.set(this.describe(error));
      this.checkState.set('error');
    }
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
