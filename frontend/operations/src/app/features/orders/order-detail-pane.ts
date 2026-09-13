import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { Versioned } from '../../core/api/aggregate-version';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { TimeZone, formatDateTime } from '../../core/format/datetime';
import { LatenessPolicy, PLATFORM_DEFAULT_LATENESS_POLICY } from '../../core/lateness-policy';
import { LatenessPolicyApi } from '../../core/lateness-policy-api';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { StepItem, Steps } from '../../shared/ui/steps';
import { Timeline, TimelineEntry } from '../../shared/ui/timeline';
import { ReasonResponse, ReferenceDataApi } from '../settings/reference-data/reference-data-api';
import {
  DecisionIdRegistry,
  OrderActionResponse,
  actionLabel,
  decisionOutcomeLabel,
} from './order-actions';
import { DecisionResponse, OrderActionsApi } from './order-actions-api';
import {
  OrderAddressReveal,
  OrderDetailResponse,
  OrderLine,
  OrderTimelineEntry,
  RevisionResponse,
} from './order-detail';
import { describeApiError, mutationErrorNotice } from './order-errors';
import { OrderHandoverPanel } from './order-handover-panel';
import { orderLifecycleSteps } from './order-lifecycle-steps';
import { MoneyReconciliation, reconcileMoney } from './order-money';
import {
  outcomeKindLabel,
  outcomeSystemCategoryLabel,
  stockDispositionLabel,
  liabilityPartyLabel,
  customerRefundLabel,
} from './order-outcome-labels';
import { OrderOutcomeReasonDialog, OutcomeReasonSubmission } from './order-outcome-reason-dialog';
import {
  OrderRejectReasonDialog,
  OrderRejectSubmission,
  RejectReasonOption,
} from './order-reject-reason-dialog';
import { RejectReasonsApi } from './order-reject-reasons-api';
import { OrderRevealApi } from './order-reveal-api';
import { OrderSeverity, computeOrderSeverity, formatSeverityCaption } from './order-severity';
import { orderStatusLabel } from './order-status';

/** See `order-queue.ts`'s identical constant for why this is a fixed zone, not the browser's. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

/**
 * Fixed, English, machine-facing purpose strings for the ADR 0029 reveal
 * audit trail — not translated, the same reason `ApiError`'s message and
 * `advanceReasonCode` are not: these are read by whoever reviews the audit
 * log, not by the operator, and a purpose that changes with the UI locale
 * would fragment that log by language for no reason.
 */
const REVEAL_PURPOSE = {
  phoneCall: 'Operations console: call the customer',
  phoneCopy: 'Operations console: copy the phone number',
  address: 'Operations console: view the delivery address',
  lineNote: 'Operations console: view a line note',
} as const;

/** Which reason dialog is open, if any. */
type DialogKind = 'reject' | 'cancel' | 'complete';

/**
 * The order detail — `docs/operations-spec/orders.md` §3, docked beside the
 * queue (`orders-page.ts` explains why it is a route and not a modal).
 *
 * **What this wave builds, and what it does not.** The two-column desktop
 * layout §3.2 draws is the *full-page* screen; this application docks the
 * detail in a fixed-width column beside the queue instead (`orders-page.css`),
 * so every section here stacks in one column rather than two. Content-wise:
 * the lines table, the money panel with its §1.3 reconciliation guard, the
 * customer and address panels behind their ADR 0029 reveal calls, and the
 * commercial timeline lane are built. Комментарии (§3.6), Оплата,
 * Фискализация, Ревизии and Интеграции (§3.9-§3.11) all need tables that do
 * not exist yet (§11) and are not here. The production and delivery timeline
 * lanes render, greyed, naming the ADRs that own them (ADR 0041, ADR 0014) —
 * never silently dropped, the same rule `not-built-page.ts` follows for a
 * whole screen, applied here to two lanes of one.
 */
@Component({
  selector: 'q-order-detail-pane',
  imports: [
    TPipe,
    OrderOutcomeReasonDialog,
    OrderRejectReasonDialog,
    OrderHandoverPanel,
    Steps,
    Timeline,
  ],
  templateUrl: './order-detail-pane.html',
  styleUrl: './order-detail-pane.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderDetailPane {
  private readonly api = inject(ApiClient);
  private readonly location = inject(CurrentLocation);
  private readonly actionsApi = inject(OrderActionsApi);
  private readonly rejectReasonsApi = inject(RejectReasonsApi);
  private readonly referenceDataApi = inject(ReferenceDataApi);
  private readonly revealApi = inject(OrderRevealApi);
  private readonly latenessPolicyApi = inject(LatenessPolicyApi);
  private readonly i18n = inject(I18n);

  /** Bound from the route parameter by `withComponentInputBinding()`. */
  readonly orderId = input.required<string>();

  protected readonly loading = signal(true);
  protected readonly order = signal<Versioned<OrderDetailResponse> | null>(null);
  protected readonly notFound = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);

  protected readonly timeline = signal<readonly OrderTimelineEntry[] | null>(null);
  protected readonly timelineError = signal(false);

  /**
   * `q-timeline`'s own shape, row `X.26` — the same idea as the staff
   * activity log's event list, so the same component: a gap notice stays
   * its own row (§3.10's "hiding it hides a bug"), computed exactly as
   * before via {@link missingSequenceBefore}.
   *
   * **Wave P09.** `actor` used to be omitted here on the premise that
   * `OrderTimelineEntry.actorType` — a bare wire tag with no resolvable name
   * or subject behind it, unlike an audit event's `actorDisplay`/
   * `actorSubject` — would render nothing but the same fallback dash on
   * every row. That premise undersold `q-actor-chip`: even with no name it
   * still marks *which kind* of actor moved the order (a customer, an
   * operator, the system itself), which is exactly the fact a raw
   * `reasonCode` next to it cannot supply on its own.
   */
  protected readonly commercialTimelineEntries = computed<readonly TimelineEntry[] | null>(() => {
    const entries = this.timeline();
    if (!entries) {
      return null;
    }
    return entries.map((entry, index) => ({
      id: String(entry.sequence),
      timestamp: this.formatOccurredAt(entry.occurredAt),
      actor: { kind: entry.actorType, displayName: null, subject: null },
      title: `${this.statusLabel(entry.fromStatus)} → ${this.statusLabel(entry.toStatus)}`,
      detail: entry.reasonCode
        ? `${this.triggerLabel(entry.trigger)} · ${entry.reasonCode}`
        : this.triggerLabel(entry.trigger),
      gapBefore: this.gapLabel(entries, index),
      selectable: false,
    }));
  });

  /**
   * The order lifecycle rail (row `X.33`) — over the §3.10 timeline this
   * pane already fetches, no new endpoint. `null` while the order or the
   * timeline has not settled; the rail simply does not render then, the same
   * "greyed, never silently dropped" rule this section already follows for
   * the production/delivery lanes, applied here to render nothing rather
   * than a misleading first position.
   */
  protected readonly lifecycleSteps = computed<readonly StepItem[] | null>(() => {
    const detail = this.order();
    const entries = this.timeline();
    if (!detail || !entries) {
      return null;
    }
    return orderLifecycleSteps(detail.value.summary.status, entries, (status) =>
      this.statusLabel(status),
    );
  });

  /** §4.1/§4.3: STALE_VERSION, a lost approval race, and a refused transition all surface here. */
  protected readonly notice = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly dialog = signal<DialogKind | null>(null);
  /** Fetched before the reject dialog opens — see {@link onActionClick}'s REJECT case. */
  protected readonly rejectReasons = signal<readonly RejectReasonOption[]>([]);
  /**
   * Fetched before the cancel or completion dialog opens (§4.5/§4.6, wave
   * P09) — whichever one is currently relevant; the two dialogs never open
   * at once, so one signal is enough.
   */
  protected readonly outcomeReasons = signal<readonly ReasonResponse[]>([]);
  protected readonly headerOverflowOpen = signal(false);
  private readonly decisionIds = new DecisionIdRegistry();

  /**
   * `GET .../revisions` (§3.9, ADR 0039, row `1.2p`) — fetched on demand, not
   * on load: an order with no amendments has one revision nobody needs to
   * see, and the pane's own load already makes two calls (the order, the
   * timeline). `null` before the operator has asked; `[]` would be
   * indistinguishable from "still loading".
   */
  protected readonly revisions = signal<readonly RevisionResponse[] | null>(null);
  protected readonly revisionsOpen = signal(false);
  protected readonly revisionsLoading = signal(false);
  protected readonly revisionsError = signal(false);

  protected readonly revealedPhone = signal<string | null>(null);
  protected readonly revealingPhone = signal(false);
  protected readonly revealedAddress = signal<OrderAddressReveal | null>(null);
  protected readonly revealingAddress = signal(false);
  protected readonly revealedNotes = signal<ReadonlyMap<string, string | null>>(new Map());
  protected readonly revealingNoteFor = signal<string | null>(null);

  /** The resolved `ordering.lateness` policy (wave P06) — fetched once per order load in {@link load}. */
  private latenessPolicy: LatenessPolicy = PLATFORM_DEFAULT_LATENESS_POLICY;

  constructor() {
    // `orderId` is a signal input: navigating from one order to another under
    // the same `:orderId` route config reuses this component (Angular's
    // default `RouteReuseStrategy`) rather than recreating it, so a plain
    // `ngOnInit` would only ever see the first order. This effect is what
    // notices the second.
    effect(() => {
      const id = this.orderId();
      void this.load(id);
    });
  }

  private async load(orderId: string): Promise<void> {
    this.loading.set(true);
    this.lastError.set(null);
    this.notFound.set(false);
    this.timeline.set(null);
    this.timelineError.set(false);
    this.revealedPhone.set(null);
    this.revealedAddress.set(null);
    this.revealedNotes.set(new Map());
    this.revisions.set(null);
    this.revisionsOpen.set(false);
    this.revisionsError.set(false);

    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.order.set(null);
      this.loading.set(false);
      return;
    }
    this.denied.set(false);
    void this.loadLatenessPolicy(scope);

    try {
      const result = await firstValueFrom(
        this.api.get<OrderDetailResponse>(operationsPaths.order(scope, orderId)),
      );
      this.order.set(result);
      void this.loadTimeline(orderId);
    } catch (error) {
      if (error instanceof ApiError) {
        if (error.code === ApiErrorCode.RESOURCE_NOT_FOUND) {
          this.notFound.set(true);
          this.order.set(null);
        } else {
          this.lastError.set(error);
        }
      } else {
        throw error;
      }
    } finally {
      this.loading.set(false);
    }
  }

  private async loadTimeline(orderId: string): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      const result = await firstValueFrom(
        this.api.get<OrderTimelineEntry[]>(operationsPaths.orderTimeline(scope, orderId)),
      );
      this.timeline.set(result.value ?? []);
    } catch {
      // Non-critical panel: the order itself loaded, the timeline just did
      // not. §2.11's "previously loaded rows stay" applies here too — the
      // rest of the detail is still shown.
      this.timelineError.set(true);
    }
  }

  /**
   * The resolved `ordering.lateness` policy (wave P06), fire-and-forget
   * exactly like {@link loadTimeline}: {@link headerSeverity} already falls
   * back to {@link PLATFORM_DEFAULT_LATENESS_POLICY} while this is in
   * flight, so the header renders immediately and refines once this settles.
   */
  private async loadLatenessPolicy(scope: LocationScope): Promise<void> {
    this.latenessPolicy = await this.latenessPolicyApi.resolve(scope);
  }

  protected manualRetry(): void {
    void this.load(this.orderId());
  }

  protected statusLabel(status: string): string {
    return orderStatusLabel(status, (key) => this.i18n.t(key));
  }

  protected formatMoneyMinor(amountMinor: number, currency: string): string {
    return formatMoney({ amountMinor, currency }, this.i18n.locale(), { withUnit: true });
  }

  protected formatOccurredAt(occurredAt: string): string {
    return formatDateTime(new Date(occurredAt), PLACEHOLDER_TIME_ZONE);
  }

  protected errorMessage(error: ApiError): string {
    return describeApiError(error, (key, values) => this.i18n.t(key, values));
  }

  protected dismissNotice(): void {
    this.notice.set(null);
  }

  /** For `q-order-handover-panel`'s `[scope]` input — the template cannot reach `location` directly. */
  protected currentScope(): LocationScope | null {
    return this.location.scope();
  }

  // ------------------------------------------------------------ header severity

  protected headerSeverity(): OrderSeverity | null {
    const detail = this.order();
    if (!detail) {
      return null;
    }
    const summary = detail.value.summary;
    return computeOrderSeverity(
      {
        status: summary.status,
        createdAt: new Date(summary.createdAt),
        approvalDeadlineAt: summary.approvalDeadlineAt
          ? new Date(summary.approvalDeadlineAt)
          : null,
        fulfillmentMode: summary.fulfillmentMode,
        promisedAt: summary.promisedAt ? new Date(summary.promisedAt) : null,
        // Always null on this read today — the detail endpoint's own
        // `OrderSummaryResponse.of(OrderRow, ...)` overload projects no
        // process state (see that record's own doc). Read the real field
        // rather than hardcoding false, so this picks up a real value for
        // free the day that overload does project one.
        hasBlockedProcess: summary.processAttention === 'MANUAL_ACTION_REQUIRED',
      },
      new Date(),
      this.latenessPolicy,
    );
  }

  protected severityCaption(severity: OrderSeverity): string | null {
    return formatSeverityCaption(severity, (key, values) => this.i18n.t(key, values));
  }

  // ------------------------------------------------------------ §3.11/§4.3 actions

  /**
   * The server offers `ADVANCE`→`COMPLETED` and `COMPLETE` together whenever
   * either is legal (`OrderActionsPolicy`'s own doc explains why: a client
   * built before wave P09 — `order-queue.ts` — still works against the
   * generic entry). This pane prefers `COMPLETE`, which lets it name the
   * fulfilment-mode-appropriate reason instead of always booking
   * `DELIVERED_OWN_COURIER` (row `1.2j`), so the redundant `ADVANCE` entry is
   * filtered out here rather than rendered as a second, competing button.
   */
  private visibleActions(): readonly OrderActionResponse[] {
    const actions = this.order()?.value.summary.actions ?? [];
    const hasComplete = actions.some((action) => action.action === 'COMPLETE');
    return hasComplete
      ? actions.filter(
          (action) => !(action.action === 'ADVANCE' && action.targetStatus === 'COMPLETED'),
        )
      : actions;
  }

  protected primaryAction(): OrderActionResponse | null {
    return this.visibleActions()[0] ?? null;
  }

  protected overflowActions(): readonly OrderActionResponse[] {
    return this.visibleActions().slice(1);
  }

  protected actionLabel(action: OrderActionResponse): string {
    const mode = this.order()?.value.summary.fulfillmentMode ?? null;
    return actionLabel(
      action,
      mode,
      (key, values) => this.i18n.t(key, values),
      (status) => this.statusLabel(status),
    );
  }

  protected toggleHeaderOverflow(): void {
    this.headerOverflowOpen.update((open) => !open);
  }

  protected onActionClick(action: OrderActionResponse): void {
    this.headerOverflowOpen.set(false);
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    const orderId = detail.value.summary.orderId;
    const version = detail.value.summary.version ?? 0;

    switch (action.action) {
      case 'APPROVE':
        void this.submitDecision(
          this.actionsApi.approve(scope, orderId, this.decisionIds.idFor(orderId)),
        );
        return;
      case 'REJECT':
        void this.openRejectDialog();
        return;
      case 'CANCEL':
        void this.openCancelDialog();
        return;
      case 'COMPLETE':
        void this.startCompletion();
        return;
      case 'ADVANCE':
        if (action.targetStatus) {
          void this.submitStateMutation(
            this.actionsApi.advance(scope, orderId, action.targetStatus, version),
          );
        }
        return;
      default:
      // An action code this client does not recognise yet (§4.2: still rendered, nothing to invoke).
    }
  }

  /**
   * Fetch-before-open (wave 24): the picker needs `GET .../reject-reasons`
   * before it has anything to show, and opening on an empty list would be a
   * dialog with no way to confirm. A failed fetch surfaces through the same
   * notice band every other mutation error already uses, and the dialog
   * never opens.
   */
  private async openRejectDialog(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      this.rejectReasons.set(await this.rejectReasonsApi.list(scope));
      this.dialog.set('reject');
    } catch (error) {
      if (error instanceof ApiError) {
        this.notice.set(this.errorMessage(error));
      } else {
        throw error;
      }
    }
  }

  /**
   * Fetch-before-open (orders.md §4.5, wave P09 row `1.2k`), the same rule as
   * {@link openRejectDialog}: the picker needs the tenant's active
   * `CANCELLATION` reasons before it has anything to show. `ORDER_ACTION`s
   * cancel is now offered from `CONFIRMED` onward too — the reasoned path
   * this dialog exists for.
   */
  private async openCancelDialog(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      this.outcomeReasons.set(await this.referenceDataApi.list(scope, 'CANCELLATION'));
      this.dialog.set('cancel');
    } catch (error) {
      if (error instanceof ApiError) {
        this.notice.set(this.errorMessage(error));
      } else {
        throw error;
      }
    }
  }

  /**
   * §4.6: "where exactly one reason is valid for the order's mode... the
   * action completes without a dialog." Fetches the tenant's active
   * `COMPLETION` reasons, narrows them to this order's fulfilment mode, and
   * either submits the one unambiguous choice directly or opens the picker
   * for the operator to choose among several. An empty result (a tenant that
   * configured no completion reason for this mode) falls back to the
   * reasonless call — the server's own honest default, never invented here.
   */
  private async startCompletion(): Promise<void> {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    const mode = detail.value.summary.fulfillmentMode ?? '';
    try {
      const reasons = await this.referenceDataApi.list(scope, 'COMPLETION');
      const eligible = reasons.filter(
        (reason) =>
          !reason.allowedFulfillmentModes || reason.allowedFulfillmentModes.includes(mode),
      );
      if (eligible.length === 0) {
        void this.submitCompletion();
      } else if (eligible.length === 1) {
        void this.submitCompletion(eligible[0].id);
      } else {
        this.outcomeReasons.set(eligible);
        this.dialog.set('complete');
      }
    } catch (error) {
      if (error instanceof ApiError) {
        this.notice.set(this.errorMessage(error));
      } else {
        throw error;
      }
    }
  }

  private async submitCompletion(reasonId?: string): Promise<void> {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    const orderId = detail.value.summary.orderId;
    const version = detail.value.summary.version ?? 0;

    await this.submitStateMutation(this.actionsApi.complete(scope, orderId, version, reasonId));
  }

  protected onDialogDismiss(): void {
    this.dialog.set(null);
  }

  protected onCancelDialogConfirm(submission: OutcomeReasonSubmission): void {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    const orderId = detail.value.summary.orderId;
    const version = detail.value.summary.version ?? 0;

    void this.submitStateMutation(
      this.actionsApi.cancelWithReason(
        scope,
        orderId,
        version,
        submission.reasonId,
        submission.reasonCode,
        submission.note,
      ),
    ).finally(() => this.dialog.set(null));
  }

  protected onCompletionDialogConfirm(submission: OutcomeReasonSubmission): void {
    void this.submitCompletion(submission.reasonId).finally(() => this.dialog.set(null));
  }

  protected onRejectDialogConfirm(submission: OrderRejectSubmission): void {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    const orderId = detail.value.summary.orderId;

    void this.submitDecision(
      this.actionsApi.reject(
        scope,
        orderId,
        this.decisionIds.idFor(orderId),
        submission.reasonCode,
        submission.note,
      ),
    ).finally(() => this.dialog.set(null));
  }

  private async submitDecision(request: Observable<DecisionResponse>): Promise<void> {
    const orderId = this.order()?.value.summary.orderId;
    if (!orderId) {
      return;
    }
    this.busy.set(true);
    try {
      const result = await firstValueFrom(request);
      this.decisionIds.settle(orderId);
      if (!result.applied && result.effectiveAction) {
        this.notice.set(
          this.i18n.t('orders.action.lostRace', {
            action: this.decisionActionLabel(result.effectiveAction),
          }),
        );
      }
      await this.load(orderId);
    } catch (error) {
      this.handleMutationError(orderId, error, true);
    } finally {
      this.busy.set(false);
    }
  }

  private async submitStateMutation(request: Observable<DecisionResponse>): Promise<void> {
    const orderId = this.order()?.value.summary.orderId;
    if (!orderId) {
      return;
    }
    this.busy.set(true);
    try {
      await firstValueFrom(request);
      await this.load(orderId);
    } catch (error) {
      this.handleMutationError(orderId, error, false);
    } finally {
      this.busy.set(false);
    }
  }

  private handleMutationError(orderId: string, error: unknown, isDecision: boolean): void {
    if (!(error instanceof ApiError)) {
      throw error;
    }
    if (isDecision && !error.isRetryable) {
      this.decisionIds.settle(orderId);
    }
    const outcome = mutationErrorNotice(
      error,
      (key, values) => this.i18n.t(key, values),
      (status) => this.statusLabel(status),
    );
    this.notice.set(outcome.text);
    if (outcome.shouldReread) {
      void this.load(orderId);
    }
  }

  private decisionActionLabel(effectiveAction: string): string {
    return decisionOutcomeLabel(effectiveAction, (key) => this.i18n.t(key));
  }

  // ------------------------------------------------------------ §3.4 lines

  protected lineName(line: OrderLine): string {
    return line.productName;
  }

  protected revealedNote(lineId: string): string | null | undefined {
    // undefined = never revealed this load; null = revealed and genuinely empty.
    return this.revealedNotes().get(lineId);
  }

  protected isRevealingNote(lineId: string): boolean {
    return this.revealingNoteFor() === lineId;
  }

  protected async revealLineNote(lineId: string): Promise<void> {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    this.revealingNoteFor.set(lineId);
    try {
      const result = await firstValueFrom(
        this.revealApi.revealLineNote(
          scope,
          detail.value.summary.orderId,
          lineId,
          REVEAL_PURPOSE.lineNote,
        ),
      );
      this.revealedNotes.update((current) => {
        const next = new Map(current);
        next.set(lineId, result.note);
        return next;
      });
    } catch (error) {
      this.noticeFromRevealError(error);
    } finally {
      this.revealingNoteFor.set(null);
    }
  }

  // ------------------------------------------------------------ §1.3 money

  protected moneyReconciliation(): MoneyReconciliation | null {
    const detail = this.order();
    if (!detail) {
      return null;
    }
    return reconcileMoney(
      detail.value.lines,
      detail.value.subtotalMinor,
      detail.value.summary.totalMinor,
    );
  }

  // ------------------------------------------------------------ §3.7 customer

  protected isDeliveryOrder(): boolean {
    return this.order()?.value.summary.fulfillmentMode === 'DELIVERY';
  }

  /**
   * Click-to-call: reveals and displays the number. Copy-to-clipboard below
   * is a *separate* audited call, never a read of this one's result (§1.5).
   */
  protected async revealPhone(): Promise<void> {
    await this.fetchPhone(REVEAL_PURPOSE.phoneCall);
  }

  protected async copyPhone(): Promise<void> {
    await this.fetchPhone(REVEAL_PURPOSE.phoneCopy);
    const phone = this.revealedPhone();
    if (phone) {
      try {
        await navigator.clipboard.writeText(phone);
      } catch {
        // Clipboard access denied or unavailable (an insecure context, a
        // locked-down kiosk profile) — the number is still on screen to copy
        // by hand, which is a worse but not broken outcome.
      }
    }
  }

  private async fetchPhone(purpose: string): Promise<void> {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    this.revealingPhone.set(true);
    try {
      const result = await firstValueFrom(
        this.revealApi.revealPhone(scope, detail.value.summary.orderId, purpose),
      );
      this.revealedPhone.set(result.phone);
    } catch (error) {
      this.noticeFromRevealError(error);
    } finally {
      this.revealingPhone.set(false);
    }
  }

  // ------------------------------------------------------------ §3.8 address

  protected async revealAddress(): Promise<void> {
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    this.revealingAddress.set(true);
    try {
      const result = await firstValueFrom(
        this.revealApi.revealAddress(scope, detail.value.summary.orderId, REVEAL_PURPOSE.address),
      );
      this.revealedAddress.set(result);
    } catch (error) {
      this.noticeFromRevealError(error);
    } finally {
      this.revealingAddress.set(false);
    }
  }

  protected hasCoordinates(address: OrderAddressReveal): boolean {
    return address.latitude !== 0 || address.longitude !== 0;
  }

  private noticeFromRevealError(error: unknown): void {
    if (error instanceof ApiError) {
      this.notice.set(this.errorMessage(error));
    } else {
      throw error;
    }
  }

  // ------------------------------------------------------------ §3.10 timeline

  /**
   * §3.10: "if the sequence has a gap the panel says «пропущена запись N»,
   * because hiding it hides a bug." Reports the first sequence number missing
   * immediately before `entries[index]`, or null when there is none.
   */
  protected missingSequenceBefore(
    entries: readonly OrderTimelineEntry[],
    index: number,
  ): number | null {
    if (index === 0) {
      return null;
    }
    const previous = entries[index - 1];
    const current = entries[index];
    return current.sequence === previous.sequence + 1 ? null : previous.sequence + 1;
  }

  protected triggerLabel(trigger: string): string {
    const key = TRIGGER_LABEL_KEYS[trigger];
    // A trigger this client does not know yet renders as its own raw value —
    // the same forward-compatibility rule as an unknown status (order-status.ts).
    return key ? this.i18n.t(key) : trigger;
  }

  /** `q-timeline`'s `TimelineEntry.gapBefore`, translated from {@link missingSequenceBefore}. */
  private gapLabel(entries: readonly OrderTimelineEntry[], index: number): string | null {
    const missing = this.missingSequenceBefore(entries, index);
    return missing === null
      ? null
      : this.i18n.t('orders.detail.timeline.gap', { sequence: missing });
  }

  // ------------------------------------------------------------ §3.9 revisions (row 1.2p)

  /**
   * Toggles the revisions view, fetching on first open only — `revisions()`
   * stays populated across a collapse/re-expand within the same order so a
   * second look costs nothing.
   */
  protected async toggleRevisions(): Promise<void> {
    const opening = !this.revisionsOpen();
    this.revisionsOpen.set(opening);
    if (!opening || this.revisions() !== null) {
      return;
    }
    const detail = this.order();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    this.revisionsLoading.set(true);
    this.revisionsError.set(false);
    try {
      const result = await firstValueFrom(
        this.api.get<RevisionResponse[]>(
          operationsPaths.orderRevisions(scope, detail.value.summary.orderId),
        ),
      );
      this.revisions.set(result.value ?? []);
    } catch (error) {
      if (error instanceof ApiError) {
        this.revisionsError.set(true);
      } else {
        throw error;
      }
    } finally {
      this.revisionsLoading.set(false);
    }
  }

  // ------------------------------------------------------------ §3.4/§3.9 attribution and outcome

  protected outcomeKindLabel(kind: string): string {
    return outcomeKindLabel(kind, (key, values) => this.i18n.t(key, values));
  }

  protected outcomeCategoryLabel(category: string): string {
    return outcomeSystemCategoryLabel(category, (key, values) => this.i18n.t(key, values));
  }

  protected stockDispositionLabel(value: string): string {
    return stockDispositionLabel(value, (key, values) => this.i18n.t(key, values));
  }

  protected liabilityPartyLabel(value: string): string {
    return liabilityPartyLabel(value, (key, values) => this.i18n.t(key, values));
  }

  protected customerRefundLabel(value: string): string {
    return customerRefundLabel(value, (key, values) => this.i18n.t(key, values));
  }

  /**
   * `createdByActorType`/`acceptedByActorType` name a bare wire tag with no
   * resolvable display name behind it — same limitation the commercial
   * timeline's own doc comment names for `actorType` — so this renders the
   * type and the raw id together rather than pretending a name exists.
   * Machine principals (`"SYSTEM"`) carry no id and render as the type alone.
   */
  protected actorDisplay(
    actorType: string | null | undefined,
    actorId: string | null | undefined,
  ): string | null {
    if (!actorType) {
      return null;
    }
    return actorId ? `${actorType} · ${actorId}` : actorType;
  }
}

const TRIGGER_LABEL_KEYS: Readonly<Partial<Record<string, MessageKey>>> = {
  CHECKOUT: 'orders.detail.timeline.trigger.CHECKOUT',
  APPROVAL_DECISION: 'orders.detail.timeline.trigger.APPROVAL_DECISION',
  APPROVAL_TIMEOUT: 'orders.detail.timeline.trigger.APPROVAL_TIMEOUT',
  PAYMENT_RESULT: 'orders.detail.timeline.trigger.PAYMENT_RESULT',
  OPERATIONS_ACTION: 'orders.detail.timeline.trigger.OPERATIONS_ACTION',
  KITCHEN_PROGRESS: 'orders.detail.timeline.trigger.KITCHEN_PROGRESS',
  CUSTOMER_ACTION: 'orders.detail.timeline.trigger.CUSTOMER_ACTION',
  SYSTEM: 'orders.detail.timeline.trigger.SYSTEM',
};
