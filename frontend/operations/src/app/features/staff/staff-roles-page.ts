import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';

import { CurrentTenant } from '../../core/auth/current-tenant';
import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { CAPABILITY_SENTENCES, capabilityAreaName, sentenceLocale } from './capability-sentences';
import { GrantView, RoleDescriptor, ScopeDirectory, StaffApi } from './staff-api';
import { roleDescription, roleLabel, scopeLevelLabel } from './staff-role-labels';

interface RoleRow {
  readonly role: RoleDescriptor;
  readonly holderCount: number;
  readonly areas: readonly string[];
}

interface Holder {
  readonly principalSubject: string;
  readonly scopeText: string;
  readonly since: string;
}

/**
 * Должности — the job library (operations IA §9.1, staff-and-access.md §5).
 *
 * A list with an expand-in-place detail rather than a routed `:roleCode`
 * pane, unlike Люди/Карточка. The spec's own wording ("a list of the eight
 * tenant-visible jobs, each opening a read-only detail") does not require a
 * deep-linkable route the way Карточка explicitly does — nothing links here
 * from outside this screen the way the activity log links to a person — so a
 * second docked pane would be structure with no consumer. §5's «Назначить
 * кому-то» action is left off for the same budget reason it would need a
 * staff picker this backend cannot support beyond "someone already in the
 * list" (§11.1): every holder row here already links to that exact person's
 * own Добавить-должность action instead.
 */
@Component({
  selector: 'q-staff-roles-page',
  imports: [TPipe],
  templateUrl: './staff-roles-page.html',
  styleUrl: './staff-roles-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StaffRolesPage {
  private readonly api = inject(StaffApi);
  private readonly tenant = inject(CurrentTenant);
  private readonly router = inject(Router);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);

  private readonly roles = signal<readonly RoleDescriptor[]>([]);
  private readonly grants = signal<readonly GrantView[]>([]);
  private readonly directory = signal<ScopeDirectory>({ brands: [], locations: [] });
  protected readonly expandedCode = signal<string | null>(null);

  protected readonly rows = computed<readonly RoleRow[]>(() => {
    const locale = sentenceLocale(this.i18n.locale());
    const grants = this.grants().filter((g) => g.status === 'ACTIVE');
    return this.roles()
      .map((role) => ({
        role,
        holderCount: grants.filter((g) => g.roleCode === role.code).length,
        areas: Array.from(
          new Set(role.capabilities.map((code) => capabilityAreaName(code, locale))),
        ).sort(),
      }))
      .sort((a, b) => {
        const levelDelta =
          SCOPE_LEVEL_ORDER[a.role.scopeType] - SCOPE_LEVEL_ORDER[b.role.scopeType];
        return levelDelta !== 0 ? levelDelta : b.holderCount - a.holderCount;
      });
  });

  constructor() {
    void this.load();
  }

  protected roleLabel(code: string): string {
    return roleLabel(code, (key) => this.i18n.t(key));
  }

  protected roleDescription(code: string): string | null {
    return roleDescription(code, (key) => this.i18n.t(key));
  }

  protected scopeLevelLabel(scopeType: RoleDescriptor['scopeType']): string {
    return scopeLevelLabel(scopeType, (key) => this.i18n.t(key));
  }

  protected toggle(code: string): void {
    this.expandedCode.set(this.expandedCode() === code ? null : code);
  }

  protected canDoSentences(role: RoleDescriptor): readonly string[] {
    const locale = sentenceLocale(this.i18n.locale());
    return [...role.capabilities]
      .map((code) => CAPABILITY_SENTENCES[code]?.[locale] ?? code)
      .sort();
  }

  /** «Чего нельзя», uncapped here unlike the invite preview's five-line version (§5). */
  protected cannotSentences(role: RoleDescriptor): readonly string[] {
    const mine = new Set(role.capabilities);
    const others = new Set<string>();
    for (const candidate of this.roles()) {
      if (candidate.code === role.code) {
        continue;
      }
      for (const code of candidate.capabilities) {
        if (!mine.has(code)) {
          others.add(code);
        }
      }
    }
    const locale = sentenceLocale(this.i18n.locale());
    return Array.from(others)
      .map((code) => CAPABILITY_SENTENCES[code]?.[locale] ?? code)
      .sort();
  }

  protected holdersOf(roleCode: string): readonly Holder[] {
    return this.grants()
      .filter((grant) => grant.status === 'ACTIVE' && grant.roleCode === roleCode)
      .map((grant) => ({
        principalSubject: grant.principalSubject,
        scopeText: this.scopeText(grant),
        since: grant.validFrom.slice(0, 10),
      }));
  }

  protected openPerson(subject: string): void {
    void this.router.navigate(['/staff', subject]);
  }

  private scopeText(grant: GrantView): string {
    if (grant.scopeType === 'TENANT' || grant.scopeType === 'PLATFORM') {
      return this.i18n.t('staff.scope.company');
    }
    const dir = this.directory();
    const found =
      grant.scopeType === 'BRAND'
        ? dir.brands.find((b) => b.id === grant.scopeId)?.displayName
        : dir.locations.find((l) => l.id === grant.scopeId)?.displayName;
    return found ?? this.i18n.t('staff.scope.unknown');
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      this.denied.set(this.tenant.denied());
      this.loading.set(false);
      return;
    }
    try {
      const [roles, grants, directory] = await Promise.all([
        this.api.roles(tenantId),
        this.api.listGrants(tenantId, false),
        this.api.scopeDirectory(tenantId),
      ]);
      this.roles.set(roles);
      this.grants.set(grants);
      this.directory.set(directory);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else if (error instanceof ApiError) {
        this.loadError.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
      } else {
        throw error;
      }
    } finally {
      this.loading.set(false);
    }
  }
}

const SCOPE_LEVEL_ORDER: Record<RoleDescriptor['scopeType'], number> = {
  PLATFORM: -1,
  TENANT: 0,
  BRAND: 1,
  LOCATION: 2,
};
