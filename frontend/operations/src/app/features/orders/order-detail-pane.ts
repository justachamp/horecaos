import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { Versioned } from '../../core/api/aggregate-version';
import { operationsPaths } from '../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { TimeZone, formatDateTime } from '../../core/format/datetime';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
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
} from './order-detail';
import { describeApiError, mutationErrorNotice } from './order-errors';
import { MoneyReconciliation, reconcileMoney } from './order-money';
import { OrderReasonDialog, OrderReasonSubmission } from './order-reason-dialog';
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
type DialogKind = 'reject' | 'cancel';

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
  imports: [TPipe, OrderReasonDialog],
  templateUrl: './order-detail-pane.html',
  styleUrl: './order-detail-pane.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderDetailPane {
  private readonly api = inject(ApiClient);
  private readonly location = inject(CurrentLocation);
  private readonly actionsApi = inject(OrderActionsApi);
  private readonly revealApi = inject(OrderRevealApi);
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

  /** §4.1/§4.3: STALE_VERSION, a lost approval race, and a refused transition all surface here. */
  protected readonly notice = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly dialog = signal<DialogKind | null>(null);
  protected readonly headerOverflowOpen = signal(false);
  private readonly decisionIds = new DecisionIdRegistry();

  protected readonly revealedPhone = signal<string | null>(null);
  protected readonly revealingPhone = signal(false);
  protected readonly revealedAddress = signal<OrderAddressReveal | null>(null);
  protected readonly revealingAddress = signal(false);
  protected readonly revealedNotes = signal<ReadonlyMap<string, string | null>>(new Map());
  protected readonly revealingNoteFor = signal<string | null>(null);

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

    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.order.set(null);
      this.loading.set(false);
      return;
    }
    this.denied.set(false);

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
        // order_process_states is not on this response either — see order-severity.ts.
        hasBlockedProcess: false,
      },
      new Date(),
    );
  }

  protected severityCaption(severity: OrderSeverity): string | null {
    return formatSeverityCaption(severity, (key, values) => this.i18n.t(key, values));
  }

  // ------------------------------------------------------------ §3.11/§4.3 actions

  protected primaryAction(): OrderActionResponse | null {
    return (this.order()?.value.summary.actions ?? [])[0] ?? null;
  }

  protected overflowActions(): readonly OrderActionResponse[] {
    return (this.order()?.value.summary.actions ?? []).slice(1);
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
        this.dialog.set('reject');
        return;
      case 'CANCEL':
        this.dialog.set('cancel');
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

  protected dialogTitleKey(): MessageKey {
    return this.dialog() === 'cancel' ? 'orders.dialog.cancel.title' : 'orders.dialog.reject.title';
  }

  protected dialogConfirmLabelKey(): MessageKey {
    return this.dialog() === 'cancel' ? 'orders.action.cancel' : 'orders.action.reject';
  }

  protected dialogNoteEnabled(): boolean {
    return this.dialog() === 'cancel';
  }

  protected onDialogDismiss(): void {
    this.dialog.set(null);
  }

  protected onDialogConfirm(submission: OrderReasonSubmission): void {
    const kind = this.dialog();
    const detail = this.order();
    const scope = this.location.scope();
    if (!kind || !detail || !scope) {
      return;
    }
    const orderId = detail.value.summary.orderId;
    const version = detail.value.summary.version ?? 0;

    const task =
      kind === 'reject'
        ? this.submitDecision(
            this.actionsApi.reject(
              scope,
              orderId,
              this.decisionIds.idFor(orderId),
              submission.reasonCode,
            ),
          )
        : this.submitStateMutation(
            this.actionsApi.cancel(scope, orderId, version, submission.reasonCode, submission.note),
          );

    void task.finally(() => this.dialog.set(null));
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
