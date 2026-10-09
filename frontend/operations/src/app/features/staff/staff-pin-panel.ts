import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { IssuedCode, PinStateView, StaffPinApi } from './pin-api';
import { StaffReach } from './staff-reach';
import { formatInstantUtc, formatTimeUtc, groupCode } from './terminals/instant-format';

/**
 * «PIN терминала» on the person card's Безопасность tab (ADR 0155, Decisions 2 and 7).
 *
 * It shows the state of the credential (not set, set and since when, locked) and whether a code is waiting and who
 * issued it, and offers the two things a manager may do: **issue an activation code** and **reset**. It offers no way to
 * type, choose or read a PIN, because the platform has none: the code is shown once and typed by the person at a
 * terminal, where the person chooses the PIN. The code is held in this component's memory until the manager closes it
 * and is never put in a URL, a log or a store.
 *
 * Who may use which route is the server's to say. A manager whose reach is the company uses the company-wide routes; one
 * whose reach stops at a branch uses that branch's twin, for the branch she is working in, and is refused for a person who
 * also works elsewhere. The panel shows that refusal in words.
 */
@Component({
  selector: 'q-staff-pin-panel',
  imports: [TPipe],
  templateUrl: './staff-pin-panel.html',
  styleUrl: './staff-pin-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StaffPinPanel implements OnInit {
  private readonly api = inject(StaffPinApi);
  private readonly capabilities = inject(SessionCapabilities);
  private readonly reach = inject(StaffReach);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  readonly tenantId = input.required<string>();
  readonly memberId = input.required<string>();
  /** The person's own card: a person asks for their own code in «Мой профиль», not through a manager's route. */
  readonly isOwnAccount = input(false);

  protected readonly canRead = computed(() => this.capabilities.has('STAFF_PROFILE_READ'));
  protected readonly canManage = computed(
    () => this.capabilities.has('STAFF_PROFILE_MANAGE') && !this.isOwnAccount(),
  );

  protected readonly state = signal<'loading' | 'ready' | 'outOfReach' | 'unknown'>('loading');
  protected readonly pin = signal<PinStateView | null>(null);

  protected readonly form = signal<'none' | 'issue' | 'reset'>('none');
  protected readonly reason = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  /** The code, held only while the manager reads it out. */
  protected readonly issued = signal<IssuedCode | null>(null);

  ngOnInit(): void {
    void this.load();
  }

  /** A branch manager's twin for the branch she is working in; the company's routes for everyone wider. */
  private scope() {
    return this.reach.scoped() ? (this.location.scope() ?? undefined) : undefined;
  }

  private async load(): Promise<void> {
    if (!this.canRead()) {
      return;
    }
    this.state.set('loading');
    try {
      this.pin.set(await this.api.state(this.tenantId(), this.memberId(), this.scope()));
      this.state.set('ready');
    } catch (failure) {
      this.state.set(
        failure instanceof ApiError && failure.status === 404 ? 'outOfReach' : 'unknown',
      );
    }
  }

  protected open(kind: 'issue' | 'reset'): void {
    this.reason.set('');
    this.error.set(null);
    this.notice.set(null);
    this.issued.set(null);
    this.form.set(kind);
  }

  protected close(): void {
    this.form.set('none');
  }

  /** The manager has read the code out: it is gone from the screen, and from memory. */
  protected dismissCode(): void {
    this.issued.set(null);
  }

  protected onReason(event: Event): void {
    this.reason.set((event.target as HTMLTextAreaElement).value);
    this.error.set(null);
  }

  protected async confirm(): Promise<void> {
    const reason = this.reason().trim();
    const kind = this.form();
    if (reason === '' || this.busy() || kind === 'none') {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      if (kind === 'issue') {
        this.issued.set(
          await firstValueFrom(
            this.api.issue(this.tenantId(), this.memberId(), reason, this.scope()),
          ),
        );
      } else {
        await firstValueFrom(
          this.api.reset(this.tenantId(), this.memberId(), reason, this.scope()),
        );
        this.notice.set(this.i18n.t('staff.pin.resetDone'));
      }
      this.form.set('none');
      await this.load();
    } catch (failure) {
      this.error.set(this.describe(failure));
    } finally {
      this.busy.set(false);
    }
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

  private describe(failure: unknown): string {
    if (failure instanceof ApiError) {
      if (failure.status === 404) {
        return this.i18n.t('staff.pin.outOfReach');
      }
      if (failure.code === ApiErrorCode.INSUFFICIENT_CAPABILITY) {
        return this.i18n.t('staff.pin.notAllowed');
      }
    }
    return this.i18n.t('staff.pin.failed');
  }
}
