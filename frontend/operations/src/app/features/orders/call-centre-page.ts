import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';

import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { ApiError } from '../../core/api/problem-details';
import { CreateCustomerDialog, CreateCustomerSubmission } from '../customers/create-customer-dialog';
import { CustomersApi } from '../customers/customers-api';
import { CallCentreApi, CallLogEntry, PresenceState, PresenceView, ScreenPopCard } from './call-centre-api';

/** Same cadence every other live screen in this app polls at (`order-queue.ts`, `vdu-page.ts`), until ADR 0045 exists. */
const POLL_INTERVAL_MS = 10_000;

const PRESENCE_STATES: readonly PresenceState[] = ['ONLINE', 'PAUSED', 'WRAP_UP', 'OFFLINE'];

/**
 * IA 1.6 — Call centre (ADR 0064, wave 46): presence, the screen-pop poll,
 * and the branch's call log.
 *
 * **Built**: marking your own presence at this branch, with a required
 * reason on PAUSED; polling for the current ringing call and claiming its
 * card; an unknown caller's card offering create-customer, prefilled with
 * the number the operator already heard; the branch's recent call list.
 *
 * **Not built, deliberately**: a softphone or click-to-call. ADR 0064
 * considered a WebRTC softphone inside the operations app and refused it —
 * it would carry audio, codecs and telephony reliability into a platform
 * that does not own a phone system, and operators keep the handset or
 * softphone they already have. This screen never tries to dial or ring
 * anything; it only shows what the platform already knows about a call.
 * Telephony KPIs (average handling time, per-operator leaderboards) are IA
 * §7.5's own screen, fed by the same {@link CallCentreApi.callLog} facts
 * through ADR 0043's day-close pipeline — not duplicated here.
 */
@Component({
  selector: 'q-call-centre-page',
  imports: [TPipe, CreateCustomerDialog],
  templateUrl: './call-centre-page.html',
  styleUrl: './call-centre-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CallCentrePage implements OnInit {
  private readonly location = inject(CurrentLocation);
  private readonly api = inject(CallCentreApi);
  private readonly customersApi = inject(CustomersApi);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly i18n = inject(I18n);

  protected readonly presenceStates = PRESENCE_STATES;

  protected readonly firstLoadComplete = signal(false);
  protected readonly denied = signal(false);

  protected readonly presence = signal<PresenceView | null>(null);
  protected readonly presenceReason = signal('');
  protected readonly presenceSaving = signal(false);
  protected readonly presenceError = signal<string | null>(null);

  protected readonly currentCall = signal<ScreenPopCard | null>(null);
  protected readonly screenPopError = signal<string | null>(null);
  protected readonly acknowledging = signal(false);

  protected readonly callLog = signal<readonly CallLogEntry[]>([]);

  protected readonly createCustomerOpen = signal(false);
  protected readonly createCustomerPhone = signal('');
  protected readonly createCustomerBusy = signal(false);
  protected readonly createCustomerError = signal<string | null>(null);

  private pollHandle: ReturnType<typeof setInterval> | null = null;
  private readonly onVisibilityChange = (): void => {
    if (document.visibilityState === 'visible') {
      void this.pollCurrentCall();
    }
  };

  async ngOnInit(): Promise<void> {
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.firstLoadComplete.set(true);
      return;
    }

    try {
      const [presence, callLog] = await Promise.all([this.api.myPresence(scope), this.api.callLog(scope)]);
      this.presence.set(presence);
      this.callLog.set(callLog);
      this.denied.set(false);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else if (!(error instanceof ApiError)) {
        throw error;
      }
    } finally {
      this.firstLoadComplete.set(true);
    }

    document.addEventListener('visibilitychange', this.onVisibilityChange);
    this.pollHandle = setInterval(() => {
      if (document.visibilityState === 'visible') {
        void this.pollCurrentCall();
      }
    }, POLL_INTERVAL_MS);
    this.destroyRef.onDestroy(() => {
      document.removeEventListener('visibilitychange', this.onVisibilityChange);
      if (this.pollHandle !== null) {
        clearInterval(this.pollHandle);
      }
    });

    void this.pollCurrentCall();
  }

  private async pollCurrentCall(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      const card = await this.api.currentCall(scope);
      this.currentCall.set(card.ringing ? card : null);
      this.screenPopError.set(null);
    } catch (error) {
      // A poll failing once is not worth interrupting an operator over; the
      // next tick tries again. Only a persistent 403 is worth a message, and
      // that already surfaced on first load.
      if (error instanceof ApiError && error.status !== 403) {
        this.screenPopError.set(this.i18n.t('orders.callCentre.screenPop.error'));
      }
    }
  }

  protected presenceStateLabel(state: PresenceState): string {
    return this.i18n.t(('orders.callCentre.presence.state.' + state) as MessageKey);
  }

  protected async setPresence(state: PresenceState): Promise<void> {
    const scope = this.location.scope();
    if (!scope || this.presenceSaving()) {
      return;
    }
    const reason = this.presenceReason().trim();
    if (state === 'PAUSED' && reason === '') {
      this.presenceError.set(this.i18n.t('orders.callCentre.presence.reasonRequired'));
      return;
    }
    this.presenceSaving.set(true);
    this.presenceError.set(null);
    try {
      const updated = await this.api.setPresence(scope, state, state === 'PAUSED' ? reason : null);
      this.presence.set(updated);
      this.presenceReason.set('');
    } catch {
      this.presenceError.set(this.i18n.t('orders.callCentre.presence.error'));
    } finally {
      this.presenceSaving.set(false);
    }
  }

  protected async claimCurrentCall(): Promise<void> {
    const scope = this.location.scope();
    const call = this.currentCall();
    if (!scope || !call?.callEventId || this.acknowledging()) {
      return;
    }
    this.acknowledging.set(true);
    try {
      await this.api.acknowledge(scope, call.callEventId);
      await this.pollCurrentCall();
    } catch {
      // A claim that lost the race (another operator got there first, or the
      // call already ended) is not an error worth a message — the next poll
      // shows the card gone either way.
    } finally {
      this.acknowledging.set(false);
    }
  }

  /** `eventType` is always one of the five ADR 0064 vocabulary words the backend's own `CallEventType` enum defines. */
  protected callTypeLabel(eventType: string): string {
    return this.i18n.t(('orders.callCentre.callLog.type.' + eventType) as MessageKey);
  }

  protected formatDuration(seconds: number | null): string {
    if (seconds === null) {
      return '—';
    }
    const minutes = Math.floor(seconds / 60);
    const remainder = seconds % 60;
    return `${minutes}:${String(remainder).padStart(2, '0')}`;
  }

  protected formatTime(iso: string): string {
    const date = new Date(iso);
    return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
  }

  // -------------------------------------------------------- create customer

  /**
   * Reveals the unknown caller's real number (never the masked display
   * value — that could not be typed into a contact point) and opens the
   * shared create-customer dialog prefilled with it.
   */
  protected async openCreateCustomer(): Promise<void> {
    const scope = this.location.scope();
    const call = this.currentCall();
    if (!scope || !call?.callEventId) {
      return;
    }
    this.createCustomerError.set(null);
    try {
      const number = await this.api.revealCallerNumber(scope, call.callEventId);
      this.createCustomerPhone.set(number);
      this.createCustomerOpen.set(true);
    } catch {
      this.createCustomerError.set(this.i18n.t('orders.callCentre.screenPop.error'));
    }
  }

  protected async onCreateCustomerConfirm(submission: CreateCustomerSubmission): Promise<void> {
    const scope = this.location.scope();
    if (!scope || this.createCustomerBusy()) {
      return;
    }
    this.createCustomerBusy.set(true);
    this.createCustomerError.set(null);
    try {
      await this.customersApi.create(scope, {
        brandId: scope.brandId,
        phone: submission.phone,
        displayName: submission.displayName || null,
      });
      this.createCustomerOpen.set(false);
      // The customer now exists; the next poll re-resolves this same caller
      // against it rather than this page guessing the new account's shape.
      await this.pollCurrentCall();
    } catch {
      this.createCustomerError.set(this.i18n.t('orders.callCentre.screenPop.error'));
    } finally {
      this.createCustomerBusy.set(false);
    }
  }

  protected onCreateCustomerDismiss(): void {
    this.createCustomerOpen.set(false);
  }
}
