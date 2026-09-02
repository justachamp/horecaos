import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { CAPABILITY_SENTENCES, sentenceLocale } from './capability-sentences';
import { GrantRequest, RoleDescriptor, ScopeDirectory, ScopeType } from './staff-api';
import { Scope, canGrantAt, tenantScope, brandScope, locationScope } from './scope-coverage';
import { ScopeGrant } from '../../core/auth/session-context';
import { roleDescription, roleLabel } from './staff-role-labels';

export interface ScopeOption {
  readonly brandId: string | null;
  readonly locationId: string | null;
  readonly label: string;
}

/**
 * Добавить/Изменить должность — staff-and-access.md §3.2/§4. One dialog for
 * both: "change" has no separate endpoint (only `POST .../grants`), so a row
 * offering "Изменить должность" opens this the same way §3's "Добавить
 * должность" does, pre-scoped to the same person. The old assignment, if any,
 * is left for the operator to remove separately on the person's own card
 * (§3's «Убрать») — granting and revoking are two different intents and two
 * different reasons, and folding them into one submit would either lose the
 * old reason or invent one.
 *
 * §0's corollary is enforced here as a *picker filter*, never a disabled
 * option or an error after the fact: a job is absent from "Должность" unless
 * {@link canGrantAt} says the signed-in operator could confer it somewhere,
 * and "Где" only ever lists the scopes where that specific job clears the
 * check.
 */
@Component({
  selector: 'q-staff-job-dialog',
  imports: [TPipe],
  templateUrl: './staff-job-dialog.html',
  styleUrl: './staff-job-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StaffJobDialog {
  readonly tenantId = input.required<string>();
  readonly principalSubject = input.required<string>();
  readonly roles = input.required<readonly RoleDescriptor[]>();
  readonly directory = input.required<ScopeDirectory>();
  readonly myScopes = input.required<readonly ScopeGrant[]>();
  readonly busy = input(false);
  readonly serverError = input<string | null>(null);

  readonly submitted = output<GrantRequest>();
  readonly dismiss = output<void>();

  protected readonly i18n = inject(I18n);

  protected readonly selectedRoleCode = signal<string | null>(null);
  protected readonly selectedBrandId = signal<string | null>(null);
  protected readonly selectedLocationId = signal<string | null>(null);
  protected readonly reason = signal('');
  protected readonly validUntil = signal('');
  private readonly touched = signal(false);

  /** Only jobs the operator could confer somewhere — §0's corollary, enforced as absence. */
  protected readonly grantableRoles = computed(() =>
    this.roles().filter((role) => this.scopeOptionsForRole(role).length > 0),
  );

  protected readonly selectedRole = computed<RoleDescriptor | null>(
    () => this.grantableRoles().find((role) => role.code === this.selectedRoleCode()) ?? null,
  );

  protected readonly scopeOptions = computed<readonly ScopeOption[]>(() => {
    const role = this.selectedRole();
    return role ? this.scopeOptionsForRole(role) : [];
  });

  /** Pre-selected and read-only when only one option exists (staff-and-access.md §4's «Где»). */
  protected readonly scopeIsFixed = computed(() => this.scopeOptions().length === 1);

  protected readonly canSubmit = computed(() => {
    const role = this.selectedRole();
    if (!role) {
      return false;
    }
    if (role.scopeType === 'BRAND' && this.selectedBrandId() === null) {
      return false;
    }
    if (role.scopeType === 'LOCATION' && this.selectedLocationId() === null) {
      return false;
    }
    return this.reason().trim() !== '';
  });

  protected readonly reasonMissing = computed(() => this.touched() && this.reason().trim() === '');

  /** "Сможет" — the selected job's own capabilities, as sentences. */
  protected readonly grantedSentences = computed(() => {
    const role = this.selectedRole();
    if (!role) {
      return [];
    }
    const locale = sentenceLocale(this.i18n.locale());
    return [...role.capabilities]
      .map((code) => CAPABILITY_SENTENCES[code]?.[locale] ?? code)
      .sort();
  });

  /**
   * "Не сможет" — the complement against every other tenant-visible job,
   * capped at five (§4). Ordering is simplified: the spec wants it ranked by
   * how often people ask (money, then menu, then other branches); this ranks
   * alphabetically by capability code instead, which is honest about not
   * having that usage data yet rather than faking a priority.
   */
  protected readonly cannotSentences = computed(() => {
    const role = this.selectedRole();
    if (!role) {
      return [];
    }
    const mine = new Set(role.capabilities);
    const others = new Set<string>();
    for (const candidate of this.roles()) {
      if (candidate.code === role.code) {
        continue;
      }
      for (const capability of candidate.capabilities) {
        if (!mine.has(capability)) {
          others.add(capability);
        }
      }
    }
    const locale = sentenceLocale(this.i18n.locale());
    return Array.from(others)
      .sort()
      .slice(0, 5)
      .map((code) => CAPABILITY_SENTENCES[code]?.[locale] ?? code);
  });

  protected selectRole(code: string): void {
    this.selectedRoleCode.set(code || null);
    this.selectedBrandId.set(null);
    this.selectedLocationId.set(null);
    const options = this.scopeOptions();
    if (options.length === 1) {
      this.selectedBrandId.set(options[0].brandId);
      this.selectedLocationId.set(options[0].locationId);
    }
  }

  protected selectScope(index: string): void {
    const option = this.scopeOptions()[Number(index)];
    if (option) {
      this.selectedBrandId.set(option.brandId);
      this.selectedLocationId.set(option.locationId);
    }
  }

  protected setReason(value: string): void {
    this.reason.set(value);
  }

  protected setValidUntil(value: string): void {
    this.validUntil.set(value);
  }

  protected submit(): void {
    this.touched.set(true);
    const role = this.selectedRole();
    const reason = this.reason().trim();
    if (!role || !this.canSubmit() || !reason) {
      return;
    }
    this.submitted.emit({
      principalSubject: this.principalSubject(),
      roleCode: role.code,
      brandId: this.selectedBrandId() ?? undefined,
      locationId: this.selectedLocationId() ?? undefined,
      reason,
      validUntil: this.validUntil() ? new Date(this.validUntil()).toISOString() : undefined,
    });
  }

  protected close(): void {
    this.dismiss.emit();
  }

  protected roleLabel(code: string): string {
    return roleLabel(code, (key) => this.i18n.t(key));
  }

  protected roleDescription(code: string): string | null {
    return roleDescription(code, (key) => this.i18n.t(key));
  }

  private scopeOptionsForRole(role: RoleDescriptor): readonly ScopeOption[] {
    const tenantId = this.tenantId();
    const mine = this.myScopes();
    const scopeType: ScopeType = role.scopeType;

    if (scopeType === 'PLATFORM') {
      return [];
    }
    if (scopeType === 'TENANT') {
      const scope: Scope = tenantScope(tenantId);
      return canGrantAt(mine, scope, role.capabilities)
        ? [{ brandId: null, locationId: null, label: this.i18n.t('staff.scope.company') }]
        : [];
    }
    if (scopeType === 'BRAND') {
      return this.directory()
        .brands.filter((brand) =>
          canGrantAt(mine, brandScope(tenantId, brand.id), role.capabilities),
        )
        .map((brand) => ({ brandId: brand.id, locationId: null, label: brand.displayName }));
    }
    return this.directory()
      .locations.filter((location) =>
        canGrantAt(mine, locationScope(tenantId, location.brandId, location.id), role.capabilities),
      )
      .map((location) => ({
        brandId: location.brandId,
        locationId: location.id,
        label: location.displayName,
      }));
  }
}
