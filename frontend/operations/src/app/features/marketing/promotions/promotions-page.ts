import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { BrandScope } from '../../../core/api/catalog-paths';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { formatDateTime } from '../../../core/format/datetime';
import { formatMoney } from '../../../core/format/money';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ConfirmDialog } from '../../../shared/ui/confirm-dialog';
import {
  RuleEnabledChange,
  RuleList,
  RuleListItem,
  RuleReorder,
} from '../../../shared/ui/rule-list';
import { describeApiError } from '../../orders/order-errors';
import { REPORTS_PLACEHOLDER_TIME_ZONE } from '../../reports/reports-filter-state';
import {
  PromotionDraft,
  bodyFromDraft,
  buildConditionCatalogue,
  draftFromView,
  draftProblems,
  emptyDraft,
} from './promotion-draft';
import { PromotionEditor } from './promotion-editor';
import { PromotionReferences } from './promotion-references';
import { PromotionSimulator, ReplayChoice } from './promotion-simulator';
import {
  PromotionRedemption,
  PromotionStatus,
  PromotionView,
  PromotionsApi,
  ValidationIssue,
  ValidationResult,
} from './promotions-api';

type Pane = 'rule' | 'simulate' | 'history' | 'redemptions';

/** One stacking group as the list shows it: live promotions only, highest priority first. */
interface GroupView {
  readonly name: string;
  readonly promotions: readonly PromotionView[];
  readonly items: readonly RuleListItem[];
}

/**
 * Marketing §6.1 Promotions (ADR 0140, gap-map row `6.1`): the rule-engine
 * authoring screen over `PromotionController`.
 *
 * A promotion is **data in a closed vocabulary**: AND-only conditions
 * (`q-condition-builder`) and typed actions, authored as a draft, **validated** by
 * the server's rule checker, **activated** (which asks a second person above the
 * ADR 0030 thresholds and then answers 202), and later suspended, resumed or
 * archived. An active promotion is never edited in place: the page locks it and
 * says to suspend it first.
 *
 * **`q-rule-list` is the priority order, one list per stacking group.** Priority
 * only breaks ties, benefit comes first, and the engine compares promotions within
 * a group, so the order is meaningful only inside one. Dragging rewrites that
 * group's priorities (`PUT .../promotions/priority`), the one change a live
 * promotion accepts without going back to draft. The row's switch is the lifecycle
 * made quick: on activates a validated promotion (and may land on "waiting for
 * approval"), off suspends a live one, and a refusal reloads the list so the
 * switch does not stay lit on a promotion that is not.
 *
 * **`q-rule-simulator` is the real engine.** The simulator tab sends a synthetic
 * cart to `POST .../promotions/simulate` and shows the decision trace for every
 * promotion in the brand, so a marketer sees why an offer did or did not apply
 * before it reaches a guest. See {@link PromotionSimulator}.
 *
 * **Every id the screen sends was picked from a list the server gave it**
 * ({@link PromotionReferences}); nothing is typed by hand into a rule.
 */
@Component({
  selector: 'q-promotions-page',
  imports: [TPipe, RuleList, PromotionEditor, PromotionSimulator, ConfirmDialog, RouterLink],
  providers: [PromotionReferences],
  templateUrl: './promotions-page.html',
  styleUrl: './promotions-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PromotionsPage implements OnInit {
  private readonly api = inject(PromotionsApi);
  private readonly brand = inject(CurrentBrand);
  protected readonly refs = inject(PromotionReferences);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly promotions = signal<readonly PromotionView[]>([]);

  /** Bumped to re-create the lists, so a switch the server refused does not stay lit. */
  protected readonly listEpoch = signal(0);

  // ------------------------------------------------------------- the open rule

  protected readonly selectedId = signal<string | null>(null);
  protected readonly creating = signal(false);
  protected readonly detail = signal<PromotionView | null>(null);
  protected readonly draft = signal<PromotionDraft>(emptyDraft());
  /** The body the open rule had when it was loaded, to tell unsaved work from none. */
  private readonly baseline = signal<string>('');
  protected readonly unsupported = signal<readonly string[]>([]);
  protected readonly pane = signal<Pane>('rule');

  protected readonly saving = signal(false);
  protected readonly acting = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly notice = signal<MessageKey | null>(null);
  protected readonly validation = signal<ValidationResult | null>(null);
  protected readonly pendingApprovalId = signal<string | null>(null);

  protected readonly activationOpen = signal(false);
  protected readonly activationReason = signal('');
  protected readonly archiveOpen = signal(false);
  /** Where to go once the operator has agreed to discard unsaved edits. */
  private readonly pendingMove = signal<(() => void) | null>(null);
  protected readonly discardOpen = computed(() => this.pendingMove() !== null);

  protected readonly redemptions = signal<readonly PromotionRedemption[]>([]);
  protected readonly redemptionsState = signal<'idle' | 'loading' | 'ready' | 'error'>('idle');

  // ----------------------------------------------------------------- derived

  protected readonly catalogue = computed(() => buildConditionCatalogue(this.refs.lookups()));

  protected readonly groups = computed<readonly GroupView[]>(() => {
    const live = this.promotions().filter((promotion) => promotion.status !== 'ARCHIVED');
    const names = [...new Set(live.map((promotion) => promotion.stackingGroup))].sort();
    return names.map((name) => {
      const inGroup = live
        .filter((promotion) => promotion.stackingGroup === name)
        .sort((a, b) => b.priority - a.priority || a.code.localeCompare(b.code));
      return {
        name,
        promotions: inGroup,
        items: inGroup.map((promotion): RuleListItem => ({
          id: promotion.promotionId,
          label: promotion.name,
          description: this.describe(promotion),
          enabled: promotion.status === 'ACTIVE',
        })),
      };
    });
  });

  protected readonly archived = computed(() =>
    this.promotions().filter((promotion) => promotion.status === 'ARCHIVED'),
  );

  protected readonly stackingGroups = computed(() => this.groups().map((group) => group.name));

  protected readonly dirty = computed(
    () => this.hasOpenRule() && JSON.stringify(bodyFromDraft(this.draft())) !== this.baseline(),
  );

  protected readonly hasOpenRule = computed(() => this.creating() || this.detail() !== null);

  protected readonly status = computed<PromotionStatus | null>(() => this.detail()?.status ?? null);

  /** An active or retired promotion is shown, not edited: suspend it first. */
  protected readonly locked = computed(
    () => this.status() === 'ACTIVE' || this.status() === 'ARCHIVED',
  );

  protected readonly lockedHint = computed(() =>
    this.status() === 'ACTIVE'
      ? this.i18n.t('marketing.promotions.locked.active')
      : this.status() === 'ARCHIVED'
        ? this.i18n.t('marketing.promotions.locked.archived')
        : null,
  );

  /** The unsaved draft as a request, if it is complete enough to try in the simulator. */
  protected readonly candidate = computed(() =>
    this.hasOpenRule() && draftProblems(this.draft()).length === 0
      ? bodyFromDraft(this.draft())
      : null,
  );

  protected readonly replay = computed<ReplayChoice | null>(() => {
    const open = this.detail();
    return open && open.versions.length > 0
      ? {
          promotionId: open.promotionId,
          code: open.code,
          versions: open.versions.map((version) => version.definitionVersion),
        }
      : null;
  });

  protected readonly canValidate = computed(
    () => (this.status() === 'DRAFT' || this.status() === 'VALIDATED') && !this.dirty(),
  );
  protected readonly canActivate = computed(() => this.status() === 'VALIDATED' && !this.dirty());
  protected readonly canSuspend = computed(() => this.status() === 'ACTIVE');
  protected readonly canResume = computed(() => this.status() === 'SUSPENDED' && !this.dirty());
  protected readonly canArchive = computed(
    () => this.detail() !== null && this.status() !== 'ARCHIVED',
  );

  protected readonly panes: readonly { readonly id: Pane; readonly labelKey: MessageKey }[] = [
    { id: 'rule', labelKey: 'marketing.promotions.pane.rule' },
    { id: 'simulate', labelKey: 'marketing.promotions.pane.simulate' },
    { id: 'history', labelKey: 'marketing.promotions.pane.history' },
    { id: 'redemptions', labelKey: 'marketing.promotions.pane.redemptions' },
  ];

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    await this.brand.ensureLoaded();
    const scope = this.brand.scope();
    if (!scope) {
      this.denied.set(this.brand.denied());
      this.loading.set(false);
      return;
    }
    try {
      this.promotions.set(await this.api.list(scope));
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(this.describeError(error));
      }
    } finally {
      this.loading.set(false);
    }
    if (!this.denied()) {
      // Best-effort lookups, in the background: the list is usable without them.
      void this.refs.load(scope);
    }
  }

  private async reloadList(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    try {
      this.promotions.set(await this.api.list(scope));
    } catch (error) {
      this.actionError.set(this.describeError(error));
    }
  }

  // --------------------------------------------------------------- rendering

  protected statusLabelKey(status: string): MessageKey {
    return `marketing.promotions.status.${status}` as MessageKey;
  }

  protected scopeLabelKey(scope: string): MessageKey {
    return `marketing.promotions.scope.${scope}` as MessageKey;
  }

  protected kindLabelKey(kind: string): MessageKey {
    return `marketing.promotions.kind.${kind}` as MessageKey;
  }

  private describe(promotion: PromotionView): string {
    return this.i18n.t('marketing.promotions.row.description', {
      code: promotion.code,
      status: this.i18n.t(this.statusLabelKey(promotion.status)),
      scope: this.i18n.t(this.scopeLabelKey(promotion.scope)),
    });
  }

  protected issueText(issue: ValidationIssue): string {
    const key = `marketing.promotions.issue.${issue.code}` as MessageKey;
    const text = this.i18n.t(key);
    // Unknown to this build (a newer server): the catalogue has no entry, so `t` answers with
    // nothing at all (or, cold, the raw key). The server's own sentence, in English, beats both.
    return !text || text === key ? issue.message : text;
  }

  protected issuePlace(issue: ValidationIssue): string | null {
    return issue.sequence === null
      ? null
      : this.i18n.t('marketing.promotions.issue.place', { n: issue.sequence });
  }

  protected formatWhen(iso: string): string {
    const zone = this.refs.locations()[0]?.timezone ?? REPORTS_PLACEHOLDER_TIME_ZONE;
    return formatDateTime(new Date(iso), zone);
  }

  protected formatMoneyValue(amountMinor: number, currency: string): string {
    return formatMoney({ amountMinor, currency }, this.i18n.locale(), { withUnit: true });
  }

  protected readonly approvalsLink = '/staff/approvals';

  /** The brand the screen works on, for the simulator panel's own request. */
  protected brandScope(): BrandScope | null {
    return this.brand.scope();
  }

  // ------------------------------------------------------------- opening rules

  /** Runs `move` now, or after the operator agrees to throw away what they have not saved. */
  private guarded(move: () => void): void {
    if (this.dirty()) {
      this.pendingMove.set(move);
      return;
    }
    move();
  }

  protected confirmDiscard(): void {
    const move = this.pendingMove();
    this.pendingMove.set(null);
    move?.();
  }

  protected cancelDiscard(): void {
    this.pendingMove.set(null);
  }

  protected select(promotionId: string): void {
    if (promotionId === this.selectedId() && !this.creating()) {
      return;
    }
    this.guarded(() => void this.open(promotionId));
  }

  protected startCreate(): void {
    this.guarded(() => {
      this.clearOutcome();
      this.selectedId.set(null);
      this.detail.set(null);
      this.creating.set(true);
      this.unsupported.set([]);
      this.pane.set('rule');
      const fresh = emptyDraft();
      this.draft.set(fresh);
      this.baseline.set(JSON.stringify(bodyFromDraft(fresh)));
    });
  }

  protected close(): void {
    this.guarded(() => {
      this.clearOutcome();
      this.selectedId.set(null);
      this.detail.set(null);
      this.creating.set(false);
    });
  }

  private clearOutcome(): void {
    this.actionError.set(null);
    this.notice.set(null);
    this.validation.set(null);
    this.pendingApprovalId.set(null);
  }

  private async open(promotionId: string): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.clearOutcome();
    this.creating.set(false);
    this.selectedId.set(promotionId);
    this.pane.set('rule');
    try {
      this.show(await this.api.detail(scope, promotionId));
    } catch (error) {
      this.actionError.set(this.describeError(error));
    }
  }

  /** Puts a stored promotion into the editor and remembers what it looked like. */
  private show(view: PromotionView): void {
    const { draft, unsupported } = draftFromView(view, this.catalogue());
    this.detail.set(view);
    this.selectedId.set(view.promotionId);
    this.draft.set(draft);
    this.unsupported.set(unsupported);
    this.baseline.set(JSON.stringify(bodyFromDraft(draft)));
    this.redemptions.set([]);
    this.redemptionsState.set('idle');
  }

  protected selectPane(pane: Pane): void {
    this.pane.set(pane);
    if (pane === 'redemptions') {
      void this.loadRedemptions();
    }
  }

  private async loadRedemptions(): Promise<void> {
    const scope = this.brand.scope();
    const open = this.detail();
    if (!scope || !open) {
      return;
    }
    this.redemptionsState.set('loading');
    try {
      this.redemptions.set(await this.api.redemptions(scope, open.promotionId));
      this.redemptionsState.set('ready');
    } catch (error) {
      this.actionError.set(this.describeError(error));
      this.redemptionsState.set('error');
    }
  }

  protected async changeMenuBranch(locationId: string): Promise<void> {
    const scope = this.brand.scope();
    if (scope) {
      await this.refs.loadVariants(scope, locationId);
    }
  }

  // ------------------------------------------------------------------- saving

  protected async save(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || draftProblems(this.draft()).length > 0 || this.unsupported().length > 0) {
      return;
    }
    const body = bodyFromDraft(this.draft());
    this.saving.set(true);
    this.clearOutcome();
    try {
      const open = this.detail();
      const saved = open
        ? await this.api.update(scope, open.promotionId, body, open.version)
        : await this.api.create(scope, body);
      this.creating.set(false);
      this.show(await this.api.detail(scope, saved.promotionId));
      this.notice.set('marketing.promotions.notice.saved');
      await this.reloadList();
    } catch (error) {
      this.actionError.set(this.describeError(error));
      await this.refreshOpenAfterConflict(error);
    } finally {
      this.saving.set(false);
    }
  }

  // ---------------------------------------------------------------- lifecycle

  private async lifecycle(
    name: string,
    run: (scope: BrandScope, open: PromotionView) => Promise<void>,
  ): Promise<void> {
    const scope = this.brand.scope();
    const open = this.detail();
    if (!scope || !open) {
      return;
    }
    this.acting.set(name);
    this.actionError.set(null);
    this.notice.set(null);
    try {
      await run(scope, open);
    } catch (error) {
      this.actionError.set(this.describeError(error));
      await this.refreshOpenAfterConflict(error);
    } finally {
      this.acting.set(null);
    }
  }

  /** After a stale version or a transition the server refused, show the promotion as it now is. */
  private async refreshOpenAfterConflict(error: unknown): Promise<void> {
    if (!(error instanceof ApiError) || (error.status !== 409 && error.status !== 412)) {
      return;
    }
    const scope = this.brand.scope();
    const open = this.detail();
    if (!scope || !open) {
      return;
    }
    try {
      this.show(await this.api.detail(scope, open.promotionId));
      await this.reloadList();
    } catch {
      // The original error stays on screen.
    }
  }

  protected validate(): Promise<void> {
    return this.lifecycle('validate', async (scope, open) => {
      const result = await this.api.validate(scope, open.promotionId, open.version);
      this.validation.set(result);
      this.show(await this.api.detail(scope, open.promotionId));
      this.notice.set(
        result.valid
          ? 'marketing.promotions.notice.validated'
          : 'marketing.promotions.notice.refused',
      );
      await this.reloadList();
    });
  }

  protected openActivation(): void {
    this.activationReason.set('');
    this.activationOpen.set(true);
  }

  protected closeActivation(): void {
    this.activationOpen.set(false);
  }

  protected async confirmActivation(): Promise<void> {
    this.activationOpen.set(false);
    await this.lifecycle('activate', async (scope, open) => {
      const reason = this.activationReason().trim();
      const result = await this.api.activate(
        scope,
        open.promotionId,
        open.version,
        reason === '' ? null : reason,
      );
      if (result.outcome === 'PENDING_APPROVAL') {
        this.pendingApprovalId.set(result.approvalRequestId);
        this.notice.set('marketing.promotions.notice.pending');
      } else {
        this.pendingApprovalId.set(null);
        this.notice.set('marketing.promotions.notice.activated');
      }
      this.show(await this.api.detail(scope, open.promotionId));
      await this.reloadList();
    });
  }

  protected suspend(): Promise<void> {
    return this.lifecycle('suspend', async (scope, open) => {
      await this.api.suspend(scope, open.promotionId, open.version);
      this.show(await this.api.detail(scope, open.promotionId));
      this.notice.set('marketing.promotions.notice.suspended');
      await this.reloadList();
    });
  }

  protected resume(): Promise<void> {
    return this.lifecycle('resume', async (scope, open) => {
      await this.api.resume(scope, open.promotionId, open.version);
      this.show(await this.api.detail(scope, open.promotionId));
      this.notice.set('marketing.promotions.notice.resumed');
      await this.reloadList();
    });
  }

  protected openArchive(): void {
    this.archiveOpen.set(true);
  }

  protected closeArchive(): void {
    this.archiveOpen.set(false);
  }

  protected async confirmArchive(): Promise<void> {
    this.archiveOpen.set(false);
    await this.lifecycle('archive', async (scope, open) => {
      await this.api.archive(scope, open.promotionId, open.version);
      this.show(await this.api.detail(scope, open.promotionId));
      this.notice.set('marketing.promotions.notice.archived');
      await this.reloadList();
    });
  }

  // ------------------------------------------------------- q-rule-list gestures

  protected async onReorder(group: GroupView, order: RuleReorder): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.actionError.set(null);
    this.notice.set(null);
    try {
      await this.api.reorder(scope, group.name, order);
      this.notice.set('marketing.promotions.notice.reordered');
    } catch (error) {
      this.actionError.set(this.describeError(error));
    }
    // The server's priorities are the truth; the list re-reads them either way.
    await this.reloadList();
    this.listEpoch.update((value) => value + 1);
    const open = this.detail();
    if (open && order.includes(open.promotionId) && !this.dirty()) {
      await this.reopenQuietly(open.promotionId);
    }
  }

  private async reopenQuietly(promotionId: string): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    try {
      this.show(await this.api.detail(scope, promotionId));
    } catch {
      // Leave what is on screen.
    }
  }

  protected async onEnabledChange(change: RuleEnabledChange): Promise<void> {
    const target = this.promotions().find((promotion) => promotion.promotionId === change.id);
    if (!target) {
      return;
    }
    // The switch only ever makes sense from a state that has a lifecycle call for it.
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.actionError.set(null);
    this.notice.set(null);
    if (this.dirty() && this.selectedId() !== target.promotionId) {
      // Opening the other promotion would throw the edits away without asking.
      this.actionError.set(this.i18n.t('marketing.promotions.error.unsaved'));
      this.listEpoch.update((value) => value + 1);
      return;
    }
    const open = async (): Promise<void> => {
      await this.open(target.promotionId);
    };
    if (change.enabled && target.status === 'VALIDATED') {
      await open();
      this.openActivation();
    } else if (change.enabled && target.status === 'SUSPENDED') {
      await open();
      await this.resume();
    } else if (!change.enabled && target.status === 'ACTIVE') {
      await open();
      await this.suspend();
    } else {
      this.actionError.set(
        this.i18n.t(
          change.enabled
            ? 'marketing.promotions.error.toggleNeedsValidation'
            : 'marketing.promotions.error.toggleNotLive',
        ),
      );
    }
    this.listEpoch.update((value) => value + 1);
  }

  private describeError(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
