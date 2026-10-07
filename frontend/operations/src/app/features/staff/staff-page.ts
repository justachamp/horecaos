import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterOutlet } from '@angular/router';

import { Auth } from '../../core/auth/auth';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { formatDate } from '../../core/format/datetime';
import { describeApiError } from '../orders/order-errors';
import { StaffAccessDialog, StaffAccessDialogMode } from './staff-access-dialog';
import {
  GrantRequest,
  GrantView,
  RoleDescriptor,
  ScopeDirectory,
  StaffApi,
  StaffInvitationOutstanding,
  StaffInvitationRequest,
  TelegramStaffLinkView,
} from './staff-api';
import { StaffInviteDialog } from './staff-invite-dialog';
import { StaffJobDialog } from './staff-job-dialog';
import { StaffMfaPolicyCard } from './staff-mfa-policy-card';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { MfaSummaryRow, StaffMember, StaffMembersApi } from './staff-members-api';
import { roleLabel, scopeLevelLabel } from './staff-role-labels';
import {
  COMPANY_WIDE_GROUP,
  StaffPerson,
  StaffStatus,
  activeGrants,
  groupIntoPeople,
  groupsFor,
  hasNoAccess,
  initialsOf,
  matchesQuery,
  nameOf,
  referenceOf,
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
 * Each row is a person the tenant keeps a record for (ADR 0139): name, masked
 * phone, the `S-0142` reference and employment, joined to the jobs they hold.
 * The jobs come from `iam.grants`; the person comes from `iam.staff_members`,
 * read once for the whole list (`StaffMembersApi.list`), sorted and searched
 * here because a name is ciphertext in the database. A subject with a job and
 * no record (an account that predates the record) is still listed, by its
 * identifier, and says it has no profile yet.
 *
 * Two of the spec's six sort weights are not computable with this backend and
 * are honestly dropped — see `staff-row.ts`'s own doc. The «Без должности»
 * pill is dropped for the same reason.
 */
@Component({
  selector: 'q-staff-page',
  imports: [
    TPipe,
    RouterOutlet,
    StaffJobDialog,
    StaffAccessDialog,
    StaffInviteDialog,
    StaffMfaPolicyCard,
  ],
  templateUrl: './staff-page.html',
  styleUrl: './staff-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StaffPage {
  private readonly api = inject(StaffApi);
  private readonly membersApi = inject(StaffMembersApi);
  private readonly tenant = inject(CurrentTenant);
  private readonly auth = inject(Auth);
  private readonly capabilities = inject(SessionCapabilities);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly docked = signal(false);

  private readonly grants = signal<readonly GrantView[]>([]);
  /** The tenant's own records of its people (ADR 0139). Empty when the read is refused: the list then falls back to identifiers. */
  private readonly members = signal<readonly StaffMember[]>([]);
  protected readonly roles = signal<readonly RoleDescriptor[]>([]);
  protected readonly directory = signal<ScopeDirectory>({ brands: [], locations: [] });
  private readonly telegramLinks = signal<readonly TelegramStaffLinkView[]>([]);
  /** Open (not accepted, not cancelled) staff invitations — the «Приглашён» pill and staff-access-dialog's resend/revoke. */
  private readonly invitations = signal<readonly StaffInvitationOutstanding[]>([]);
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
  protected readonly inviteDialogBusy = signal(false);
  protected readonly inviteDialogError = signal<string | null>(null);
  protected readonly inviteDuplicateSubject = signal<string | null>(null);
  protected readonly inviteCreatedLink = signal<string | null>(null);
  /** Whether `inviteCreatedLink` is showing a freshly-resent link rather than a just-created one — see `confirmAccess`. */
  protected readonly inviteLinkResent = signal(false);

  protected readonly notice = signal<string | null>(null);

  private readonly people = computed(() => groupIntoPeople(this.grants(), this.members()));
  /** Every subject with an open staff invitation — staff-row.ts's `INVITED` status (ADR 0116). */
  private readonly invitedSubjects = computed(
    () => new Set(this.invitations().map((invitation) => invitation.principalSubject)),
  );
  private readonly sortedPeople = computed(() =>
    sortByAttention(this.people(), this.loadedAt(), this.invitedSubjects()),
  );

  protected readonly effectiveViewMode = computed<ViewMode>(
    () => this.viewMode() ?? (this.directory().locations.length > 1 ? 'byBranch' : 'flat'),
  );

  protected readonly statusCounts = computed(() => {
    // Computed before filtering (Togora §2b, per staff-and-access.md §2), so a
    // pill's own count never moves when a different pill is what narrowed the table.
    const all = this.sortedPeople();
    const suspended = all.filter((p) => hasNoAccess(statusOf(p, this.loadedAt()))).length;
    return { all: all.length, active: all.length - suspended, suspended };
  });

  protected readonly filteredPeople = computed(() => {
    let list = this.sortedPeople();
    const status = this.statusFilter();
    if (status === 'active') {
      list = list.filter((p) => !hasNoAccess(statusOf(p, this.loadedAt())));
    } else if (status === 'suspended') {
      list = list.filter((p) => hasNoAccess(statusOf(p, this.loadedAt())));
    }

    const query = this.search();
    if (query.trim()) {
      list = list.filter((p) => matchesQuery(p, query));
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

  /**
   * ADR 0148: the «Способ входа» column, read from Keycloak's credential lists in one call.
   * Absent for a holder of neither `iam.staff.mfa.read` nor a working Keycloak: the column
   * then shows nothing for a person, never a confident "password only".
   */
  private readonly mfaByMember = signal<ReadonlyMap<string, MfaSummaryRow>>(new Map());
  protected readonly showMfaColumn = computed(() => this.capabilities.has('IAM_STAFF_MFA_READ'));

  protected mfaOf(person: StaffPerson): MfaSummaryRow | null {
    const memberId = person.member?.memberId;
    const row = memberId === undefined ? undefined : this.mfaByMember().get(memberId);
    return row !== undefined && row.state === 'KNOWN' ? row : null;
  }

  private async loadMfaSummary(tenantId: string): Promise<void> {
    if (!this.showMfaColumn()) {
      return;
    }
    try {
      const rows = await this.membersApi.mfaSummary(tenantId);
      this.mfaByMember.set(new Map(rows.map((row) => [row.memberId, row])));
    } catch {
      // The column is an aid: a failed read leaves it blank, never the list.
      this.mfaByMember.set(new Map());
    }
  }

  protected isSelf(subject: string): boolean {
    return this.auth.subject() === subject;
  }

  protected statusOfPerson(person: StaffPerson) {
    return statusOf(person, this.loadedAt(), this.invitedSubjects().has(person.principalSubject));
  }

  /** The caption under a flagged row's name — §2's "the reason text is the point, a bare badge is not". */
  protected rowCaption(person: StaffPerson): string | null {
    const status = this.statusOfPerson(person);
    if (status.kind === 'ALL_REVOKED') {
      return status.lastRevokedReason
        ? this.i18n.t('staff.row.revoked.reason', { reason: status.lastRevokedReason })
        : this.i18n.t('staff.row.revoked.noReason');
    }
    if (status.kind === 'ENDED') {
      return this.i18n.t('staff.row.ended', { date: this.endedOn(status) });
    }
    if (status.kind === 'ACCESS_DRIFT') {
      return this.i18n.t('staff.row.accessDrift', { date: this.endedOn(status) });
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
    if (status.kind === 'INVITED') {
      return this.i18n.t('staff.row.invited');
    }
    return null;
  }

  /** `employed_until` is a calendar date, not an instant: shown as it is stored, never shifted through a zone. */
  private endedOn(status: Extract<StaffStatus, { endedOn: string | null }>): string {
    return status.endedOn ?? '—';
  }

  /** The status pill's message key — one place, so the template carries no nested ternary. */
  protected statusLabelKey(person: StaffPerson) {
    switch (this.statusOfPerson(person).kind) {
      case 'ALL_REVOKED':
        return 'staff.status.revoked';
      case 'ENDED':
        return 'staff.status.ended';
      case 'ACCESS_DRIFT':
        return 'staff.status.accessDrift';
      case 'EXPIRING_SOON':
        return 'staff.status.expiring';
      case 'INVITED':
        return 'staff.status.invited';
      case 'ON_LEAVE':
        return 'staff.status.onLeave';
      case 'OK':
        return 'staff.status.ok';
    }
  }

  /** No access left to grant: every job revoked, or employment ended and the jobs gone with it. */
  protected isInactive(person: StaffPerson): boolean {
    return hasNoAccess(this.statusOfPerson(person));
  }

  /** A person whose employment has ended is not handed a new job from a list row. */
  protected canAddJob(person: StaffPerson): boolean {
    const kind = this.statusOfPerson(person).kind;
    return kind !== 'ENDED' && kind !== 'ACCESS_DRIFT';
  }

  /** «Приостановить доступ» is offered while there is an active job to take away -- which is also how an owner finishes an access drift. */
  protected canSuspend(person: StaffPerson): boolean {
    return activeGrants(person).length > 0;
  }

  /** «Восстановить» brings back the last revoked batch of jobs; it is never offered for someone whose employment has ended. */
  protected canRestore(person: StaffPerson): boolean {
    return this.statusOfPerson(person).kind === 'ALL_REVOKED';
  }

  /** The tenant's name for the person, or `null` — the template then shows the identifier and says there is no profile. */
  protected nameOf(person: StaffPerson): string | null {
    return nameOf(person);
  }

  protected referenceOf(person: StaffPerson): string | null {
    return referenceOf(person);
  }

  protected initialsOf(person: StaffPerson): string {
    return initialsOf(person);
  }

  /** The list carries the masked number only; the full one is on the card. */
  protected maskedPhoneOf(person: StaffPerson): string | null {
    return person.member?.maskedPhone ?? null;
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

  /** The one open invitation this subject was created under, if any — resend/revoke's own lookup. */
  protected pendingInvitationOf(subject: string): StaffInvitationOutstanding | null {
    return this.invitations().find((invitation) => invitation.principalSubject === subject) ?? null;
  }

  protected openResendInviteDialog(subject: string): void {
    this.accessDialogError.set(null);
    this.accessDialogTarget.set({ subject, mode: 'resendInvite' });
  }

  protected openRevokeInviteDialog(subject: string): void {
    this.accessDialogError.set(null);
    this.accessDialogTarget.set({ subject, mode: 'revokeInvite' });
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
      : target.mode === 'restore'
        ? this.grantsToRestore(target.subject).length
        : 1;
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
      let resentLink: string | null = null;
      if (target.mode === 'suspend') {
        await this.suspend(tenantId, target.subject, reason);
      } else if (target.mode === 'restore') {
        await this.restore(tenantId, target.subject, reason);
      } else {
        const invitation = this.pendingInvitationOf(target.subject);
        if (!invitation) {
          throw new Error('This invitation is no longer open');
        }
        if (target.mode === 'resendInvite') {
          const resent = await this.api.resendStaffInvitation(
            tenantId,
            invitation.invitationId,
            reason,
          );
          resentLink = resent.inviteLink;
        } else {
          await this.api.revokeStaffInvitation(tenantId, invitation.invitationId, reason);
        }
      }
      this.accessDialogTarget.set(null);
      await this.reload();
      if (resentLink) {
        // Resend mints a fresh one-time link and invalidates the old one
        // (StaffInvitationService#resend) — for a phone-only colleague it
        // is the only way to reach them, so the console has to show it
        // here rather than discard it. Reuses the invite dialog's own
        // success/copy-link view rather than a bespoke one.
        this.inviteDuplicateSubject.set(null);
        this.inviteCreatedLink.set(resentLink);
        this.inviteLinkResent.set(true);
        this.inviteDialogOpen.set(true);
      }
    } catch (error) {
      this.accessDialogError.set(this.describeError(error));
    } finally {
      this.accessDialogBusy.set(false);
    }
  }

  /**
   * ADR 0039's "N independent operations" is also Staff 9.3c's "N audit rows
   * that must correlate as one action": one id minted once, sent on every
   * revoke in the fan-out, so {@code GrantAuditListener} writes it as every
   * row's own `correlation_id` instead of each grant's own id. Without this
   * the activity log's «Часть массового действия» chip found exactly one row
   * for a batch of twelve.
   */
  private async suspend(tenantId: string, subject: string, reason: string): Promise<void> {
    const targets = this.grantsToSuspend(subject);
    const correlationId = crypto.randomUUID();
    const outcomes = await Promise.allSettled(
      targets.map((grant) => this.api.revoke(tenantId, grant.id, reason, correlationId)),
    );
    this.reportOutcomes(outcomes.length, outcomes.filter((o) => o.status === 'fulfilled').length);
  }

  /** Same bulk-correlation reasoning as {@link suspend}, for a restore's fan-out of grants. */
  private async restore(tenantId: string, subject: string, reason: string): Promise<void> {
    const targets = this.grantsToRestore(subject);
    const correlationId = crypto.randomUUID();
    const outcomes = await Promise.allSettled(
      targets.map((grant) => {
        const { brandId, locationId } = this.resolveScopeIdentifiers(grant);
        return this.api.grant(
          tenantId,
          {
            principalSubject: subject,
            roleCode: grant.roleCode,
            brandId,
            locationId,
            reason,
          },
          correlationId,
        );
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
    this.inviteDialogError.set(null);
    this.inviteDuplicateSubject.set(null);
    this.inviteCreatedLink.set(null);
    this.inviteLinkResent.set(false);
    this.inviteDialogOpen.set(true);
  }

  protected closeInviteDialog(): void {
    this.inviteDialogOpen.set(false);
    // A completed invite already refreshed the list on submit; closing from
    // the success state must not lose that state before the reset.
    this.inviteCreatedLink.set(null);
    this.inviteLinkResent.set(false);
    this.inviteDuplicateSubject.set(null);
    this.inviteDialogError.set(null);
  }

  protected async submitInvite(request: StaffInvitationRequest): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      return;
    }
    this.inviteDialogBusy.set(true);
    this.inviteDialogError.set(null);
    this.inviteDuplicateSubject.set(null);
    try {
      const created = await this.api.invite(tenantId, request);
      this.inviteCreatedLink.set(created.inviteLink);
      this.notice.set(this.i18n.t('staff.inviteDialog.toast'));
      await this.reload();
    } catch (error) {
      if (error instanceof ApiError && error.code === ApiErrorCode.RESOURCE_CONFLICT) {
        const existingSubjectId = error.problem?.['existingSubjectId'];
        if (typeof existingSubjectId === 'string') {
          this.inviteDuplicateSubject.set(existingSubjectId);
          return;
        }
      }
      this.inviteDialogError.set(this.describeError(error));
    } finally {
      this.inviteDialogBusy.set(false);
    }
  }

  // ----------------------------------------------------------------- load

  private async reload(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      return;
    }
    const [grants, invitations, members] = await Promise.all([
      this.api.listGrants(tenantId, true),
      this.api.staffInvitations(tenantId).catch(() => []),
      this.membersApi.list(tenantId).catch(() => []),
    ]);
    this.grants.set(grants);
    this.invitations.set(invitations);
    this.members.set(members);
    this.loadedAt.set(new Date());
    void this.loadMfaSummary(tenantId);
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
      const [grants, roles, directory, telegramLinks, invitations, members] = await Promise.all([
        this.api.listGrants(tenantId, true),
        this.api.roles(tenantId),
        this.api.scopeDirectory(tenantId),
        this.api.telegramLinks(tenantId).catch(() => []),
        this.api.staffInvitations(tenantId).catch(() => []),
        // A refused or failed read of the records must not blank the list: it
        // then shows identifiers, as it did before the record existed.
        this.membersApi.list(tenantId).catch(() => []),
      ]);
      this.grants.set(grants);
      this.roles.set(roles);
      this.directory.set(directory);
      this.telegramLinks.set(telegramLinks);
      this.invitations.set(invitations);
      this.members.set(members);
      this.loadedAt.set(new Date());
      void this.loadMfaSummary(tenantId);
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
