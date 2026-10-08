import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { ApiError } from '../../core/api/problem-details';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { formatDateTime } from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { LocationView } from '../settings/locations/locations-api';
import { CallRecorder } from './call-recorder';
import {
  BLOCKING_REASON_KEYS,
  CONTACT_DIRECTION_KEYS,
  CONTACT_OUTCOME_KEYS,
  LEAD_REASON_KEYS,
  LEAD_SOURCE_KEYS,
  LEAD_STATUS_KEYS,
  NEXT_ACTION_KEYS,
} from './lead-labels';
import { LeadAccess } from './lead-reach';
import {
  ContactAttempt,
  LEAD_CLOSED_REASONS,
  Lead,
  LeadClosedReason,
  LeadsApi,
  RecordContactAttemptRequest,
  RevealedLeadContact,
  TransitionLeadRequest,
} from './leads-api';

const PLACEHOLDER_TIME_ZONE = 'Asia/Tashkent';

/** Fixed, English, machine-facing: read by whoever reviews the audit log, not by the operator. */
const REVEAL_PURPOSE = 'Operations console: call a lead back';

type Action = 'schedule' | 'convert' | 'decline' | 'lose' | 'assign';

/**
 * One lead, and everything an operator does with it (ADR 0111 §4-§8): read it, reveal the number,
 * move it along its machine, hand it to a branch, and record the calls she makes.
 *
 * **What it shows and what it does not.** The queue row carried a masked number; this panel adds the
 * hints the detail endpoint computes (accounts and other open leads holding the same number) and the
 * journal of calls. The number, the name and the notes appear only after {@link reveal}, which is one
 * purpose-stamped, audited read and needs `customer.pii.reveal` as well as the lead capability — the
 * button is absent for an operator who lacks it. Revealed values live in this component's own signal
 * and are dropped when the lead changes: nothing here caches them.
 *
 * **What it offers follows the machine** (`LeadStatus.canMoveTo`): a finished lead offers nothing but
 * its journal, a new one can be contacted, a scheduled one rescheduled. Every refusal the server
 * still makes — a stale version, a conversion with no real order — is shown in the server's own words.
 * A branch (location reach) can work a lead it was handed and cannot hand it on, so the hand-over
 * form is drawn at brand reach only.
 */
@Component({
  selector: 'q-lead-detail-panel',
  imports: [TPipe, RouterLink, CallRecorder],
  templateUrl: './lead-detail-panel.html',
  styleUrl: './lead-detail-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LeadDetailPanel {
  private readonly api = inject(LeadsApi);
  protected readonly i18n = inject(I18n);
  private readonly capabilities = inject(SessionCapabilities);

  readonly lead = input.required<Lead>();
  readonly access = input.required<LeadAccess>();
  readonly locations = input<readonly LocationView[]>([]);

  /** The lead after any change, so the queue replaces its row. */
  readonly changed = output<Lead>();
  readonly closed = output<void>();

  protected readonly current = signal<Lead | null>(null);
  protected readonly attempts = signal<readonly ContactAttempt[]>([]);
  protected readonly revealed = signal<RevealedLeadContact | null>(null);
  protected readonly revealing = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly action = signal<Action | null>(null);
  protected readonly recording = signal(false);

  protected readonly callbackAt = signal('');
  protected readonly convertKind = signal<'order' | 'reservation'>('order');
  protected readonly convertId = signal('');
  protected readonly closedReason = signal<LeadClosedReason>('NOT_INTERESTED');
  protected readonly assignTo = signal('');

  protected readonly closedReasons = LEAD_CLOSED_REASONS;

  protected readonly open = computed(() => {
    const status = this.current()?.status;
    return status === 'NEW' || status === 'CONTACTED' || status === 'CALLBACK_SCHEDULED';
  });
  protected readonly canContact = computed(() => {
    const status = this.current()?.status;
    return status === 'NEW' || status === 'CALLBACK_SCHEDULED';
  });
  protected readonly canAssign = computed(
    () => this.access().reach.kind === 'BRAND' && this.access().canManage && this.open(),
  );
  protected readonly canWork = computed(() => this.access().canManage && this.open());
  /** Linking is identity, not the machine: it is offered whatever the lead's status. */
  protected readonly canLink = computed(() => this.access().canManage);
  /** The customer's page is `customer.read`'s; a brand manager runs the queue without it. */
  protected readonly canOpenCustomer = computed(() => this.capabilities.has('CUSTOMER_READ'));

  constructor() {
    // The queue reuses this component across a selection change, so the load keys on the input.
    effect(() => {
      const lead = this.lead();
      void this.load(lead);
    });
  }

  private async load(lead: Lead): Promise<void> {
    this.current.set(lead);
    this.revealed.set(null);
    this.action.set(null);
    this.error.set(null);
    this.recording.set(false);
    this.attempts.set([]);
    const reach = this.access().reach;
    try {
      const [detail, attempts] = await Promise.all([
        this.api.detail(reach, lead.id),
        this.api.attempts(reach, lead.id),
      ]);
      // A selection may have moved on while this was in flight; the older answer is not the screen's.
      if (this.lead().id === lead.id) {
        this.current.set(detail.value);
        this.attempts.set(attempts);
      }
    } catch (failure) {
      this.fail(failure);
    }
  }

  protected async reveal(): Promise<void> {
    const lead = this.current();
    if (!lead || this.revealing()) {
      return;
    }
    this.revealing.set(true);
    this.error.set(null);
    try {
      this.revealed.set(await this.api.reveal(this.access().reach, lead.id, REVEAL_PURPOSE));
    } catch (failure) {
      this.fail(failure);
    } finally {
      this.revealing.set(false);
    }
  }

  protected hide(): void {
    this.revealed.set(null);
  }

  protected choose(action: Action): void {
    this.error.set(null);
    this.action.set(action);
  }

  protected cancelAction(): void {
    this.action.set(null);
  }

  protected async markContacted(): Promise<void> {
    await this.transition({ target: 'CONTACTED' });
  }

  protected async submitAction(): Promise<void> {
    switch (this.action()) {
      case 'schedule': {
        const at = this.callbackAt();
        if (!at) {
          return;
        }
        await this.transition({
          target: 'CALLBACK_SCHEDULED',
          callbackDueAt: new Date(at).toISOString(),
        });
        return;
      }
      case 'convert': {
        const id = this.convertId().trim();
        if (!id) {
          return;
        }
        await this.transition(
          this.convertKind() === 'order'
            ? { target: 'CONVERTED', convertedOrderId: id }
            : { target: 'CONVERTED', convertedReservationId: id },
        );
        return;
      }
      case 'decline':
        await this.transition({ target: 'DECLINED', closedReason: this.closedReason() });
        return;
      case 'lose':
        await this.transition({ target: 'LOST', closedReason: this.closedReason() });
        return;
      case 'assign': {
        const location = this.assignTo();
        if (!location) {
          return;
        }
        await this.run((lead) =>
          this.api.assign(this.access().reach, lead.id, location, lead.version),
        );
        return;
      }
      case null:
        return;
    }
  }

  /** The operator confirms the hinted account is the guest behind this lead. */
  protected async linkTo(accountId: string): Promise<void> {
    await this.run((lead) =>
      this.api.linkCustomer(this.access().reach, lead.id, accountId, lead.version),
    );
  }

  protected async record(request: RecordContactAttemptRequest): Promise<void> {
    const lead = this.current();
    if (!lead || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.api.recordAttempt(this.access().reach, lead.id, request);
      this.attempts.set(await this.api.attempts(this.access().reach, lead.id));
      this.recording.set(false);
    } catch (failure) {
      this.fail(failure);
    } finally {
      this.busy.set(false);
    }
  }

  private async transition(request: TransitionLeadRequest): Promise<void> {
    await this.run((lead) =>
      this.api.transition(this.access().reach, lead.id, request, lead.version),
    );
  }

  private async run(call: (lead: Lead) => Promise<Lead>): Promise<void> {
    const lead = this.current();
    if (!lead || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      const updated = await call(lead);
      // The write answers with the lead as stored, but the hints come from the detail read.
      const detail = await this.api.detail(this.access().reach, updated.id);
      this.current.set(detail.value);
      this.action.set(null);
      this.changed.emit(detail.value);
    } catch (failure) {
      this.fail(failure);
    } finally {
      this.busy.set(false);
    }
  }

  private fail(failure: unknown): void {
    if (failure instanceof ApiError) {
      this.error.set(describeApiError(failure, (key, values) => this.i18n.t(key, values)));
    } else {
      throw failure;
    }
  }

  protected close(): void {
    this.closed.emit();
  }

  protected statusLabel(lead: Lead): string {
    return this.i18n.t(LEAD_STATUS_KEYS[lead.status]);
  }

  protected sourceLabel(lead: Lead): string {
    return this.i18n.t(LEAD_SOURCE_KEYS[lead.source]);
  }

  protected reasonLabel(reason: LeadClosedReason): string {
    return this.i18n.t(LEAD_REASON_KEYS[reason]);
  }

  protected branchLabel(lead: Lead): string {
    if (lead.assignedLocationId === null) {
      return this.i18n.t('customers.leads.branch.none');
    }
    return (
      this.locations().find((location) => location.id === lead.assignedLocationId)?.displayName ??
      this.i18n.t('customers.leads.branch.thisOne')
    );
  }

  protected formatWhen(instant: string): string {
    return formatDateTime(new Date(instant), PLACEHOLDER_TIME_ZONE);
  }

  protected attemptSummary(attempt: ContactAttempt): string {
    const parts = [
      this.i18n.t(CONTACT_DIRECTION_KEYS[attempt.direction]),
      this.i18n.t(CONTACT_OUTCOME_KEYS[attempt.outcome]),
    ];
    if (attempt.blockingReason) {
      parts.push(this.i18n.t(BLOCKING_REASON_KEYS[attempt.blockingReason]));
    }
    if (attempt.nextAction) {
      parts.push(this.i18n.t(NEXT_ACTION_KEYS[attempt.nextAction]));
    }
    return parts.join(' · ');
  }

  protected setClosedReason(value: string): void {
    const found = LEAD_CLOSED_REASONS.find((reason) => reason === value);
    if (found) {
      this.closedReason.set(found);
    }
  }

  protected setConvertKind(value: string): void {
    this.convertKind.set(value === 'reservation' ? 'reservation' : 'order');
  }
}
