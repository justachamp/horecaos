import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';

import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { ApiError } from '../../core/api/problem-details';
import { VoicePresence } from '../../shell/voice-presence';
import {
  CreateCustomerDialog,
  CreateCustomerSubmission,
} from '../customers/create-customer-dialog';
import { CustomersApi } from '../customers/customers-api';
import { CallCentreApi, CallLogEntry, PresenceState, PresenceView } from './call-centre-api';

const PRESENCE_STATES: readonly PresenceState[] = ['ONLINE', 'PAUSED', 'WRAP_UP', 'OFFLINE'];

/**
 * IA 1.6 — Call centre (ADR 0064, wave 46; presence/screen-pop lifted into
 * `VoicePresence` and the roster wired up in W01).
 *
 * **Built**: marking your own presence at this branch, with a required
 * reason on PAUSED; a team roster of every operator's presence at this
 * branch; polling for the current ringing call and claiming its card; an
 * unknown caller's card offering create-customer, prefilled with the number
 * the operator already heard, or a known caller's card offering to start an
 * order for them (which records the call's provenance once the order
 * exists — see `new-order-page.ts`); the branch's recent call list.
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
 *
 * **Presence and the screen-pop poll live in `VoicePresence` now**, not
 * here — that is what lets the shell's call bar (IA X.37) show the same
 * ringing card on every screen, not only this one. This page still owns the
 * call log, the presence-change form, the roster, and the create-customer
 * flow; `ngOnInit` calls `VoicePresence.start()` itself (idempotent, same
 * shape as `CurrentLocation.ensureLoaded`) so the poll runs even when an
 * operator opens this page directly rather than through the shell's
 * already-mounted bar.
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
  private readonly voicePresence = inject(VoicePresence);
  private readonly router = inject(Router);
  protected readonly i18n = inject(I18n);

  protected readonly presenceStates = PRESENCE_STATES;

  protected readonly firstLoadComplete = signal(false);
  protected readonly denied = signal(false);

  protected readonly presence = computed(() => this.voicePresence.presence());
  protected readonly presenceReason = signal('');
  protected readonly presenceSaving = signal(false);
  protected readonly presenceError = signal<string | null>(null);

  protected readonly currentCall = computed(() => this.voicePresence.currentCall());
  protected readonly screenPopError = computed(() =>
    this.voicePresence.pollError() ? this.i18n.t('orders.callCentre.screenPop.error') : null,
  );
  protected readonly acknowledging = signal(false);

  /** The branch's team board — empty and silently hidden for an operator who holds VOICE_PRESENCE_MANAGE but not VOICE_PRESENCE_READ (`location-staff`, `PlatformRole.java`). */
  protected readonly roster = signal<readonly PresenceView[]>([]);

  protected readonly callLog = signal<readonly CallLogEntry[]>([]);

  protected readonly createCustomerOpen = signal(false);
  protected readonly createCustomerPhone = signal('');
  protected readonly createCustomerBusy = signal(false);
  protected readonly createCustomerError = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.firstLoadComplete.set(true);
      return;
    }

    try {
      const [, callLog] = await Promise.all([
        this.voicePresence.refreshPresence(),
        this.api.callLog(scope),
      ]);
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

    try {
      this.roster.set(await this.api.roster(scope));
    } catch {
      // location-staff holds VOICE_PRESENCE_MANAGE and not VOICE_PRESENCE_READ
      // (PlatformRole.java's own comment on that split) — a 403 here just
      // means no team board for this operator, not a reason to deny the
      // whole screen they otherwise have every right to use.
    }

    this.voicePresence.start();
  }

  protected presenceStateLabel(state: string): string {
    return this.i18n.t(('orders.callCentre.presence.state.' + state) as MessageKey);
  }

  protected async setPresence(state: PresenceState): Promise<void> {
    if (this.presenceSaving()) {
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
      await this.voicePresence.setPresence(state, state === 'PAUSED' ? reason : null);
      this.presenceReason.set('');
    } catch {
      this.presenceError.set(this.i18n.t('orders.callCentre.presence.error'));
    } finally {
      this.presenceSaving.set(false);
    }
  }

  protected async claimCurrentCall(): Promise<void> {
    const call = this.currentCall();
    if (!call?.callEventId || this.acknowledging()) {
      return;
    }
    this.acknowledging.set(true);
    try {
      await this.voicePresence.claim(call.callEventId);
    } catch {
      // A claim that lost the race (another operator got there first, or the
      // call already ended) is not an error worth a message — the next poll
      // shows the card gone either way.
    } finally {
      this.acknowledging.set(false);
    }
  }

  /**
   * ADR 0064: starts an order for the caller this claimed card already
   * names, carrying the call's id so `new-order-page.ts` can link the order
   * it creates back to this call — see that page's own doc.
   */
  protected startOrderForCall(): void {
    const call = this.currentCall();
    if (!call?.callEventId) {
      return;
    }
    void this.router.navigate(['/orders/new'], { queryParams: { callEventId: call.callEventId } });
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
      await this.voicePresence.refreshCurrentCall();
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
