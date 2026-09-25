import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';

import { Auth } from '../../core/auth/auth';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { CAPABILITY_SENTENCES, capabilityAreaName, sentenceLocale } from './capability-sentences';
import { StaffAccessDialog } from './staff-access-dialog';
import {
  GrantRequest,
  GrantView,
  OperatorTodayCounts,
  RoleDescriptor,
  ScopeDirectory,
  StaffApi,
  TelegramStaffLinkView,
} from './staff-api';
import { StaffJobDialog } from './staff-job-dialog';
import { roleLabel, scopeLevelLabel } from './staff-role-labels';

type StaffTab = 'access' | 'security';

interface CapabilityGroup {
  readonly area: string;
  readonly sentences: readonly string[];
}

/**
 * Карточка сотрудника — the person record (operations IA §9.1, staff-and-access.md
 * §3), reduced to what is P-tier: the **Доступ** and **Безопасность** tabs.
 * **Активность** and **Смены** are left off entirely rather than built
 * half-way — both are person-scoped slices of screens the IA tiers at 2
 * (9.8 Журнал действий, 9.6 Смены), and neither's backend exists yet either
 * (§11.6, §11.11, §11.13).
 *
 * No stored name exists (§11.1): the identity block shows the Keycloak
 * subject, captioned as not built, exactly where the spec would put a photo
 * and a name.
 */
@Component({
  selector: 'q-staff-member-detail-pane',
  imports: [TPipe, StaffJobDialog, StaffAccessDialog],
  templateUrl: './staff-member-detail-pane.html',
  styleUrl: './staff-member-detail-pane.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StaffMemberDetailPane {
  private readonly api = inject(StaffApi);
  private readonly tenant = inject(CurrentTenant);
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  protected readonly i18n = inject(I18n);

  /** Route param, bound by `withComponentInputBinding()` — see `location-detail-pane.ts` for the same idiom. */
  readonly subjectId = input.required<string>();

  protected readonly activeTab = signal<StaffTab>('access');
  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly notFound = signal(false);
  protected readonly loadError = signal<string | null>(null);

  private readonly allGrants = signal<readonly GrantView[]>([]);
  protected readonly roles = signal<readonly RoleDescriptor[]>([]);
  protected readonly directory = signal<ScopeDirectory>({ brands: [], locations: [] });
  protected readonly telegramLinks = signal<readonly TelegramStaffLinkView[]>([]);
  protected readonly expandedGrantId = signal<string | null>(null);

  /** Staff 9.2d — null while loading or on a fetch failure, which the card renders as simply absent. */
  protected readonly todayCounts = signal<OperatorTodayCounts | null>(null);
  protected readonly todayCountsLoading = signal(true);

  protected readonly jobDialogOpen = signal(false);
  protected readonly jobDialogBusy = signal(false);
  protected readonly jobDialogError = signal<string | null>(null);

  protected readonly removeTargetGrantId = signal<string | null>(null);
  protected readonly removeBusy = signal(false);
  protected readonly removeError = signal<string | null>(null);

  /**
   * «Отвязать» — an administrator's unlink (operations-gap-map.md `9/X.1`):
   * `TelegramStaffLinkService` had no revoke until this wave, so this whole
   * block is new. An inline reveal rather than `q-staff-access-dialog`: that
   * dialog's copy is written for suspending an *assignment* ("N jobs will
   * stop working") and has no sentence that is honest about severing a
   * Telegram identity fact instead — see `TelegramStaffLinkService#revoke`'s
   * own doc for why this never touches a grant at all.
   */
  protected readonly telegramUnlinkOpen = signal(false);
  protected readonly telegramUnlinkReason = signal('');
  protected readonly telegramUnlinkTouched = signal(false);
  protected readonly telegramUnlinkBusy = signal(false);
  protected readonly telegramUnlinkError = signal<string | null>(null);

  protected readonly telegramUnlinkReasonMissing = computed(
    () => this.telegramUnlinkTouched() && this.telegramUnlinkReason().trim() === '',
  );

  protected readonly myGrants = computed(() =>
    this.allGrants().filter((grant) => grant.principalSubject === this.subjectId()),
  );

  protected readonly activeGrants = computed(() =>
    this.myGrants().filter((g) => g.status === 'ACTIVE'),
  );

  protected readonly isSelf = computed(() => this.auth.subject() === this.subjectId());

  protected readonly accessStatusKey = computed<'staff.status.revoked' | 'staff.status.ok'>(() =>
    this.activeGrants().length === 0 ? 'staff.status.revoked' : 'staff.status.ok',
  );

  /** «В системе с» — `min(valid_from)`, a proxy for a hire date this backend does not track (§3's own doc calls this "honest enough"). */
  protected readonly memberSince = computed<string | null>(() => {
    const grants = this.myGrants();
    if (grants.length === 0) {
      return null;
    }
    return grants.reduce(
      (earliest, g) => (g.validFrom < earliest ? g.validFrom : earliest),
      grants[0].validFrom,
    );
  });

  protected readonly telegramLink = computed(
    () => this.telegramLinks().find((link) => link.principalSubject === this.subjectId()) ?? null,
  );

  constructor() {
    // Re-reads on a `:subjectId` change under the docked outlet's default
    // RouteReuseStrategy — the same reason `location-detail-pane.ts` keys its
    // load off an `effect()` rather than the constructor alone.
    effect(() => {
      const subject = this.subjectId();
      void this.load(subject);
    });
  }

  protected selectTab(tab: StaffTab): void {
    this.activeTab.set(tab);
  }

  protected toggleCapabilities(grantId: string): void {
    this.expandedGrantId.set(this.expandedGrantId() === grantId ? null : grantId);
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

  /** «Что можно делать» — grouped by area, plain sentences, never a dotted code (§3). */
  protected capabilityGroups(roleCode: string): readonly CapabilityGroup[] {
    const role = this.roles().find((candidate) => candidate.code === roleCode);
    if (!role) {
      return [];
    }
    const locale = sentenceLocale(this.i18n.locale());
    const byArea = new Map<string, string[]>();
    for (const code of role.capabilities) {
      const area = capabilityAreaName(code, locale);
      const sentence = CAPABILITY_SENTENCES[code]?.[locale] ?? code;
      const bucket = byArea.get(area);
      if (bucket) {
        bucket.push(sentence);
      } else {
        byArea.set(area, [sentence]);
      }
    }
    return Array.from(byArea, ([area, sentences]) => ({ area, sentences: sentences.sort() })).sort(
      (a, b) => a.area.localeCompare(b.area),
    );
  }

  // -------------------------------------------------------------- add job

  protected openJobDialog(): void {
    this.jobDialogError.set(null);
    this.jobDialogOpen.set(true);
  }

  protected closeJobDialog(): void {
    this.jobDialogOpen.set(false);
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
      this.jobDialogOpen.set(false);
      await this.reload(tenantId);
    } catch (error) {
      this.jobDialogError.set(this.describe(error));
    } finally {
      this.jobDialogBusy.set(false);
    }
  }

  // -------------------------------------------------------------- remove

  /** «Убрать» — absent on the actor's own last assignment; §3 will not let the software lock its own operator out. */
  protected canRemove(grant: GrantView): boolean {
    return !(
      this.isSelf() &&
      this.activeGrants().length === 1 &&
      this.activeGrants()[0].id === grant.id
    );
  }

  protected openRemoveDialog(grantId: string): void {
    this.removeError.set(null);
    this.removeTargetGrantId.set(grantId);
  }

  protected closeRemoveDialog(): void {
    this.removeTargetGrantId.set(null);
  }

  protected async confirmRemove({ reason }: { reason: string }): Promise<void> {
    const tenantId = this.tenant.tenantId();
    const grantId = this.removeTargetGrantId();
    if (!tenantId || !grantId) {
      return;
    }
    this.removeBusy.set(true);
    this.removeError.set(null);
    try {
      await this.api.revoke(tenantId, grantId, reason);
      this.removeTargetGrantId.set(null);
      await this.reload(tenantId);
    } catch (error) {
      this.removeError.set(this.describe(error));
    } finally {
      this.removeBusy.set(false);
    }
  }

  // ----------------------------------------------------------- telegram unlink

  protected openTelegramUnlink(): void {
    this.telegramUnlinkError.set(null);
    this.telegramUnlinkReason.set('');
    this.telegramUnlinkTouched.set(false);
    this.telegramUnlinkOpen.set(true);
  }

  protected cancelTelegramUnlink(): void {
    this.telegramUnlinkOpen.set(false);
  }

  protected setTelegramUnlinkReason(value: string): void {
    this.telegramUnlinkReason.set(value);
  }

  protected async confirmTelegramUnlink(): Promise<void> {
    this.telegramUnlinkTouched.set(true);
    const reason = this.telegramUnlinkReason().trim();
    const tenantId = this.tenant.tenantId();
    const link = this.telegramLink();
    if (!reason || !tenantId || !link) {
      return;
    }
    this.telegramUnlinkBusy.set(true);
    this.telegramUnlinkError.set(null);
    try {
      await this.api.revokeTelegramLink(tenantId, link.id, reason);
      this.telegramUnlinkOpen.set(false);
      this.telegramLinks.set(await this.api.telegramLinks(tenantId).catch(() => []));
    } catch (error) {
      this.telegramUnlinkError.set(this.describe(error));
    } finally {
      this.telegramUnlinkBusy.set(false);
    }
  }

  protected myScopes() {
    return this.tenant.scopes();
  }

  protected tenantId(): string | null {
    return this.tenant.tenantId();
  }

  /** Back is a text link above the title (§3), not a redirect — a dangling deep link should say so, not silently bounce. */
  protected backToList(): void {
    void this.router.navigate(['..'], { relativeTo: this.route });
  }

  /**
   * Staff 9.3's deep link the other way: from this person's own card into
   * the activity log, pre-filtered to them. `activity-log-page.ts`'s own doc
   * names exactly this — "a deep link from a person's own card would seed
   * [the actor filter] without a fetch this screen does not otherwise need"
   * — via the `actor` query param `withComponentInputBinding()` binds there.
   */
  protected viewActivity(): void {
    void this.router.navigate(['/staff/activity'], { queryParams: { actor: this.subjectId() } });
  }

  private async reload(tenantId: string): Promise<void> {
    this.allGrants.set(await this.api.listGrants(tenantId, true));
  }

  private async load(subjectId: string): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    this.notFound.set(false);
    this.todayCounts.set(null);
    this.todayCountsLoading.set(true);
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
      this.allGrants.set(grants);
      this.roles.set(roles);
      this.directory.set(directory);
      this.telegramLinks.set(telegramLinks);
      if (!grants.some((g) => g.principalSubject === subjectId)) {
        this.notFound.set(true);
      } else {
        void this.loadTodayCounts(tenantId, subjectId);
      }
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(this.describe(error));
      }
    } finally {
      this.loading.set(false);
    }
  }

  /**
   * Staff 9.2d — fetched separately from {@link load}'s own `Promise.all`
   * rather than joined into it: this read can fail on its own (a manager
   * with `ORDER_READ` but no order data yet is not an error worth blanking
   * the whole card for) without the access/security tabs going down with it.
   */
  private async loadTodayCounts(tenantId: string, subjectId: string): Promise<void> {
    this.todayCountsLoading.set(true);
    try {
      this.todayCounts.set(await this.api.operatorTodayOrderCounts(tenantId, subjectId));
    } catch {
      this.todayCounts.set(null);
    } finally {
      this.todayCountsLoading.set(false);
    }
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
