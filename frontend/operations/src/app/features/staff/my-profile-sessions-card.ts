import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { Auth } from '../../core/auth/auth';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import {
  DeviceLabel,
  OwnSession,
  OwnSessions,
  SessionBrowser,
  SessionEndReason,
  SessionForm,
  SessionOs,
  StaffSessionsApi,
  TERMINATION_REASONS,
  TerminationReason,
} from './staff-sessions-api';

const BROWSERS: Readonly<Record<SessionBrowser, MessageKey>> = {
  CHROME: 'staff.myProfile.sessions.browser.CHROME',
  SAFARI: 'staff.myProfile.sessions.browser.SAFARI',
  FIREFOX: 'staff.myProfile.sessions.browser.FIREFOX',
  EDGE: 'staff.myProfile.sessions.browser.EDGE',
  YANDEX: 'staff.myProfile.sessions.browser.YANDEX',
  SAMSUNG: 'staff.myProfile.sessions.browser.SAMSUNG',
  OTHER: 'staff.myProfile.sessions.browser.OTHER',
};

const SYSTEMS: Readonly<Record<SessionOs, MessageKey>> = {
  WINDOWS: 'staff.myProfile.sessions.os.WINDOWS',
  MACOS: 'staff.myProfile.sessions.os.MACOS',
  LINUX: 'staff.myProfile.sessions.os.LINUX',
  ANDROID: 'staff.myProfile.sessions.os.ANDROID',
  IOS: 'staff.myProfile.sessions.os.IOS',
  CHROMEOS: 'staff.myProfile.sessions.os.CHROMEOS',
  OTHER: 'staff.myProfile.sessions.os.OTHER',
};

const FORMS: Readonly<Record<SessionForm, MessageKey>> = {
  DESKTOP: 'staff.myProfile.sessions.form.DESKTOP',
  PHONE: 'staff.myProfile.sessions.form.PHONE',
  TABLET: 'staff.myProfile.sessions.form.TABLET',
};

const REASONS: Readonly<Record<TerminationReason, MessageKey>> = {
  LOST_DEVICE: 'staff.myProfile.sessions.reason.LOST_DEVICE',
  SHARED_DEVICE: 'staff.myProfile.sessions.reason.SHARED_DEVICE',
  SUSPECTED_COMPROMISE: 'staff.myProfile.sessions.reason.SUSPECTED_COMPROMISE',
  JUST_IN_CASE: 'staff.myProfile.sessions.reason.JUST_IN_CASE',
};

const ENDED_BY: Readonly<Record<SessionEndReason, MessageKey>> = {
  SIGNED_OUT: 'staff.myProfile.sessions.endedBy.SIGNED_OUT',
  SIGNED_OUT_EVERYWHERE: 'staff.myProfile.sessions.endedBy.SIGNED_OUT_EVERYWHERE',
  PASSWORD_RESET: 'staff.myProfile.sessions.endedBy.PASSWORD_RESET',
  ENDED_ONE: 'staff.myProfile.sessions.endedBy.ENDED_ONE',
  EXPIRED: 'staff.myProfile.sessions.endedBy.EXPIRED',
};

/** What the confirmation is about to do: every session, or one. */
type Pending = { readonly scope: 'ALL' } | { readonly scope: 'ONE'; readonly session: OwnSession };

/**
 * «Сеансы» on «Мой профиль» (ADR 0176, gap map row `X.5`): the account's sessions, labelled by
 * the device each signed in from, and the two acts that end them.
 *
 * **Whose sessions these are is said in words.** A session belongs to the account, which may serve
 * more than one company, so the card says that ending one ends it in every company, and the
 * confirmation says so again. Nothing here is a manager's view: there is no way to open a
 * colleague's list, and the platform has no route that takes another person's id.
 *
 * **Keycloak is the authority for whether a session is live.** When it could not be asked the card
 * says the list is only what this system recorded, and still offers the act: someone who has lost
 * a phone must not be left without the one thing they came for.
 *
 * **A falsely reassuring «done» is the failure that matters.** When the platform could not reach
 * the sign-in service nothing was ended, the confirmation stays open and says exactly that. Only
 * after the platform answers 204 does the card clear this device's tokens and send the person to
 * the sign-in page -- this device is among the sessions the act ends.
 */
@Component({
  selector: 'q-my-profile-sessions-card',
  imports: [TPipe],
  templateUrl: './my-profile-sessions-card.html',
  styleUrl: './my-profile-sessions-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MyProfileSessionsCard {
  private readonly api = inject(StaffSessionsApi);
  private readonly tenant = inject(CurrentTenant);
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);
  protected readonly i18n = inject(I18n);

  protected readonly reasons = TERMINATION_REASONS;

  protected readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  protected readonly sessions = signal<OwnSessions | null>(null);

  protected readonly pending = signal<Pending | null>(null);
  protected readonly reason = signal<TerminationReason | null>(null);
  protected readonly busy = signal(false);
  protected readonly failure = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected readonly active = computed(() =>
    (this.sessions()?.items ?? []).filter((session) => session.state === 'ACTIVE'),
  );
  protected readonly ended = computed(() =>
    (this.sessions()?.items ?? []).filter((session) => session.state === 'ENDED'),
  );

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      this.state.set('error');
      return;
    }
    try {
      this.sessions.set(await this.api.list(tenantId));
      this.state.set('ready');
    } catch {
      this.state.set('error');
    }
  }

  protected deviceName(label: DeviceLabel | null): string {
    if (label === null) {
      return this.i18n.t('staff.myProfile.sessions.unknownDevice');
    }
    return this.i18n.t('staff.myProfile.sessions.device', {
      browser: this.i18n.t(BROWSERS[label.browser]),
      os: this.i18n.t(SYSTEMS[label.os]),
      form: this.i18n.t(FORMS[label.form]),
    });
  }

  protected when(iso: string): string {
    const instant = new Date(iso);
    if (Number.isNaN(instant.getTime())) {
      return '';
    }
    return new Intl.DateTimeFormat(this.i18n.locale(), {
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(instant);
  }

  protected endedBy(session: OwnSession): string | null {
    return session.endReason === null ? null : this.i18n.t(ENDED_BY[session.endReason]);
  }

  protected reasonLabel(reason: TerminationReason): string {
    return this.i18n.t(REASONS[reason]);
  }

  // ------------------------------------------------------------------------------ the acts

  protected askToEndAll(): void {
    this.open({ scope: 'ALL' });
  }

  protected askToEndOne(session: OwnSession): void {
    this.open({ scope: 'ONE', session });
  }

  private open(pending: Pending): void {
    this.notice.set(null);
    this.failure.set(null);
    this.reason.set(null);
    this.pending.set(pending);
  }

  protected cancel(): void {
    if (!this.busy()) {
      this.pending.set(null);
    }
  }

  protected chooseReason(code: TerminationReason): void {
    this.reason.set(code);
    this.failure.set(null);
  }

  /** How many sessions the confirmation says will end. */
  protected endingCount(): number {
    const pending = this.pending();
    return pending === null || pending.scope === 'ONE' ? 1 : this.active().length;
  }

  protected async confirm(): Promise<void> {
    const pending = this.pending();
    const reason = this.reason();
    const tenantId = this.tenant.tenantId();
    if (pending === null || reason === null || !tenantId || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.failure.set(null);
    try {
      if (pending.scope === 'ALL') {
        await this.api.terminateAll(tenantId, reason);
        await this.leave();
        return;
      }
      await this.api.terminateOne(tenantId, pending.session.id, reason);
      if (pending.session.current) {
        await this.leave();
        return;
      }
      this.pending.set(null);
      this.notice.set(this.i18n.t('staff.myProfile.sessions.endedOne'));
      await this.load();
    } catch (error) {
      this.failure.set(this.describe(error));
    } finally {
      this.busy.set(false);
    }
  }

  /** This device is among the sessions the act ended: forget its tokens and ask for a sign-in. */
  private async leave(): Promise<void> {
    this.auth.forgetLocalSession();
    await this.router.navigateByUrl('/login');
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      if (error.code === ApiErrorCode.IDENTITY_PROVIDER_UNAVAILABLE) {
        return this.i18n.t('staff.myProfile.sessions.notEnded');
      }
      if (error.code === ApiErrorCode.RATE_LIMIT_EXCEEDED) {
        return this.i18n.t('staff.myProfile.sessions.rateLimited');
      }
      if (error.code === ApiErrorCode.RESOURCE_NOT_FOUND) {
        return this.i18n.t('staff.myProfile.sessions.gone');
      }
    }
    return this.i18n.t('staff.myProfile.sessions.failed');
  }
}
