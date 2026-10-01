import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { RuleEnabledChange, RuleList, RuleListItem, RuleReorder } from '../../shared/ui/rule-list';
import { describeApiError } from '../orders/order-errors';
import { DispatchActionForm } from './dispatch-action-form';
import { DispatchConditionsForm } from './dispatch-conditions-form';
import {
  DispatchAction,
  DispatchConditions,
  DispatchOptions,
  DispatchRule,
  DispatchRulesApi,
  DispatchRulesDocument,
  DispatchRulesView,
  DispatchScope,
  DispatchUsage,
  ScopeLevel,
} from './dispatch-rules-api';
import {
  MAX_RULES,
  MODE_LABELS,
  RULE_ID_PATTERN,
  documentOf,
  newRule,
  orderRules,
  removeRule,
  replaceRule,
  sameDocument,
  setEnabled,
} from './dispatch-rules-model';
import { DispatchSimulator } from './dispatch-simulator';
import { PaymentWindowCard } from './payment-window-card';
import { SourcingTimingsCard } from './sourcing-timings-card';

const SCOPE_LABELS: Readonly<Record<ScopeLevel, MessageKey>> = {
  TENANT: 'delivery.rules.scope.TENANT',
  BRAND: 'delivery.rules.scope.BRAND',
  LOCATION: 'delivery.rules.scope.LOCATION',
};

/** How far back the per-rule order counts look. */
const USAGE_DAYS = 30;

/** The sentence `ApiError.problem.detail` joins several reasons with (`DispatchRulesAuthoringService`). */
const PROBLEM_SEPARATOR = '; ';

/**
 * IA 3.8 / ADR 0142 -- Dispatch rules.
 *
 * One ordered list of rules and a default, per scope (the whole company, a brand, a branch), in a closed
 * vocabulary: *when* an order comes from these sources, into these zones, within this preparation time and
 * distance, at these hours, prepaid or not -- *then* send it this way (own couriers first or partners first),
 * to these partners in this order or whichever quotes the cheapest, starting the search at this moment. The
 * first rule that matches an order decides, so the order of the list is the meaning and {@link RuleList}
 * -- the shared priority list -- is where it is edited.
 *
 * **What an edit does and does not do.** A published document is read once, when a delivery plan is created,
 * and the decision is stored on the plan. Editing here therefore never reroutes an order already in flight;
 * the screen says so. Nothing is saved until Publish: the draft is held in this component, the simulator can
 * test it before it is published, and a save is refused (`STALE_VERSION`) when someone else published first.
 *
 * **Scope.** Publishing at a level *replaces* the document for that level -- it never merges with an
 * ancestor's (ADR 0030) -- and the screen shows which level is in force and whether this one has rules of
 * its own.
 *
 * **What is deliberately not here:** a booking race with cancellation of the losers (ADR 0014 rejected it and
 * ADR 0142 keeps it closed), and grouping until how a courier is paid for one run of several orders is
 * decided -- the action form shows it locked, with the reason.
 */
@Component({
  selector: 'q-dispatch-rules-page',
  imports: [
    TPipe,
    RuleList,
    DispatchConditionsForm,
    DispatchActionForm,
    DispatchSimulator,
    SourcingTimingsCard,
    PaymentWindowCard,
  ],
  templateUrl: './dispatch-rules-page.html',
  styleUrl: './dispatch-rules-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DispatchRulesPage implements OnInit {
  private readonly api = inject(DispatchRulesApi);
  private readonly location = inject(CurrentLocation);
  private readonly capabilities = inject(SessionCapabilities);
  protected readonly i18n = inject(I18n);

  protected readonly scopeLevels: readonly ScopeLevel[] = ['TENANT', 'BRAND', 'LOCATION'];
  protected readonly scopeLevel = signal<ScopeLevel>('LOCATION');

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);

  /** What the server last said is in force at this scope. */
  protected readonly view = signal<DispatchRulesView | null>(null);
  /** The unsaved document the operator is editing. */
  protected readonly draft = signal<DispatchRulesDocument | null>(null);
  protected readonly options = signal<DispatchOptions | null>(null);
  protected readonly usage = signal<DispatchUsage | null>(null);

  protected readonly selectedId = signal<string | null>(null);
  protected readonly reason = signal('');
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly problems = signal<readonly string[]>([]);
  protected readonly stale = signal(false);
  protected readonly notice = signal<string | null>(null);

  /** The signed-in operator's own branch, brand and company: the ids every level of the ladder is built from. */
  protected readonly ids = signal<LocationScope | null>(null);

  protected readonly canWrite = computed(() =>
    this.capabilities.has('DELIVERY_DISPATCH_RULES_WRITE'),
  );
  protected readonly canManagePaymentWindow = computed(() =>
    this.capabilities.has('ORDER_PAYMENT_WINDOW_MANAGE'),
  );

  /** The call scope for the level the operator is looking at. */
  protected readonly scope = computed<DispatchScope | null>(() => {
    const ids = this.ids();
    if (!ids) {
      return null;
    }
    const level = this.scopeLevel();
    return {
      tenantId: ids.tenantId,
      ...(level !== 'TENANT' ? { brandId: ids.brandId } : {}),
      ...(level === 'LOCATION' ? { locationId: ids.locationId } : {}),
    };
  });
  protected readonly dirty = computed(() => {
    const view = this.view();
    const draft = this.draft();
    return view !== null && draft !== null && !sameDocument(draft, view);
  });

  protected readonly selected = computed<DispatchRule | null>(() => {
    const id = this.selectedId();
    return this.draft()?.rules.find((rule) => rule.id === id) ?? null;
  });

  /** A rule is new, and so its id still editable, until a published version holds it. */
  protected readonly selectedIsPublished = computed(() => {
    const id = this.selectedId();
    return this.view()?.rules.some((rule) => rule.id === id) ?? false;
  });

  protected readonly listItems = computed<readonly RuleListItem[]>(() => {
    const draft = this.draft();
    const usage = this.usage();
    return (draft?.rules ?? []).map((rule) => ({
      id: rule.id,
      label: rule.name.trim() === '' ? rule.id : rule.name,
      description: this.describe(rule, usage),
      enabled: rule.enabled,
    }));
  });

  protected readonly defaultUsage = computed(() => this.usageText(this.usage(), 'DEFAULT'));

  protected readonly idProblem = computed<boolean>(() => {
    const rule = this.selected();
    return rule !== null && !isWellFormedUniqueId(rule.id, this.draft()?.rules ?? []);
  });

  protected readonly canPublish = computed(
    () =>
      this.canWrite() &&
      this.dirty() &&
      this.reason().trim() !== '' &&
      !this.saving() &&
      !this.stale() &&
      !this.hasLocalProblem(),
  );

  protected readonly atLimit = computed(() => (this.draft()?.rules.length ?? 0) >= MAX_RULES);

  async ngOnInit(): Promise<void> {
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.loading.set(false);
      return;
    }
    this.ids.set(scope);
    await this.load();
  }

  protected scopeLabel(level: ScopeLevel): MessageKey {
    return SCOPE_LABELS[level];
  }

  /** The level whose document is in force, in the operator's words ("This brand"), for the "in force from" line. */
  protected winningLabel(view: DispatchRulesView): string {
    return view.winningScope ? this.i18n.t(SCOPE_LABELS[view.winningScope]) : '';
  }

  protected async selectScope(level: ScopeLevel): Promise<void> {
    if (level === this.scopeLevel()) {
      return;
    }
    this.scopeLevel.set(level);
    await this.load();
  }

  // ---------------------------------------------------------------- loading

  protected async load(): Promise<void> {
    const scope = this.scope();
    if (!scope) {
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    this.denied.set(false);
    this.saveError.set(null);
    this.problems.set([]);
    this.stale.set(false);
    this.notice.set(null);
    try {
      const [loaded, options, usage] = await Promise.all([
        this.api.rules(scope),
        this.api.options(scope),
        this.api.usage(scope, USAGE_DAYS),
      ]);
      this.adopt(loaded.value);
      this.options.set(options);
      this.usage.set(usage);
      this.selectedId.set(null);
      this.reason.set('');
    } catch (failure) {
      if (failure instanceof ApiError && failure.code === 'INSUFFICIENT_CAPABILITY') {
        // The section is open to anyone who can see the dispatch board; the rules are a document of their
        // own with their own grant, and "you may not read this" is not a failure to retry.
        this.denied.set(true);
      } else {
        this.loadError.set(this.describeFailure(failure));
      }
    } finally {
      this.loading.set(false);
    }
  }

  private adopt(view: DispatchRulesView): void {
    this.view.set(view);
    this.draft.set(documentOf(view));
  }

  // --------------------------------------------------------------- editing

  protected select(id: string): void {
    this.selectedId.set(id);
  }

  protected addRule(): void {
    const draft = this.draft();
    if (!draft || this.atLimit()) {
      return;
    }
    const created = newRule(
      draft.rules.map((rule) => rule.id),
      this.i18n.t('delivery.rules.newName'),
    );
    this.draft.set({ ...draft, rules: [...draft.rules, created] });
    this.selectedId.set(created.id);
    this.notice.set(null);
  }

  protected deleteSelected(): void {
    const draft = this.draft();
    const id = this.selectedId();
    if (!draft || id === null) {
      return;
    }
    this.draft.set(removeRule(draft, id));
    this.selectedId.set(null);
  }

  protected reorder(ids: RuleReorder): void {
    const draft = this.draft();
    if (draft) {
      this.draft.set(orderRules(draft, ids));
      this.notice.set(null);
    }
  }

  protected toggleEnabled(change: RuleEnabledChange): void {
    const draft = this.draft();
    if (draft) {
      this.draft.set(setEnabled(draft, change.id, change.enabled));
      this.notice.set(null);
    }
  }

  protected rename(value: string): void {
    this.patchSelected((rule) => ({ ...rule, name: value }));
  }

  protected retype(value: string): void {
    const draft = this.draft();
    const rule = this.selected();
    if (!draft || !rule) {
      return;
    }
    this.draft.set(replaceRule(draft, rule.id, { ...rule, id: value }));
    this.selectedId.set(value);
  }

  protected setConditions(when: DispatchConditions): void {
    this.patchSelected((rule) => ({ ...rule, when }));
  }

  protected setRuleAction(then: DispatchAction): void {
    this.patchSelected((rule) => ({ ...rule, then }));
  }

  protected setDefault(action: DispatchAction): void {
    const draft = this.draft();
    if (draft) {
      this.draft.set({ ...draft, default: action });
      this.notice.set(null);
    }
  }

  protected discard(): void {
    const view = this.view();
    if (view) {
      this.adopt(view);
      this.selectedId.set(null);
      this.reason.set('');
      this.saveError.set(null);
      this.problems.set([]);
    }
  }

  private patchSelected(patch: (rule: DispatchRule) => DispatchRule): void {
    const draft = this.draft();
    const rule = this.selected();
    if (!draft || !rule) {
      return;
    }
    this.draft.set(replaceRule(draft, rule.id, patch(rule)));
    this.notice.set(null);
  }

  // -------------------------------------------------------------- publishing

  protected async publish(): Promise<void> {
    const scope = this.scope();
    const draft = this.draft();
    const view = this.view();
    if (!scope || !draft || !view || !this.canPublish()) {
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    this.problems.set([]);
    try {
      const published = await this.api.publishRules(
        scope,
        draft,
        this.reason().trim(),
        view.versionAtScope,
      );
      this.adopt(published);
      this.reason.set('');
      this.selectedId.set(null);
      this.notice.set(
        this.i18n.t('delivery.rules.published', { version: published.policyVersion }),
      );
      this.usage.set(await this.api.usage(scope, USAGE_DAYS));
    } catch (failure) {
      this.handlePublishFailure(failure);
    } finally {
      this.saving.set(false);
    }
  }

  private handlePublishFailure(failure: unknown): void {
    if (failure instanceof ApiError) {
      if (failure.code === 'STALE_VERSION') {
        this.stale.set(true);
        return;
      }
      if (failure.code === 'VALIDATION_FAILED' && failure.problem?.detail) {
        // The server names every reason a document may not be published, joined into one sentence.
        this.problems.set(
          failure.problem.detail.split(PROBLEM_SEPARATOR).filter((p) => p.trim() !== ''),
        );
        return;
      }
    }
    this.saveError.set(this.describeFailure(failure));
  }

  // ---------------------------------------------------------------- display

  private describe(rule: DispatchRule, usage: DispatchUsage | null): string {
    const parts = [this.i18n.t(MODE_LABELS[rule.then.mode])];
    const used = this.usageText(usage, rule.id);
    if (used) {
      parts.push(used);
    }
    return parts.join(' · ');
  }

  private usageText(usage: DispatchUsage | null, ruleId: string): string | null {
    if (!usage) {
      return null;
    }
    const plans = usage.perRule.find((entry) => entry.ruleId === ruleId)?.plans ?? 0;
    return plans > 0
      ? this.i18n.t('delivery.rules.usage.plans', { count: plans, days: usage.days })
      : this.i18n.t('delivery.rules.usage.none', { days: usage.days });
  }

  private hasLocalProblem(): boolean {
    const draft = this.draft();
    if (!draft) {
      return true;
    }
    return draft.rules.some((rule) => !isWellFormedUniqueId(rule.id, draft.rules));
  }

  private describeFailure(failure: unknown): string {
    return failure instanceof ApiError
      ? describeApiError(failure, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}

/** Whether a rule id is well formed and not used twice in the list. */
function isWellFormedUniqueId(id: string, rules: readonly DispatchRule[]): boolean {
  return RULE_ID_PATTERN.test(id) && rules.filter((rule) => rule.id === id).length === 1;
}
