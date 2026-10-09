import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { I18n } from '../core/i18n/i18n';
import { MessageKey } from '../core/i18n/messages.en';
import { TPipe } from '../core/i18n/t.pipe';
import { PinKeypad } from './pin-keypad';
import { RosterMember } from './terminal-api';
import { TerminalOutcome, TerminalSession } from './terminal-session';

type Step =
  | 'roster'
  | 'pin'
  | 'locked'
  | 'noPin'
  | 'code'
  | 'newPin'
  | 'confirmPin'
  | 'currentPin'
  | 'changeNew'
  | 'changeConfirm'
  | 'done';

/** The sentence for each rule a new PIN can break: literal keys, so a missing translation is a compile error. */
const RULE_KEYS: Readonly<Record<string, MessageKey>> = {
  NOT_DIGITS: 'device.terminal.rule.NOT_DIGITS',
  WRONG_LENGTH: 'device.terminal.rule.WRONG_LENGTH',
  REPEATED_DIGIT: 'device.terminal.rule.REPEATED_DIGIT',
  SEQUENCE: 'device.terminal.rule.SEQUENCE',
  COMMON: 'device.terminal.rule.COMMON',
  PERSONAL_NUMBER: 'device.terminal.rule.PERSONAL_NUMBER',
};

/**
 * The unlock screen of a terminal (ADR 0155, Decision 7): the roster of people who may work here, a keypad, and the three
 * things a person does at it -- unlock, set a PIN with an activation code, and change a PIN while unlocked.
 *
 * **It is a full-screen cover, not a page.** With `PERSON_REQUIRED` the board is hidden behind it until someone unlocks,
 * with `PERSON_OPTIONAL` it opens on request and can be dismissed, and in `DEVICE_ONLY` nothing renders it. It shows
 * labels the tenant chose and never a phone, photo or number; it gives one answer for every refused PIN; it says a lock in
 * minutes; and a PIN is held in the keypad only until OK is pressed and in no field, no log and no storage.
 *
 * What the person may do once unlocked is not decided here: the server intersects the device with the person on every
 * call. This screen only opens the session.
 */
@Component({
  selector: 'q-terminal-unlock',
  imports: [TPipe, PinKeypad],
  templateUrl: './terminal-unlock.html',
  styleUrl: './terminal-unlock.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TerminalUnlock implements OnDestroy {
  protected readonly terminal = inject(TerminalSession);
  private readonly i18n = inject(I18n);

  /** `change` opens at "enter your current PIN" for the person already unlocked. */
  readonly flow = input<'unlock' | 'change'>('unlock');
  /** Whether the screen can be dismissed: not while the mode is `PERSON_REQUIRED` and nobody is unlocked. */
  readonly dismissible = input(true);

  readonly closed = output<void>();

  protected readonly step = signal<Step>('roster');
  protected readonly chosen = signal<RosterMember | null>(null);
  protected readonly busy = signal(false);
  protected readonly message = signal<string | null>(null);

  private activationCode = '';
  private pendingPin = '';
  private currentPin = '';
  private doneTimer: ReturnType<typeof setTimeout> | null = null;

  protected readonly members = this.terminal.members;
  protected readonly pinLength = this.terminal.pinLength;
  protected readonly canClose = computed(
    () => this.dismissible() && !this.terminal.lockRequired() && this.step() !== 'done',
  );

  constructor() {
    queueMicrotask(() => {
      if (this.flow() === 'change') {
        this.step.set('currentPin');
      }
    });
  }

  ngOnDestroy(): void {
    this.forgetEntries();
    if (this.doneTimer !== null) {
      clearTimeout(this.doneTimer);
    }
  }

  // ----------------------------------------------------------------------------- the roster

  protected choose(member: RosterMember): void {
    this.message.set(null);
    this.chosen.set(member);
    switch (member.pinState) {
      case 'LOCKED':
        this.step.set('locked');
        break;
      case 'NOT_SET':
        this.step.set('noPin');
        break;
      default:
        this.step.set('pin');
    }
  }

  protected backToRoster(): void {
    this.forgetEntries();
    this.chosen.set(null);
    this.message.set(null);
    this.step.set('roster');
  }

  protected startActivation(): void {
    this.message.set(null);
    this.step.set('code');
  }

  protected close(): void {
    this.forgetEntries();
    this.closed.emit();
  }

  // ----------------------------------------------------------------------------- unlocking

  protected async onPin(pin: string): Promise<void> {
    const member = this.chosen();
    if (member === null || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.message.set(null);
    try {
      this.finish(await this.terminal.unlock(member.memberId, pin), 'pin');
    } finally {
      this.busy.set(false);
    }
  }

  // ---------------------------------------------------------------------------- activation

  protected onCode(code: string): void {
    this.activationCode = code;
    this.message.set(null);
    this.step.set('newPin');
  }

  protected onNewPin(pin: string): void {
    this.pendingPin = pin;
    this.message.set(null);
    this.step.set('confirmPin');
  }

  protected async onConfirmPin(pin: string): Promise<void> {
    const member = this.chosen();
    if (member === null || this.busy()) {
      return;
    }
    if (pin !== this.pendingPin) {
      this.pendingPin = '';
      this.message.set(this.i18n.t('device.terminal.mismatch'));
      this.step.set('newPin');
      return;
    }
    this.busy.set(true);
    try {
      const outcome = await this.terminal.activate(member.memberId, this.activationCode, pin);
      this.pendingPin = '';
      if (outcome.kind === 'PIN_RULE') {
        this.message.set(this.ruleSentence(outcome.reason));
        this.step.set('newPin');
      } else if (outcome.kind === 'CODE_REFUSED') {
        this.activationCode = '';
        this.message.set(this.i18n.t('device.terminal.codeRefused'));
        this.step.set('code');
      } else {
        this.finish(outcome, 'newPin');
      }
    } finally {
      this.busy.set(false);
    }
  }

  // ------------------------------------------------------------------------- changing a PIN

  protected onCurrentPin(pin: string): void {
    this.currentPin = pin;
    this.message.set(null);
    this.step.set('changeNew');
  }

  protected onChangeNew(pin: string): void {
    this.pendingPin = pin;
    this.message.set(null);
    this.step.set('changeConfirm');
  }

  protected async onChangeConfirm(pin: string): Promise<void> {
    if (this.busy()) {
      return;
    }
    if (pin !== this.pendingPin) {
      this.pendingPin = '';
      this.message.set(this.i18n.t('device.terminal.mismatch'));
      this.step.set('changeNew');
      return;
    }
    this.busy.set(true);
    try {
      const outcome = await this.terminal.changePin(this.currentPin, pin);
      this.pendingPin = '';
      if (outcome.kind === 'PIN_RULE') {
        this.message.set(this.ruleSentence(outcome.reason));
        this.step.set('changeNew');
      } else if (outcome.kind === 'REFUSED') {
        this.currentPin = '';
        this.message.set(this.i18n.t('device.terminal.refused'));
        this.step.set('currentPin');
      } else if (outcome.kind === 'LOCKED') {
        this.currentPin = '';
        this.message.set(this.lockedSentence(outcome.retryAfterSeconds));
        this.step.set('currentPin');
      } else if (outcome.kind === 'OK') {
        this.currentPin = '';
        this.step.set('done');
        this.message.set(this.i18n.t('device.terminal.pinChanged'));
        this.doneTimer = setTimeout(() => this.close(), 1500);
      } else {
        this.message.set(this.i18n.t('device.terminal.error'));
        this.step.set('currentPin');
      }
    } finally {
      this.busy.set(false);
    }
  }

  // --------------------------------------------------------------------------------- shared

  private finish(outcome: TerminalOutcome, retryStep: Step): void {
    switch (outcome.kind) {
      case 'OK':
        this.closed.emit();
        return;
      case 'LOCKED':
        this.message.set(this.lockedSentence(outcome.retryAfterSeconds));
        this.step.set('locked');
        return;
      case 'REFUSED':
        // The same sentence for a wrong PIN, no PIN and an ineligible person: the screen is no oracle.
        this.message.set(this.i18n.t('device.terminal.refused'));
        this.step.set(retryStep);
        return;
      default:
        this.message.set(this.i18n.t('device.terminal.error'));
        this.step.set(retryStep);
    }
  }

  protected lockedSentence(retryAfterSeconds: number | null): string {
    return retryAfterSeconds === null
      ? this.i18n.t('device.terminal.lockedUntilReset')
      : this.i18n.t('device.terminal.lockedMinutes', {
          minutes: Math.max(1, Math.ceil(retryAfterSeconds / 60)),
        });
  }

  private ruleSentence(reason: string): string {
    const key = RULE_KEYS[reason];
    return key === undefined
      ? this.i18n.t('device.terminal.rule.OTHER')
      : this.i18n.t(key, { digits: this.pinLength() });
  }

  private forgetEntries(): void {
    this.activationCode = '';
    this.pendingPin = '';
    this.currentPin = '';
  }
}
