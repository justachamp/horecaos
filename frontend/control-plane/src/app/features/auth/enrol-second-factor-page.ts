import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { RETURN_TO_KEY } from '../../core/auth/guards';
import { MfaTicket } from '../../core/auth/mfa-ticket';
import { SessionContextService } from '../../core/auth/session-context.service';
import { StaffSessionResponse } from '../../core/auth/staff-session';
import { I18nService } from '../../core/i18n/i18n.service';
import { MfaEnrolmentForm } from './mfa-enrolment';

/**
 * Where a refused sign-in sends an account that must hold a second factor and holds none
 * (ADR 0148, Decision 4): `/enrol-second-factor`.
 *
 * Outside the auth guard, for the reason `/login` is: the platform revoked the session it had
 * just issued, so there is none to guard on. What opens the page is the enrolment ticket held in
 * memory by {@link MfaTicket}; with none, the page says so and points back at sign-in instead of
 * rendering a form that could only fail. Confirming the first code returns a session, which this
 * page adopts: the person arrives where they were going without typing the password a third time.
 */
@Component({
  selector: 'app-enrol-second-factor-page',
  imports: [RouterLink, MfaEnrolmentForm],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="card">
        <span class="q-emphasis wordmark">{{ i18n.t('app.name') }}<span class="dot">.</span></span>
        <h1 class="q-title heading">{{ i18n.t('mfa.enrol.title') }}</h1>
        @if (ticket.ticket(); as current) {
          <app-mfa-enrolment [ticket]="current" (completed)="onCompleted($event)" />
        } @else {
          <p class="q-body" role="alert" data-testid="mfa-no-ticket">
            {{ i18n.t('mfa.enrol.noTicket') }}
          </p>
          <a class="q-body-sm back" routerLink="/login">{{ i18n.t('mfa.enrol.toSignIn') }}</a>
        }
      </div>
    </div>
  `,
  styles: `
    :host {
      display: block;
      height: 100%;
    }

    .page {
      min-height: 100%;
      display: flex;
      align-items: center;
      justify-content: center;
      background: var(--q-surface-1);
      padding: 16px;
      box-sizing: border-box;
    }

    .card {
      width: 100%;
      max-width: 400px;
      padding: 32px;
      background: var(--q-canvas);
      border: 1px solid var(--q-hairline);
      border-radius: var(--q-radius);
    }

    .wordmark {
      color: var(--q-ink);
    }

    .dot {
      color: var(--q-primary);
    }

    .heading {
      margin: 16px 0;
    }

    .back {
      display: block;
      margin-top: 16px;
      color: var(--q-primary);
    }
  `,
})
export class EnrolSecondFactorPage {
  protected readonly ticket = inject(MfaTicket);
  protected readonly i18n = inject(I18nService);
  private readonly auth = inject(AuthService);
  private readonly session = inject(SessionContextService);
  private readonly router = inject(Router);

  protected async onCompleted(session: StaffSessionResponse | null): Promise<void> {
    this.ticket.clear();
    if (session === null) {
      void this.router.navigateByUrl('/login');
      return;
    }
    this.auth.adoptSession(session);
    await this.session.load();
    void this.router.navigateByUrl(takeReturnTo());
  }
}

/** The deep link the guard saved before sending the person to sign in, if it is a same-document path. */
function takeReturnTo(): string {
  let target = '/';
  try {
    const saved = globalThis.sessionStorage?.getItem(RETURN_TO_KEY);
    if (saved && saved.startsWith('/') && !saved.startsWith('//')) {
      target = saved;
    }
    globalThis.sessionStorage?.removeItem(RETURN_TO_KEY);
  } catch {
    // No storage: land on the default.
  }
  return target;
}
