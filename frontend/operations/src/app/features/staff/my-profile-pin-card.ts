import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiError } from '../../core/api/problem-details';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { IssuedCode, MyPinView, StaffPinApi } from './pin-api';
import { formatInstantUtc, formatTimeUtc, groupCode } from './terminals/instant-format';

const ENDED_KEYS: Readonly<Record<string, MessageKey>> = {
  LOCKED: 'staff.terminals.history.reason.LOCKED',
  SUPERSEDED: 'staff.terminals.history.reason.SUPERSEDED',
  REVOKED: 'staff.terminals.history.reason.REVOKED',
  PIN_RESET: 'staff.terminals.history.reason.PIN_RESET',
  EMPLOYMENT_ENDED: 'staff.terminals.history.reason.EMPLOYMENT_ENDED',
  IDLE: 'staff.terminals.history.reason.IDLE',
  MAX_AGE: 'staff.terminals.history.reason.MAX_AGE',
};

/**
 * «Мой PIN» on «Мой профиль» (ADR 0155, Decision 2): the state of my PIN, a code I can ask for myself, and the last
 * twenty times a terminal was unlocked as me.
 *
 * **The list is the control that makes a manager-issued code safe enough.** A manager could type the code first and set a
 * PIN the person does not know, so the person's own profile shows every unlock under their name, at which tablet and
 * when: an unlock at a tablet they were not at is the reason to ask for a reset. A code a manager issued is named here,
 * with who issued it, so one nobody expected is visible.
 *
 * The code I ask for is shown once, in this component's memory, and is gone when I close it. Changing a PIN is done at a
 * terminal, while unlocked; this card does not touch a PIN.
 */
@Component({
  selector: 'q-my-profile-pin-card',
  imports: [TPipe],
  templateUrl: './my-profile-pin-card.html',
  styleUrl: './my-profile-pin-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MyProfilePinCard {
  private readonly api = inject(StaffPinApi);
  private readonly tenant = inject(CurrentTenant);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<'loading' | 'ready' | 'absent' | 'error'>('loading');
  protected readonly pin = signal<MyPinView | null>(null);
  protected readonly issued = signal<IssuedCode | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      this.state.set('absent');
      return;
    }
    try {
      this.pin.set(await this.api.mine(tenantId));
      this.state.set('ready');
    } catch (failure) {
      // A sign-in with no staff record in this company has no PIN to show: say so rather than "error".
      this.state.set(failure instanceof ApiError && failure.status === 404 ? 'absent' : 'error');
    }
  }

  protected async getCode(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (!tenantId || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      this.issued.set(await firstValueFrom(this.api.issueMine(tenantId)));
      await this.load();
    } catch {
      this.error.set(this.i18n.t('staff.myPin.failed'));
    } finally {
      this.busy.set(false);
    }
  }

  protected dismissCode(): void {
    this.issued.set(null);
  }

  protected shownCode(code: string): string {
    return groupCode(code);
  }

  protected when(value: string): string {
    return formatInstantUtc(value);
  }

  protected until(value: string): string {
    return formatTimeUtc(value);
  }

  protected endedLabel(reason: string | null): string {
    if (reason === null) {
      return this.i18n.t('staff.terminals.history.open');
    }
    const key = ENDED_KEYS[reason];
    return key === undefined ? reason : this.i18n.t(key);
  }
}
