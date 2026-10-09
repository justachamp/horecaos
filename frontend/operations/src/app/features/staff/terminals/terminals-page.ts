import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { SessionCapabilities } from '../../../core/auth/session-capabilities';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { formatInstantUtc } from './instant-format';
import {
  ATTRIBUTION_MODES,
  AttributionMode,
  RosterDisplay,
  TerminalPolicyView,
  TerminalSessionView,
  TerminalView,
  TerminalsApi,
} from './terminals-api';

/** The words for each value the server names: literal keys, so a missing translation is a compile error. */
const SOURCE_KEYS: Readonly<Record<'DEFAULT' | 'PLATFORM' | 'TENANT', MessageKey>> = {
  DEFAULT: 'staff.terminals.policy.source.DEFAULT',
  PLATFORM: 'staff.terminals.policy.source.PLATFORM',
  TENANT: 'staff.terminals.policy.source.TENANT',
};

const MODE_KEYS: Readonly<Record<AttributionMode, MessageKey>> = {
  DEVICE_ONLY: 'staff.terminals.mode.DEVICE_ONLY',
  PERSON_OPTIONAL: 'staff.terminals.mode.PERSON_OPTIONAL',
  PERSON_REQUIRED: 'staff.terminals.mode.PERSON_REQUIRED',
};

const END_REASON_KEYS: Readonly<Record<string, MessageKey>> = {
  LOCKED: 'staff.terminals.history.reason.LOCKED',
  SUPERSEDED: 'staff.terminals.history.reason.SUPERSEDED',
  REVOKED: 'staff.terminals.history.reason.REVOKED',
  PIN_RESET: 'staff.terminals.history.reason.PIN_RESET',
  EMPLOYMENT_ENDED: 'staff.terminals.history.reason.EMPLOYMENT_ENDED',
  IDLE: 'staff.terminals.history.reason.IDLE',
  MAX_AGE: 'staff.terminals.history.reason.MAX_AGE',
};

/** A device that must be unlocked and has not been seen for this long reads as a warning (ADR 0155, Decision 6). */
export const UNSEEN_AFTER_MINUTES = 5;

/**
 * Staff → Терминалы (ADR 0155, Decisions 6 and 7; the spec's §8 list and device record): every device at the branch, of
 * any class, with the one «last seen» they share, who is unlocked at each now, and what a manager chooses about it —
 * its name, how a person's unlock attributes an action, and its own idle lock. Above the list, the tenant's Policy card:
 * the five values, where each comes from, and the odds of guessing a PIN stated beside the length.
 *
 * Approval is not here. It stays with the screen that owns the class (Kitchen → Devices, Channel setup → KIOSK), and
 * «Заменить устройство» says what replacing one is: revoke it, then pair the new unit — a device displays its own code,
 * so no code is issued from a console.
 *
 * **What a manager can do here is gated twice and trusted never.** Reading needs `terminal.read` and changing needs
 * `terminal.manage`, both at the branch; the Policy card needs the same pair at the tenant, which a branch manager's grant
 * does not reach. The page hides what the caller's capabilities say they cannot use and shows the server's refusal if it
 * disagrees.
 */
@Component({
  selector: 'q-terminals-page',
  imports: [TPipe],
  templateUrl: './terminals-page.html',
  styleUrl: './terminals-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TerminalsPage implements OnInit {
  private readonly location = inject(CurrentLocation);
  private readonly tenant = inject(CurrentTenant);
  private readonly api = inject(TerminalsApi);
  private readonly capabilities = inject(SessionCapabilities);
  protected readonly i18n = inject(I18n);

  protected readonly modes = ATTRIBUTION_MODES;
  protected readonly firstLoadComplete = signal(false);
  protected readonly denied = signal(false);
  protected readonly loadError = signal(false);
  protected readonly devices = signal<readonly TerminalView[]>([]);

  protected readonly canManage = computed(() => this.capabilities.has('TERMINAL_MANAGE'));

  // ----------------------------------------------------------------------------------- the policy card
  protected readonly policy = signal<TerminalPolicyView | null>(null);
  /** The Policy card is held at TENANT scope: a branch manager's grant reads the registry and not the card. */
  protected readonly policyDenied = signal(false);
  protected readonly policyError = signal<string | null>(null);
  protected readonly policySaving = signal(false);
  protected readonly policySaved = signal(false);
  protected readonly draftPinLength = signal(6);
  protected readonly draftAutoLock = signal('300');
  protected readonly draftMaxHours = signal('12');
  protected readonly draftRoster = signal<RosterDisplay>('NAME_INITIAL');
  protected readonly draftDefaultMode = signal<AttributionMode>('DEVICE_ONLY');

  /** The odds stated beside the length being chosen (ADR 0155, Open inputs 3 and 4), never a tenant's setting. */
  protected readonly oddsForDraft = computed(() => {
    const odds = this.policy()?.guessOdds.find((o) => o.pinLength === this.draftPinLength());
    return odds ? new Intl.NumberFormat(this.i18n.locale()).format(odds.oneInNPerDay) : '';
  });

  // ------------------------------------------------------------------------------------ a device row
  protected readonly editId = signal<string | null>(null);
  protected readonly draftName = signal('');
  protected readonly draftMode = signal<AttributionMode>('DEVICE_ONLY');
  /** '' follows the tenant's policy. */
  protected readonly draftLock = signal('');
  protected readonly editSaving = signal(false);
  protected readonly editError = signal<string | null>(null);

  protected readonly revokeTargetId = signal<string | null>(null);
  protected readonly revokeReason = signal('');
  protected readonly revokeSubmitting = signal(false);
  protected readonly revokeError = signal<string | null>(null);

  protected readonly historyId = signal<string | null>(null);
  protected readonly history = signal<readonly TerminalSessionView[]>([]);
  protected readonly historyCursor = signal<string | null>(null);
  protected readonly historyLoading = signal(false);
  protected readonly historyError = signal(false);

  protected readonly replaceHintId = signal<string | null>(null);

  /** The clock the «not seen» judgement is made against; moved by `refreshClock` so a spec can hold it still. */
  protected readonly now = signal(Date.now());

  protected readonly activeDevices = computed(() =>
    this.devices().filter((device) => device.status === 'ACTIVE'),
  );
  protected readonly revokedDevices = computed(() =>
    this.devices().filter((device) => device.status !== 'ACTIVE'),
  );

  async ngOnInit(): Promise<void> {
    await Promise.all([this.location.ensureLoaded(), this.capabilities.ensureLoaded()]);
    await this.reload();
    void this.loadPolicy();
  }

  private async reload(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.firstLoadComplete.set(true);
      return;
    }
    try {
      this.devices.set(await this.api.list(scope));
      this.denied.set(false);
      this.loadError.set(false);
      this.now.set(Date.now());
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
        this.loadError.set(false);
      } else if (error instanceof ApiError) {
        this.loadError.set(true);
      } else {
        throw error;
      }
    } finally {
      this.firstLoadComplete.set(true);
    }
  }

  // ------------------------------------------------------------------------------------- the policy

  private async loadPolicy(): Promise<void> {
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (tenantId === null) {
      return;
    }
    try {
      this.adopt(await this.api.policy(tenantId));
      this.policyDenied.set(false);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.policyDenied.set(true);
      } else {
        this.policyError.set(this.describe(error));
      }
    }
  }

  private adopt(view: TerminalPolicyView): void {
    this.policy.set(view);
    this.draftPinLength.set(view.pinLength.value);
    this.draftAutoLock.set(String(view.autoLockSeconds.value));
    this.draftMaxHours.set(String(view.sessionMaxHours.value));
    this.draftRoster.set(view.rosterDisplay.value);
    this.draftDefaultMode.set(view.attributionDefault.value);
  }

  protected policyValid(): boolean {
    const lock = Number(this.draftAutoLock());
    const hours = Number(this.draftMaxHours());
    return (
      Number.isInteger(lock) &&
      lock >= 30 &&
      lock <= 3600 &&
      Number.isInteger(hours) &&
      hours >= 1 &&
      hours <= 16
    );
  }

  protected async savePolicy(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (tenantId === null || !this.policyValid() || this.policySaving()) {
      return;
    }
    this.policySaving.set(true);
    this.policySaved.set(false);
    this.policyError.set(null);
    try {
      this.adopt(
        await firstValueFrom(
          this.api.savePolicy(tenantId, {
            pinLength: this.draftPinLength(),
            autoLockSeconds: Number(this.draftAutoLock()),
            sessionMaxHours: Number(this.draftMaxHours()),
            rosterDisplay: this.draftRoster(),
            attributionDefault: this.draftDefaultMode(),
          }),
        ),
      );
      this.policySaved.set(true);
    } catch (error) {
      this.policyError.set(this.describe(error));
    } finally {
      this.policySaving.set(false);
    }
  }

  protected sourceLabel(source: string): string {
    return this.i18n.t(
      SOURCE_KEYS[source === 'TENANT' || source === 'PLATFORM' ? source : 'DEFAULT'],
    );
  }

  // ------------------------------------------------------------------------------------ labels

  protected classLabel(deviceClass: string): string {
    switch (deviceClass) {
      case 'KITCHEN_VDU':
        return this.i18n.t('staff.terminals.class.KITCHEN_VDU');
      case 'KIOSK':
        return this.i18n.t('staff.terminals.class.KIOSK');
      default:
        return this.i18n.t('staff.terminals.class.KITCHEN_KDS');
    }
  }

  protected modeLabel(mode: AttributionMode): string {
    return this.i18n.t(MODE_KEYS[mode]);
  }

  /** How long the device has been silent, as a manager should read it; never a bare timestamp. */
  protected lastSeenLabel(device: TerminalView): string {
    if (!device.lastSeenAt) {
      return this.i18n.t('staff.terminals.lastSeen.never');
    }
    const minutes = this.minutesSilent(device);
    if (minutes < 1) {
      return this.i18n.t('staff.terminals.lastSeen.now');
    }
    if (minutes < 60) {
      return this.i18n.t('staff.terminals.lastSeen.minutes', { minutes });
    }
    return this.i18n.t('staff.terminals.lastSeen.at', {
      time: this.formattedInstant(device.lastSeenAt),
    });
  }

  /** A device that must be unlocked and is silent: the only urgent row on the screen. */
  protected unseen(device: TerminalView): boolean {
    return (
      device.status === 'ACTIVE' &&
      device.attributionMode === 'PERSON_REQUIRED' &&
      device.lastSeenAt !== null &&
      this.minutesSilent(device) >= UNSEEN_AFTER_MINUTES
    );
  }

  private minutesSilent(device: TerminalView): number {
    const seen = new Date(device.lastSeenAt ?? '').getTime();
    return Number.isNaN(seen) ? 0 : Math.max(0, Math.floor((this.now() - seen) / 60_000));
  }

  protected reasonLabel(reason: string | null): string {
    if (reason === null) {
      return this.i18n.t('staff.terminals.history.open');
    }
    // A reason this build does not know yet is shown as the server sent it, never as a blank.
    const key = END_REASON_KEYS[reason];
    return key === undefined ? reason : this.i18n.t(key);
  }

  // ------------------------------------------------------------------------------------ editing

  protected startEdit(device: TerminalView): void {
    this.editId.set(device.deviceId);
    this.draftName.set(device.displayName);
    this.draftMode.set(device.attributionMode);
    this.draftLock.set(device.autoLockSeconds === null ? '' : String(device.autoLockSeconds));
    this.editError.set(null);
  }

  protected cancelEdit(): void {
    this.editId.set(null);
    this.editError.set(null);
  }

  protected editValid(): boolean {
    const lock = this.draftLock().trim();
    const value = Number(lock);
    return (
      this.draftName().trim().length > 0 &&
      (lock === '' || (Number.isInteger(value) && value >= 30 && value <= 3600))
    );
  }

  protected async saveEdit(device: TerminalView): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.editValid() || this.editSaving()) {
      return;
    }
    this.editSaving.set(true);
    this.editError.set(null);
    try {
      const lock = this.draftLock().trim();
      const saved = await firstValueFrom(
        this.api.configure(
          scope,
          device.deviceId,
          {
            displayName: this.draftName().trim(),
            attributionMode: device.unlockable ? this.draftMode() : 'DEVICE_ONLY',
            autoLockSeconds: lock === '' ? null : Number(lock),
          },
          device.version,
        ),
      );
      this.devices.update((current) =>
        current.map((row) => (row.deviceId === saved.deviceId ? saved : row)),
      );
      this.editId.set(null);
    } catch (error) {
      if (error instanceof ApiError && error.code === ApiErrorCode.STALE_VERSION) {
        // Someone else changed this device first: show what is true now and ask again.
        await this.reload();
        this.editId.set(null);
        this.editError.set(this.i18n.t('staff.terminals.edit.stale'));
      } else {
        this.editError.set(this.describe(error));
      }
    } finally {
      this.editSaving.set(false);
    }
  }

  // ------------------------------------------------------------------------------------- revoking

  protected startRevoke(deviceId: string): void {
    this.revokeTargetId.set(deviceId);
    this.revokeReason.set('');
    this.revokeError.set(null);
  }

  protected cancelRevoke(): void {
    this.revokeTargetId.set(null);
    this.revokeReason.set('');
    this.revokeError.set(null);
  }

  protected canConfirmRevoke(): boolean {
    return this.revokeReason().trim().length > 0;
  }

  protected async confirmRevoke(): Promise<void> {
    const scope = this.location.scope();
    const deviceId = this.revokeTargetId();
    if (!scope || !deviceId || !this.canConfirmRevoke()) {
      return;
    }
    this.revokeSubmitting.set(true);
    this.revokeError.set(null);
    try {
      await firstValueFrom(this.api.revoke(scope, deviceId, this.revokeReason().trim()));
      await this.reload();
      this.cancelRevoke();
    } catch (error) {
      this.revokeError.set(this.describe(error));
    } finally {
      this.revokeSubmitting.set(false);
    }
  }

  /** «Заменить устройство» is revoke, then pair the replacement: the screen says so rather than issuing a code. */
  protected toggleReplaceHint(deviceId: string): void {
    this.replaceHintId.update((current) => (current === deviceId ? null : deviceId));
  }

  // -------------------------------------------------------------------------------------- history

  protected async toggleHistory(deviceId: string): Promise<void> {
    if (this.historyId() === deviceId) {
      this.historyId.set(null);
      return;
    }
    this.historyId.set(deviceId);
    this.history.set([]);
    this.historyCursor.set(null);
    await this.loadHistory(deviceId, null);
  }

  protected async moreHistory(): Promise<void> {
    const deviceId = this.historyId();
    if (deviceId !== null && this.historyCursor() !== null) {
      await this.loadHistory(deviceId, this.historyCursor());
    }
  }

  private async loadHistory(deviceId: string, cursor: string | null): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.historyLoading.set(true);
    this.historyError.set(false);
    try {
      const page = await this.api.sessions(scope, deviceId, cursor);
      this.history.update((current) => [...current, ...page.sessions]);
      this.historyCursor.set(page.nextCursor);
    } catch {
      this.historyError.set(true);
    } finally {
      this.historyLoading.set(false);
    }
  }

  // --------------------------------------------------------------------------------------- misc

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }

  protected formattedInstant(value: string): string {
    return formatInstantUtc(value);
  }
}
