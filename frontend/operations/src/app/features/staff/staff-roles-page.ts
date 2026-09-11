import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';

import { CurrentTenant } from '../../core/auth/current-tenant';
import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { AccessRefusal, accessRefusal, describeApiError } from '../orders/order-errors';
import { DeniedState } from '../../shared/ui/denied-state';
import { LockedState } from '../../shared/ui/locked-state';
import {
  CAPABILITY_SENTENCES,
  SentenceLocale,
  capabilityAreaName,
  sentenceLocale,
} from './capability-sentences';
import { GrantView, RoleDescriptor, ScopeDirectory, StaffApi } from './staff-api';
import { roleDescription, roleLabel, scopeLevelLabel } from './staff-role-labels';

interface RoleRow {
  readonly role: RoleDescriptor;
  readonly holderCount: number;
  readonly areas: readonly string[];
}

/**
 * Whether `role` has any business matching `needle` — capability search
 * (operations IA §9.1, "9.1 itself contributes... capability search").
 * Frontend-only over `capability-sentences.ts`, never `CapabilityRegistryController`,
 * which is `PLATFORM_ADMIN`-gated and this screen's own operator cannot
 * reach. Matches a capability's plain sentence in the operator's own
 * language, or its raw wire code — an admin who already knows the code from
 * `q-denied-state` should be able to paste it straight in.
 */
function matchesQuery(role: RoleDescriptor, needle: string, locale: SentenceLocale): boolean {
  if (needle === '') {
    return true;
  }
  return role.capabilities.some((code) => {
    if (code.toLowerCase().includes(needle)) {
      return true;
    }
    const sentence = CAPABILITY_SENTENCES[code]?.[locale];
    return sentence !== undefined && sentence.toLowerCase().includes(needle);
  });
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
  imports: [TPipe, DeniedState, LockedState],
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
  /** No active tenant at all (a platform-scope session) — distinct from {@link refusal}. */
  protected readonly denied = signal(false);
  /**
   * ADR 0025's two refusal codes, from a live 403 on this screen's own
   * calls (operations IA §9.1d) — see `accessRefusal`'s own doc. Rendered
   * as `q-denied-state`/`q-locked-state`, never `staff.people.denied`'s flat
   * sentence, which is what every one of the app's other sixty-five hand-
   * written `denied` branches still fall back to.
   */
  protected readonly refusal = signal<AccessRefusal | null>(null);
  protected readonly loadError = signal<string | null>(null);

  private readonly roles = signal<readonly RoleDescriptor[]>([]);
  private readonly grants = signal<readonly GrantView[]>([]);
  private readonly directory = signal<ScopeDirectory>({ brands: [], locations: [] });
  protected readonly expandedCode = signal<string | null>(null);

  /** «Поиск» over the capability sentences a job carries — see `matchesQuery`'s own doc. */
  protected readonly query = signal('');

  protected readonly rows = computed<readonly RoleRow[]>(() => {
    const locale = sentenceLocale(this.i18n.locale());
    const grants = this.grants().filter((g) => g.status === 'ACTIVE');
    const needle = this.query().trim().toLowerCase();
    return this.roles()
      .filter((role) => matchesQuery(role, needle, locale))
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

  protected onQueryInput(value: string): void {
    this.query.set(value);
  }

  /**
   * The «Сколько человек» count used to render as a `<button>` that only
   * stopped the row's own click from opening the detail — a control that
   * looked actionable and did nothing (`staff-roles-page.html:35-37`,
   * flagged in the operations gap map). It opens the same «Кто занимает»
   * detail the row itself opens, deterministically — `stopPropagation`
   * still runs, so this is the row's own `toggle`, called once, not a second
   * toggle racing the row's bubbled click.
   */
  protected onHolderCountClick(event: Event, code: string): void {
    event.stopPropagation();
    this.toggle(code);
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
      if (error instanceof ApiError) {
        const refusal = accessRefusal(error);
        if (refusal) {
          this.refusal.set(refusal);
        } else {
          this.loadError.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
        }
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
