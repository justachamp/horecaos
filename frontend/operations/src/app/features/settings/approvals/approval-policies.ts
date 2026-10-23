import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { SessionCapabilities } from '../../../core/auth/session-capabilities';
import { formatDateTime } from '../../../core/format/datetime';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { approvalActionLabelKey } from '../../staff/approval-action-labels';
import { SettingsScope } from '../settings-scope';
import {
  ApprovalPoliciesApi,
  AuthorPolicyInput,
  MissingPolicyMode,
  PolicyCoverage,
  PolicyScopeType,
  PolicyVersion,
} from './approval-policies-api';
import { ApprovalTextKey, ApprovalTextPipe, approvalText } from './approvals-texts';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';

/**
 * The approver capabilities offered as suggestions. Anything the platform declares is accepted by the server;
 * these are the ones the registered actions are actually governed by today, so the common case is one pick.
 */
export const APPROVER_CAPABILITY_SUGGESTIONS: readonly string[] = [
  'approval.decide',
  'refund.approve',
  'courier.adjustment.approve',
  'pricing.promotion.manage',
  'customer.pii.export',
];

/** One action row: the coverage as the platform reports it, with the words the table prints for it. */
interface ActionRow {
  readonly coverage: PolicyCoverage;
  readonly code: string;
  readonly label: string;
  /** In force somewhere, so the "with no policy" default is not what governs it everywhere. */
  readonly governed: boolean;
  /** Fail-closed with nothing published: the action cannot be taken at all until a policy exists. */
  readonly blocked: boolean;
}

/**
 * Who signs (row 9.4, ADR 0050, ADR 0027, ADR 0181): the tenant's approval policies on Settings, Approvals.
 *
 * The thresholds above this section decide *whether to ask*; this decides *whether a second signature is then
 * required and who may give it*. Until this screen existed the three routes behind it were published only in the
 * control-plane group, so a tenant could be told that an action "needs a published approval policy" and had
 * nowhere to publish one: a manual courier penalty is fail-closed and nothing seeds a floor for it, and a refund
 * or an export could not be put behind a second signature from any screen.
 *
 * The page never decides what a policy means. It lists every action the tenant's own approvers decide (an action
 * HorecaOS decides about a tenant is not offered), what happens to each with no policy in words, and the versions
 * published so far; it publishes the next version at the level the scope bar names, and end-dates one that
 * governs now. A refusal comes back as the server's sentence. Only the owner holds `approval.policy.manage`, so
 * anyone else is told so instead of being handed a control that would be refused.
 */
@Component({
  selector: 'q-approval-policies',
  imports: [TPipe, ApprovalTextPipe],
  templateUrl: './approval-policies.html',
  styleUrl: './approval-policies.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ApprovalPolicies {
  private readonly api = inject(ApprovalPoliciesApi);
  private readonly tenant = inject(CurrentTenant);
  private readonly capabilities = inject(SessionCapabilities);
  protected readonly scope = inject(SettingsScope);
  protected readonly i18n = inject(I18n);

  protected readonly suggestions = APPROVER_CAPABILITY_SUGGESTIONS;

  protected readonly state = signal<LoadState>('loading');
  protected readonly coverage = signal<readonly PolicyCoverage[]>([]);
  protected readonly loadError = signal<string | null>(null);
  protected readonly notice = signal<ApprovalTextKey | null>(null);

  /** The action whose publish form is open, or null. */
  protected readonly publishing = signal<string | null>(null);
  protected readonly threshold = signal('');
  protected readonly capability = signal('');
  protected readonly validFrom = signal('');
  protected readonly reason = signal('');

  /** The version whose end form is open, or null. */
  protected readonly ending = signal<PolicyVersion | null>(null);
  protected readonly endReason = signal('');

  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);

  protected readonly canManage = computed(() => this.capabilities.has('APPROVAL_POLICY_MANAGE'));

  /** What the scope bar says a new policy applies to: the company, the brand, or the branch in view. */
  protected readonly target = computed<{
    readonly scopeType: PolicyScopeType;
    readonly brandId: string | null;
    readonly locationId: string | null;
  }>(() => {
    const level = this.scope.level();
    const brandId = this.scope.brandId();
    const locationId = this.scope.locationId();
    if (level === 'LOCATION' && brandId && locationId) {
      return { scopeType: 'LOCATION', brandId, locationId };
    }
    if (level === 'BRAND' && brandId) {
      return { scopeType: 'BRAND', brandId, locationId: null };
    }
    return { scopeType: 'TENANT', brandId: null, locationId: null };
  });

  protected readonly rows = computed<readonly ActionRow[]>(() => {
    // The labels follow the active locale; depending on it here re-words the table on a switch.
    this.i18n.locale();
    return this.coverage()
      .filter((entry) => entry.worklist === 'TENANT')
      .map((entry) => this.rowOf(entry))
      .sort(
        (a, b) =>
          Number(b.blocked) - Number(a.blocked) ||
          a.label.localeCompare(b.label, this.i18n.locale()),
      );
  });

  protected readonly publishingRow = computed(
    () => this.rows().find((row) => row.code === this.publishing()) ?? null,
  );

  protected readonly canPublish = computed(
    () =>
      !this.saving() &&
      this.threshold().trim() !== '' &&
      this.capability().trim() !== '' &&
      this.reason().trim() !== '',
  );

  protected readonly canEnd = computed(() => !this.saving() && this.endReason().trim() !== '');

  constructor() {
    effect(() => {
      const tenantId = this.tenant.tenantId();
      if (tenantId && this.canManage()) {
        void this.load(tenantId);
      } else if (!this.canManage()) {
        this.state.set('denied');
      }
    });
  }

  // ------------------------------------------------------------------------------------ words

  protected text(key: ApprovalTextKey, values?: Readonly<Record<string, string | number>>): string {
    return approvalText(this.i18n.locale(), key, values);
  }

  protected withoutPolicyText(mode: MissingPolicyMode): string {
    return this.text(`who.without.${mode}` as ApprovalTextKey);
  }

  /** Where a version applies, in words: the company, or the brand or branch by the name the scope bar knows. */
  protected scopeText(version: PolicyVersion): string {
    if (version.scopeType === 'LOCATION') {
      const name = this.scope.locations().find((l) => l.id === version.locationId)?.displayName;
      return this.text('who.scope.LOCATION', { name: name ?? shortId(version.locationId) });
    }
    if (version.scopeType === 'BRAND') {
      const name = this.scope.brands().find((b) => b.id === version.brandId)?.displayName;
      return this.text('who.scope.BRAND', { name: name ?? shortId(version.brandId) });
    }
    return this.text('who.scope.TENANT');
  }

  protected startsText(version: PolicyVersion): string | null {
    return version.governsNow
      ? null
      : this.text('who.scheduled', { when: formatDateTime(new Date(version.validFrom), 'UTC') });
  }

  protected targetText(): string {
    const target = this.target();
    return this.scopeText({
      scopeType: target.scopeType,
      brandId: target.brandId,
      locationId: target.locationId,
    } as PolicyVersion);
  }

  // ---------------------------------------------------------------------------------- publish

  protected openPublish(code: string): void {
    this.ending.set(null);
    this.threshold.set('');
    this.capability.set('');
    this.validFrom.set('');
    this.reason.set('');
    this.formError.set(null);
    this.notice.set(null);
    this.publishing.set(code);
  }

  protected cancelPublish(): void {
    this.publishing.set(null);
  }

  protected async publish(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    const code = this.publishing();
    if (!tenantId || code === null || !this.canPublish()) {
      return;
    }
    const target = this.target();
    const input: AuthorPolicyInput = {
      actionCode: code,
      scopeType: target.scopeType,
      brandId: target.brandId,
      locationId: target.locationId,
      thresholdDescription: this.threshold().trim(),
      requiredApproverCapability: this.capability().trim(),
      // A datetime-local value is the operator's wall clock; the server wants an instant.
      validFrom: this.validFrom() === '' ? null : new Date(this.validFrom()).toISOString(),
      reason: this.reason().trim(),
    };
    this.saving.set(true);
    this.formError.set(null);
    try {
      await this.api.author(tenantId, input);
      this.publishing.set(null);
      this.notice.set('who.notice.published');
      await this.load(tenantId);
    } catch (error) {
      this.formError.set(this.describe(error));
    } finally {
      this.saving.set(false);
    }
  }

  // ------------------------------------------------------------------------------------- end

  protected openEnd(version: PolicyVersion): void {
    this.publishing.set(null);
    this.endReason.set('');
    this.formError.set(null);
    this.notice.set(null);
    this.ending.set(version);
  }

  protected cancelEnd(): void {
    this.ending.set(null);
  }

  protected endingRow(): ActionRow | null {
    const version = this.ending();
    return version === null
      ? null
      : (this.rows().find((r) => r.code === version.actionCode) ?? null);
  }

  protected async end(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    const version = this.ending();
    if (!tenantId || version === null || !this.canEnd()) {
      return;
    }
    this.saving.set(true);
    this.formError.set(null);
    try {
      await this.api.endDate(tenantId, version.id, this.endReason().trim());
      this.ending.set(null);
      this.notice.set('who.notice.ended');
      await this.load(tenantId);
    } catch (error) {
      this.formError.set(this.describe(error));
    } finally {
      this.saving.set(false);
    }
  }

  // ----------------------------------------------------------------------------------- loading

  protected retry(): void {
    const tenantId = this.tenant.tenantId();
    if (tenantId) {
      void this.load(tenantId);
    }
  }

  private async load(tenantId: string): Promise<void> {
    if (this.coverage().length === 0) {
      this.state.set('loading');
    }
    try {
      this.coverage.set(await this.api.coverage(tenantId));
      this.loadError.set(null);
      this.state.set('ready');
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.state.set('denied');
        return;
      }
      this.loadError.set(this.describe(error));
      this.state.set('error');
    }
  }

  private rowOf(entry: PolicyCoverage): ActionRow {
    const key: MessageKey | null = approvalActionLabelKey(entry.actionCode);
    const governed = entry.configuredScopes.some((version) => version.governsNow);
    return {
      coverage: entry,
      code: entry.actionCode,
      label: key === null ? entry.actionCode : this.i18n.t(key),
      governed,
      blocked: entry.missingPolicyMode === 'REQUIRE_CONFIGURED_POLICY' && !entry.configuredAnywhere,
    };
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}

function shortId(id: string | null): string {
  return id === null ? '' : id.slice(0, 8);
}
