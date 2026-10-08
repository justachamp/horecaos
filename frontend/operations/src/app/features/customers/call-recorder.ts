import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import {
  BLOCKING_REASON_KEYS,
  CONTACT_DIRECTION_KEYS,
  CONTACT_OUTCOME_KEYS,
  NEXT_ACTION_KEYS,
} from './lead-labels';
import {
  BLOCKING_REASONS,
  BlockingReason,
  CONTACT_OUTCOMES,
  ContactDirection,
  ContactOutcome,
  NEXT_ACTIONS,
  NextAction,
  RecordContactAttemptRequest,
} from './leads-api';

/**
 * The form an operator fills in after a call (ADR 0111 §8): who rang whom, what came of it, and — when
 * the attempt was refused before it could be made — why.
 *
 * Shared by a lead's panel and the customer card's contacts tab, because both write the same
 * append-only journal. It builds the request and nothing else: whether the call is about a lead or an
 * account is the caller's, and so is the call itself. The journal cannot be edited afterwards, so the
 * form says so before it is submitted rather than after.
 */
@Component({
  selector: 'q-call-recorder',
  imports: [TPipe],
  templateUrl: './call-recorder.html',
  styleUrl: './call-recorder.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CallRecorder {
  protected readonly i18n = inject(I18n);

  readonly busy = input(false);
  readonly submitted = output<RecordContactAttemptRequest>();

  protected readonly directions: readonly ContactDirection[] = ['OUTBOUND', 'INBOUND'];
  protected readonly outcomes = CONTACT_OUTCOMES;
  protected readonly blockingReasons = BLOCKING_REASONS;
  protected readonly nextActions = NEXT_ACTIONS;

  /**
   * One call is one attempt id, minted when the form opens and sent with every submit of it. The
   * journal keeps one row per (tenant, attempt id), so a submit retried after the answer was lost
   * lands on the row the first one wrote instead of recording the same call twice.
   */
  private readonly attemptId = crypto.randomUUID();

  protected readonly direction = signal<ContactDirection>('OUTBOUND');
  protected readonly outcome = signal<ContactOutcome>('CONNECTED');
  protected readonly blockingReason = signal<BlockingReason>('NO_CONSENT');
  protected readonly nextAction = signal<NextAction | ''>('');
  protected readonly nextActionAt = signal('');

  /** A refused attempt names why, and an attempt that was not refused names no reason. */
  protected readonly blocked = computed(() => this.outcome() === 'BLOCKED');

  protected directionLabel(direction: ContactDirection): string {
    return this.i18n.t(CONTACT_DIRECTION_KEYS[direction]);
  }

  protected outcomeLabel(outcome: ContactOutcome): string {
    return this.i18n.t(CONTACT_OUTCOME_KEYS[outcome]);
  }

  protected blockingLabel(reason: BlockingReason): string {
    return this.i18n.t(BLOCKING_REASON_KEYS[reason]);
  }

  protected nextActionLabel(action: NextAction): string {
    return this.i18n.t(NEXT_ACTION_KEYS[action]);
  }

  protected setDirection(value: string): void {
    this.direction.set(value === 'INBOUND' ? 'INBOUND' : 'OUTBOUND');
  }

  protected setOutcome(value: string): void {
    const found = CONTACT_OUTCOMES.find((candidate) => candidate === value);
    if (found) {
      this.outcome.set(found);
    }
  }

  protected setBlockingReason(value: string): void {
    const found = BLOCKING_REASONS.find((candidate) => candidate === value);
    if (found) {
      this.blockingReason.set(found);
    }
  }

  protected setNextAction(value: string): void {
    this.nextAction.set(NEXT_ACTIONS.find((candidate) => candidate === value) ?? '');
  }

  protected submit(): void {
    const next = this.nextAction();
    const at = this.nextActionAt();
    this.submitted.emit({
      direction: this.direction(),
      outcome: this.outcome(),
      attemptId: this.attemptId,
      blockingReason: this.blocked() ? this.blockingReason() : undefined,
      nextAction: next === '' ? undefined : next,
      nextActionAt: next !== '' && at ? new Date(at).toISOString() : undefined,
    });
  }
}
