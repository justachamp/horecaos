import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterOutlet } from '@angular/router';

import { Auth } from '../../core/auth/auth';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { ApiError } from '../../core/api/problem-details';
import { formatDate } from '../../core/format/datetime';
import { describeApiError } from '../orders/order-errors';
import { StaffAccessDialog, StaffAccessDialogMode } from './staff-access-dialog';
import {
  GrantRequest,
  GrantView,
  RoleDescriptor,
  ScopeDirectory,
  StaffApi,
  TelegramStaffLinkView,
} from './staff-api';
import { StaffInviteDialog } from './staff-invite-dialog';
import { StaffJobDialog } from './staff-job-dialog';
import { roleLabel, scopeLevelLabel } from './staff-role-labels';
import {
  COMPANY_WIDE_GROUP,
  StaffPerson,
  activeGrants,
  groupIntoPeople,
  groupsFor,
  revokedGrants,
  sortByAttention,
  statusOf,
} from './staff-row';

type StatusFilter = 'all' | 'active' | 'suspended';
type ViewMode = 'flat' | 'byBranch';

/** Sentinel group key for a person with no active job at all — see this file's own note in `groupedRows`. */
const NO_ACTIVE_GROUP = ' no-active-job';

interface GroupRow {
  readonly label: string;
  readonly labelKey: MessageKey | null;
  readonly people: readonly StaffPerson[];
}

/**
 * Люди — the staff list (operations IA §9.1, staff-and-access.md §2).
 *
 * There is no separate staff-person record (§11.1): every row is a
 * `principalSubject` derived from `iam.grants`, never a stored name. The
 * «Сотрудник» column therefore shows the identifier, muted and captioned as
 * not built, rather than pretending a name exists.
 *
 * Three of the spec's six sort weights are not computable with this backend
 * and are honestly dropped — see `staff-row.ts`'s own doc. The «Приглашены»
 * and «Без должности» status pills are dropped for the same reason: neither
 * state is derivable from grants alone.
 */
@Component({
  selector: 'q-staff-page',
  imports: [TPipe, RouterOutlet, StaffJobDialog, StaffAccessDialog, StaffInviteDialog],
  templateUrl: './staff-page.html',
  styleUrl: './staff-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StaffPage {
  private readonly api = inject(StaffApi);
  private readonly tenant = inject(CurrentTenant);
  private readonly auth = inject(Auth);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly docked = signal(false);

  private readonly grants = signal<readonly GrantView[]>([]);
  protected readonly roles = signal<readonly RoleDescriptor[]>([]);
  protected readonly directory = signal<ScopeDirectory>({ brands: [], locations: [] });
  private readonly telegramLinks = signal<readonly TelegramStaffLinkView[]>([]);
  /** Captured once at load, not recomputed on a timer — a `computed()` never re-evaluates from wall-clock time alone. */
  private readonly loadedAt = signal(new Date());

  protected readonly statusFilter = signal<StatusFilter>('all');
  protected readonly viewMode = signal<ViewMode | null>(null);
  protected readonly search = signal('');
  protected readonly selectedJobCodes = signal<ReadonlySet<string>>(new Set());

  protected readonly jobDialogTarget = signal<string | null>(null);
  protected readonly jobDialogBusy = signal(false);
  protected readonly jobDialogError = signal<string | null>(null);

  protected readonly accessDialogTarget = signal<{
    subject: string;
    mode: StaffAccessDialogMode;
  } | null>(null);
  protected readonly accessDialogBusy = signal(false);
  protected readonly accessDialogError = signal<string | null>(null);

  protected readonly inviteDialogOpen = signal(false);

  protected readonly notice = signal<string | null>(null);

  private readonly people = computed(() => groupIntoPeople(this.grants()));
  private readonly sortedPeople = computed(() => sortByAttention(this.people(), this.loadedAt()));

  protected readonly effectiveViewMode = computed<ViewMode>(
    () => this.viewMode() ?? (this.directory().locations.length > 1 ? 'byBranch' : 'flat'),
  );

  protected readonly statusCounts = computed(() => {
    // Computed before filtering (Togora §2b, per staff-and-access.md §2), so a
    // pill's own count never moves when a different pill is what narrowed the table.
    const all = this.sortedPeople();
    const suspended = all.filter((p) => statusOf(p, this.loadedAt()).kind === 'ALL_REVOKED').length;
    return { all: all.length, active: all.length - suspended, suspended };
  });

  protected readonly filteredPeople = computed(() => {
    let list = this.sortedPeople();
    const status = this.statusFilter();
    if (status === 'active') {
      list = list.filter((p) => statusOf(p, this.loadedAt()).kind !== 'ALL_REVOKED');
    } else if (status === 'suspended') {
      list = list.filter((p) => statusOf(p, this.loadedAt()).kind === 'ALL_REVOKED');
    }

    const query = this.search().trim().toLowerCase();
    if (query) {
      list = list.filter((p) => p.principalSubject.toLowerCase().includes(query));
    }

    const jobs = this.selectedJobCodes();
    if (jobs.size > 0) {
      list = list.filter((p) => activeGrants(p).some((g) => jobs.has(g.roleCode)));
    }

    return list;
  });

  /** «По филиалам»: «Вся компания» pinned first, then a no-active-job bucket for anyone fully revoked, then branches. */
  protected readonly groupedRows = computed<readonly GroupRow[]>(() => {
    const dir = this.directory();
    const byLabel = new Map<string, StaffPerson[]>();
    for (const person of this.filteredPeople()) {
      const groups = groupsFor(person, dir);
      const keys = groups.length > 0 ? groups : [NO_ACTIVE_GROUP];
      for (const key of keys) {
        const bucket = byLabel.get(key);
        if (bucket) {
          bucket.push(person);
        } else {
          byLabel.set(key, [person]);
        }
      }
    }

    const rows: GroupRow[] = [];
    const noActive = byLabel.get(NO_ACTIVE_GROUP);
    if (noActive) {
      rows.push({ label: '', labelKey: 'staff.group.noActiveJob', people: noActive });
    }
    const companyWide = byLabel.get(COMPANY_WIDE_GROUP);
    if (companyWide) {
      rows.push({ label: '', labelKey: 'staff.group.companyWide', people: companyWide });
    }
    const branchLabels = Array.from(byLabel.keys())
      .filter((key) => key !== NO_ACTIVE_GROUP && key !== COMPANY_WIDE_GROUP)
      .sort((a, b) => a.localeCompare(b));
    for (const label of branchLabels) {
      rows.push({ label, labelKey: null, people: byLabel.get(label) as StaffPerson[] });
    }
    return rows;
  });

  /**
   * What the table actually iterates: one unlabelled group in «Все» mode, so
   * the template has exactly one rendering path rather than a flat one and a
   * grouped one duplicating the same row markup.
   */
  protected readonly displayGroups = computed<readonly GroupRow[]>(() =>
    this.effectiveViewMode() === 'flat'
      ? [{ label: '', labelKey: null, people: this.filteredPeople() }]
      : this.groupedRows(),
  );

  protected readonly hasNoStaffAtAll = computed(
    () => !this.loading() && this.sortedPeople().length === 0,
  );
  protected readonly hasNoFilterMatches = computed(
    () => !this.loading() && this.sortedPeople().length > 0 && this.filteredPeople().length === 0,
  );

  constructor() {
    void this.load();
  }

  protected openPerson(subject: string): void {
    void this.router.navigate([subject], { relativeTo: this.route });
  }

  protected onOutletActivate(): void {
    this.docked.set(true);
  }

  protected onOutletDeactivate(): void {
    this.docked.set(false);
  }

  protected isSelf(subject: string): boolean {
    return this.auth.subject() === subject;
  }

  protected statusOfPerson(person: StaffPerson) {
    return statusOf(person, this.loadedAt());
  }

  /** The caption under a flagged row's name — §2's "the reason text is the point, a bare badge is not". */
  protected rowCaption(person: StaffPerson): string | null {
    const status = this.statusOfPerson(person);
    if (status.kind === 'ALL_REVOKED') {
      return status.lastRevokedReason
        ? this.i18n.t('staff.row.revoked.reason', { reason: status.lastRevokedReason })
        : this.i18n.t('staff.row.revoked.noReason');
    }
    if (status.kind === 'EXPIRING_SOON') {
      // `formatDate` wants the tenant's IANA zone (ADR 0031); nothing this
      // page loads carries it, so a day-granularity date renders in UTC
      // rather than adding a tenant-profile fetch just for one caption. A day
      // boundary can be off by one near midnight in the tenant's own zone —
      // acceptable for "≤ 7 days", not acceptable if this were a time.
      return this.i18n.t('staff.row.expiring', {
        date: formatDate(new Date(status.validUntil), 'UTC'),
      });
    }
    return null;
  }

  protected activeJobsOf(person: StaffPerson): readonly GrantView[] {
    return activeGrants(person);
  }

  protected roleLabel(code: string): string {
    return roleLabel(code, (key) => this.i18n.t(key));
  }

  protected scopeLevelLabel(scopeType: GrantView['scopeType']): string {
    return scopeLevelLabel(scopeType, (key) => this.i18n.t(key));
  }

  protected scopeText(grant: GrantView): string {
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

  protected isLinkedToTelegram(subject: string): boolean {
    return this.telegramLinks().some((link) => link.principalSubject === subject);
  }

  protected setViewMode(mode: ViewMode): void {
    this.viewMode.set(mode);
  }

  protected setStatusFilter(filter: StatusFilter): void {
    this.statusFilter.set(filter);
  }

  protected setSearch(value: string): void {
    this.search.set(value);
  }

  protected toggleJobFilter(code: string): void {
    const next = new Set(this.selectedJobCodes());
    if (next.has(code)) {
      next.delete(code);
    } else {
      next.add(code);
    }
    this.selectedJobCodes.set(next);
  }

  protected resetFilters(): void {
    this.statusFilter.set('all');
    this.search.set('');
    this.selectedJobCodes.set(new Set());
  }

  // ------------------------------------------------------------- job dialog

  protected openJobDialog(subject: string): void {
    this.jobDialogError.set(null);
    this.jobDialogTarget.set(subject);
  }

  protected closeJobDialog(): void {
    this.jobDialogTarget.set(null);
  }

  protected async submitJob(request: GrantRequest): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      return;
    }
    this.jobDialogBusy.set(true);
    this.jobDialogError.set(null);
    try {
      await this.api.grant(tenantId, request);
      this.jobDialogTarget.set(null);
      await this.reload();
    } catch (error) {
      this.jobDialogError.set(this.describeError(error));
    } finally {
      this.jobDialogBusy.set(false);
    }
  }

  // ---------------------------------------------------------- access dialog

  protected openSuspendDialog(subject: string): void {
    this.accessDialogError.set(null);
    this.accessDialogTarget.set({ subject, mode: 'suspend' });
  }

  protected openRestoreDialog(subject: string): void {
    this.accessDialogError.set(null);
    this.accessDialogTarget.set({ subject, mode: 'restore' });
  }

  protected closeAccessDialog(): void {
    this.accessDialogTarget.set(null);
  }

  protected accessDialogAffectedCount(): number {
    const target = this.accessDialogTarget();
    if (!target) {
      return 0;
    }
    return target.mode === 'suspend'
      ? this.grantsToSuspend(target.subject).length
      : this.grantsToRestore(target.subject).length;
  }

  protected async confirmAccess({ reason }: { reason: string }): Promise<void> {
    const tenantId = this.tenant.tenantId();
    const target = this.accessDialogTarget();
    if (!tenantId || !target) {
      return;
    }
    this.accessDialogBusy.set(true);
    this.accessDialogError.set(null);
    try {
      if (target.mode === 'suspend') {
        await this.suspend(tenantId, target.subject, reason);
      } else {
        await this.restore(tenantId, target.subject, reason);
      }
      this.accessDialogTarget.set(null);
      await this.reload();
    } catch (error) {
      this.accessDialogError.set(this.describeError(error));
    } finally {
      this.accessDialogBusy.set(false);
    }
  }

  private async suspend(tenantId: string, subject: string, reason: string): Promise<void> {
    const targets = this.grantsToSuspend(subject);
    const outcomes = await Promise.allSettled(
      targets.map((grant) => this.api.revoke(tenantId, grant.id, reason)),
    );
    this.reportOutcomes(outcomes.length, outcomes.filter((o) => o.status === 'fulfilled').length);
  }

  private async restore(tenantId: string, subject: string, reason: string): Promise<void> {
    const targets = this.grantsToRestore(subject);
    const outcomes = await Promise.allSettled(
      targets.map((grant) => {
        const { brandId, locationId } = this.resolveScopeIdentifiers(grant);
        return this.api.grant(tenantId, {
          principalSubject: subject,
          roleCode: grant.roleCode,
          brandId,
          locationId,
          reason,
        });
      }),
    );
    this.reportOutcomes(outcomes.length, outcomes.filter((o) => o.status === 'fulfilled').length);
  }

  /**
   * `GrantView` carries only its own level's `scopeId` (matching `iam.grants`
   * itself), never a location's parent brand — so a LOCATION-scoped restore
   * resolves the brand from the already-loaded {@link ScopeDirectory} rather
   * than needing a new read.
   */
  private resolveScopeIdentifiers(grant: GrantView): { brandId?: string; locationId?: string } {
    if (grant.scopeType === 'BRAND') {
      return { brandId: grant.scopeId ?? undefined };
    }
    if (grant.scopeType === 'LOCATION') {
      const location = this.directory().locations.find((l) => l.id === grant.scopeId);
      return { brandId: location?.brandId, locationId: grant.scopeId ?? undefined };
    }
    return {};
  }

  /** Every active grant of this person — ADR 0039's "N independent operations", one revoke call each. */
  private grantsToSuspend(subject: string): readonly GrantView[] {
    const person = this.people().find((p) => p.principalSubject === subject);
    return person ? activeGrants(person) : [];
  }

  /** The most recently revoked batch — every revoked grant sharing the latest `revokedAt` instant. */
  private grantsToRestore(subject: string): readonly GrantView[] {
    const person = this.people().find((p) => p.principalSubject === subject);
    if (!person) {
      return [];
    }
    const revoked = revokedGrants(person);
    if (revoked.length === 0) {
      return [];
    }
    const latest = revoked.reduce((max, g) =>
      (g.revokedAt ?? '') > (max.revokedAt ?? '') ? g : max,
    );
    return revoked.filter((g) => g.revokedAt === latest.revokedAt);
  }

  private reportOutcomes(total: number, succeeded: number): void {
    this.notice.set(
      succeeded === total
        ? this.i18n.t('staff.outcome.allSucceeded', { count: succeeded })
        : this.i18n.t('staff.outcome.partial', { succeeded, total }),
    );
  }

  // ---------------------------------------------------------- invite dialog

  protected openInviteDialog(): void {
    this.inviteDialogOpen.set(true);
  }

  protected closeInviteDialog(): void {
    this.inviteDialogOpen.set(false);
  }

  // ----------------------------------------------------------------- load

  private async reload(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      return;
    }
    this.grants.set(await this.api.listGrants(tenantId, true));
    this.loadedAt.set(new Date());
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
      const [grants, roles, directory, telegramLinks] = await Promise.all([
        this.api.listGrants(tenantId, true),
        this.api.roles(tenantId),
        this.api.scopeDirectory(tenantId),
        this.api.telegramLinks(tenantId).catch(() => []),
      ]);
      this.grants.set(grants);
      this.roles.set(roles);
      this.directory.set(directory);
      this.telegramLinks.set(telegramLinks);
      this.loadedAt.set(new Date());
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else if (error instanceof ApiError) {
        this.loadError.set(this.describeError(error));
      } else {
        throw error;
      }
    } finally {
      this.loading.set(false);
    }
  }

  protected myScopes() {
    return this.tenant.scopes();
  }

  protected tenantId(): string | null {
    return this.tenant.tenantId();
  }

  private describeError(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    throw error;
  }
}
